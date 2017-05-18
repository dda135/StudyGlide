package com.bumptech.glide.load.engine.cache;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * A calculator that tries to intelligently determine cache sizes for a given device based on some constants and the
 * devices screen density, width, and height.
 * 一个计算器尝试通过一些配置、屏幕密度、宽高这些来合理的确定缓存的大小
 */
public class MemorySizeCalculator {
    private static final String TAG = "MemorySizeCalculator";

    // Visible for testing.
    // ARGB8888的一个像素所占用的字节
    static final int BYTES_PER_ARGB_8888_PIXEL = 4;
    // 内存缓存期望2个屏幕的字节数大小
    static final int MEMORY_CACHE_TARGET_SCREENS = 2;
    // Bitmap循环池期望4个屏幕的字节数大小
    static final int BITMAP_POOL_TARGET_SCREENS = 4;
    // 非低内存手机可以使用的最大内存的比例
    static final float MAX_SIZE_MULTIPLIER = 0.4f;
    // 低内存手机可以使用的最大内存的比例
    static final float LOW_MEMORY_MAX_SIZE_MULTIPLIER = 0.33f;

    private final int bitmapPoolSize;
    private final int memoryCacheSize;
    private final Context context;

    interface ScreenDimensions {
        int getWidthPixels();
        int getHeightPixels();
    }

    public MemorySizeCalculator(Context context) {
        this(context,
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE),
                new DisplayMetricsScreenDimensions(context.getResources().getDisplayMetrics()));
    }

    // Visible for testing.
    MemorySizeCalculator(Context context, ActivityManager activityManager, ScreenDimensions screenDimensions) {
        this.context = context;
        final int maxSize = getMaxSize(activityManager);
        //这里指的是屏幕的所有像素所占用的字节数目，看得出来这里是以ARGB8888为基准，一个像素4个字节
        final int screenSize = screenDimensions.getWidthPixels() * screenDimensions.getHeightPixels()
                * BYTES_PER_ARGB_8888_PIXEL;
        //想要的BitmapPool的大小，因为循环池需要存储Bitmap，还是要有大小范围。这里相当于4个屏幕图片的大小
        int targetPoolSize = screenSize * BITMAP_POOL_TARGET_SCREENS;
        //想要的内存缓存得大小。这里相当于2个屏幕图片的大小
        int targetMemoryCacheSize = screenSize * MEMORY_CACHE_TARGET_SCREENS;
        //不过预期的大小必须在可以分配的总大小之内，否则需要处理
        if (targetMemoryCacheSize + targetPoolSize <= maxSize) {
            //满足预期的大小，可以直接使用
            memoryCacheSize = targetMemoryCacheSize;
            bitmapPoolSize = targetPoolSize;
        } else {//不满足预期的大小
            //先计算当前最大内存可以放置几个屏幕大小的字节
            int part = Math.round((float) maxSize / (BITMAP_POOL_TARGET_SCREENS + MEMORY_CACHE_TARGET_SCREENS));
            //这里相当于按比例均分最大内存
            memoryCacheSize = part * MEMORY_CACHE_TARGET_SCREENS;
            bitmapPoolSize = part * BITMAP_POOL_TARGET_SCREENS;
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Calculated memory cache size: " + toMb(memoryCacheSize) + " pool size: " + toMb(bitmapPoolSize)
                    + " memory class limited? " + (targetMemoryCacheSize + targetPoolSize > maxSize) + " max size: "
                    + toMb(maxSize) + " memoryClass: " + activityManager.getMemoryClass() + " isLowMemoryDevice: "
                    + isLowMemoryDevice(activityManager));
        }
    }

    /**
     * Returns the recommended memory cache size for the device it is run on in bytes.
     */
    public int getMemoryCacheSize() {
        return memoryCacheSize;
    }

    /**
     * Returns the recommended bitmap pool size for the device it is run on in bytes.
     */
    public int getBitmapPoolSize() {
        return bitmapPoolSize;
    }

    private static int getMaxSize(ActivityManager activityManager) {
        //获取当前Application运行时可以分配的最大内存，默认返回M，所以需要*1024*1024
        final int memoryClassBytes = activityManager.getMemoryClass() * 1024 * 1024;
        final boolean isLowMemoryDevice = isLowMemoryDevice(activityManager);
        //当前如果是低内存设备的话采用最大的0.33比例内存，否则采用0.4
        return Math.round(memoryClassBytes
                * (isLowMemoryDevice ? LOW_MEMORY_MAX_SIZE_MULTIPLIER : MAX_SIZE_MULTIPLIER));
    }

    private String toMb(int bytes) {
        return Formatter.formatFileSize(context, bytes);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private static boolean isLowMemoryDevice(ActivityManager activityManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return activityManager.isLowRamDevice();
        } else {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB;
        }
    }

    private static class DisplayMetricsScreenDimensions implements ScreenDimensions {
        private final DisplayMetrics displayMetrics;

        public DisplayMetricsScreenDimensions(DisplayMetrics displayMetrics) {
            this.displayMetrics = displayMetrics;
        }

        @Override
        public int getWidthPixels() {
            return displayMetrics.widthPixels;
        }

        @Override
        public int getHeightPixels() {
            return displayMetrics.heightPixels;
        }
    }
}
