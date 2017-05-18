package com.bumptech.glide.load.engine.bitmap_recycle;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * An {@link com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool} implementation that uses an
 * {@link com.bumptech.glide.load.engine.bitmap_recycle.LruPoolStrategy} to bucket {@link Bitmap}s and then uses an LRU
 * eviction policy to evict {@link android.graphics.Bitmap}s from the least recently used bucket in order to keep
 * the pool below a given maximum size limit.
 * Bitmap的一个复用池
 */
public class LruBitmapPool implements BitmapPool {
    private static final String TAG = "LruBitmapPool";
    private static final Bitmap.Config DEFAULT_CONFIG = Bitmap.Config.ARGB_8888;
    //复用策略，API19以上可以做到下兼容，否则完全匹配的复用
    //也是实际的执行者
    private final LruPoolStrategy strategy;
    //当前允许处理的Bitmap的Config
    private final Set<Bitmap.Config> allowedConfigs;
    //初始化设置的循环池的最大大小，可能因为改变大小的原因，会导致和maxSize不同
    //作为基准用于乘以比例修改缓存池实际大小
    private final int initialMaxSize;
    //默认为不处理实现
    private final BitmapTracker tracker;
    //循环池实际的最大大小
    private int maxSize;
    //当前循环池已经使用的大小
    private int currentSize;
    //复用击中次数
    private int hits;
    //没法复用的次数
    private int misses;
    //有效的put操作次数
    private int puts;
    //自动清理的bitmap的数量
    private int evictions;

    // Exposed for testing only.
    LruBitmapPool(int maxSize, LruPoolStrategy strategy, Set<Bitmap.Config> allowedConfigs) {
        this.initialMaxSize = maxSize;
        this.maxSize = maxSize;
        this.strategy = strategy;
        this.allowedConfigs = allowedConfigs;
        this.tracker = new NullBitmapTracker();//默认不处理
    }

    /**
     * Constructor for LruBitmapPool.
     *
     * @param maxSize The initial maximum size of the pool in bytes.
     */
    public LruBitmapPool(int maxSize) {
        this(maxSize, getDefaultStrategy(), getDefaultAllowedConfigs());
    }

    /**
     * Constructor for LruBitmapPool.
     *
     * @param maxSize The initial maximum size of the pool in bytes.
     * @param allowedConfigs A white listed set of {@link android.graphics.Bitmap.Config} that are allowed to be put
     *                       into the pool. Configs not in the allowed set will be rejected.
     */
    public LruBitmapPool(int maxSize, Set<Bitmap.Config> allowedConfigs) {
        this(maxSize, getDefaultStrategy(), allowedConfigs);
    }

    @Override
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * 通过基准和给定的比例重新设置复用池的大小
     * 并且清理可能多出的数据
     */
    @Override
    public synchronized void setSizeMultiplier(float sizeMultiplier) {
        maxSize = Math.round(initialMaxSize * sizeMultiplier);
        evict();
    }

    @Override
    public synchronized boolean put(Bitmap bitmap) {
        if (bitmap == null) {
            throw new NullPointerException("Bitmap must not be null");
        }
        //可以复用的条件
        //1.必须mutable
        //2.当前bitmap大小必须小于复用池的最大限制
        if (!bitmap.isMutable() || strategy.getSize(bitmap) > maxSize || !allowedConfigs.contains(bitmap.getConfig())) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Reject bitmap from pool"
                        + ", bitmap: " + strategy.logBitmap(bitmap)
                        + ", is mutable: " + bitmap.isMutable()
                        + ", is allowed config: " + allowedConfigs.contains(bitmap.getConfig()));
            }
            return false;
        }
        //计算当前要添加的bitmap的大小
        final int size = strategy.getSize(bitmap);
        strategy.put(bitmap);
        tracker.add(bitmap);

        puts++;
        //重新计算当前复用池的大小
        currentSize += size;

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Put bitmap in pool=" + strategy.logBitmap(bitmap));
        }
        dump();
        //清理多余数据，保证当前复用池的大小满足条件
        evict();
        return true;
    }

    /**
     * 确保当前的大小小于等于循环池的最大大小
     */
    private void evict() {
        trimToSize(maxSize);
    }

    /**
     * 获取复用池中的bitmap，并且擦除所有的像素
     */
    @Override
    public synchronized Bitmap get(int width, int height, Bitmap.Config config) {
        Bitmap result = getDirty(width, height, config);
        if (result != null) {//设置bitmap的所有像素为透明
            // Bitmaps in the pool contain random data that in some cases must be cleared for an image to be rendered
            // correctly. we shouldn't force all consumers to independently erase the contents individually, so we do so
            // here. See issue #131.
            result.eraseColor(Color.TRANSPARENT);
        }

        return result;
    }

    /**
     * 获取复用池中的bitmap
     * 但是注意这里的bitmap没有擦除像素，可能会导致绘制异常
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    @Override
    public synchronized Bitmap getDirty(int width, int height, Bitmap.Config config) {
        // Config will be null for non public config types, which can lead to transformations naively passing in
        // null as the requested config here. See issue #194.
        final Bitmap result = strategy.get(width, height, config != null ? config : DEFAULT_CONFIG);
        if (result == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Missing bitmap=" + strategy.logBitmap(width, height, config));
            }
            misses++;
        } else {
            hits++;
            //计算取出指定bitmap后复用池的大小
            currentSize -= strategy.getSize(result);
            tracker.remove(result);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
                result.setHasAlpha(true);
            }
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Get bitmap=" + strategy.logBitmap(width, height, config));
        }
        dump();

        return result;
    }

    @Override
    public void clearMemory() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "clearMemory");
        }
        trimToSize(0);//相当于清空复用池
    }

    @SuppressLint("InlinedApi")
    @Override
    public void trimMemory(int level) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "trimMemory, level=" + level);
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            //系统内存低，且当前已经到清理列表的中部，此时最好清理内存，从而避免被回收
            clearMemory();
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            //系统内存低，此时处于清理列表中不容易被清理的地方，但是最好还是清理一些内存，从而保证系统内存充裕
            trimToSize(maxSize / 2);
        }
    }

    /**
     * 保证当前复用池中的大小小于等于size
     * @param size 当前允许大小的最大值
     */
    private synchronized void trimToSize(int size) {
        while (currentSize > size) {
            final Bitmap removed = strategy.removeLast();//移除末位的Bitmap
            // TODO: This shouldn't ever happen, see #331.
            if (removed == null) {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Size mismatch, resetting");
                    dumpUnchecked();
                }
                currentSize = 0;
                return;
            }

            tracker.remove(removed);
            currentSize -= strategy.getSize(removed);//重新计算当期复用池大小
            removed.recycle();//当前Bitmap不需要参与复用，直接回收
            evictions++;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Evicting bitmap=" + strategy.logBitmap(removed));
            }
            dump();
        }
    }

    private void dump() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            dumpUnchecked();
        }
    }

    private void dumpUnchecked() {
        Log.v(TAG, "Hits="  + hits
                    + ", misses=" + misses
                    + ", puts=" + puts
                    + ", evictions=" + evictions
                    + ", currentSize=" + currentSize
                    + ", maxSize=" + maxSize
                    + "\nStrategy=" + strategy);
    }

    /**
     * BitmapPool默认使用的策略
     * 实际上是因为BitmapFactory在不同版本对于inBitmap的支持不同导致的
     */
    private static LruPoolStrategy getDefaultStrategy() {
        final LruPoolStrategy strategy;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {//大于API19
            //基于bitmap的大小和配置的向下兼容的复用策略
            //即原本大的bitmap可以用于小的bitmap的复用
            strategy = new SizeConfigStrategy();
        } else {
            //基于宽、高和配置的完全匹配复用策略
            strategy = new AttributeStrategy();
        }
        return strategy;
    }

    /**
     * 获取默认支持的Bitmap.Config
     * ALPHA_8,ARGB_4444,ARGB_8888,RGB_565;
     * 其实就是在API19之后添加null的支持
     * @return
     */
    private static Set<Bitmap.Config> getDefaultAllowedConfigs() {
        Set<Bitmap.Config> configs = new HashSet<Bitmap.Config>();
        configs.addAll(Arrays.asList(Bitmap.Config.values()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {//大于API19，支持null
            configs.add(null);
        }
        return Collections.unmodifiableSet(configs);
    }

    private interface BitmapTracker {
        void add(Bitmap bitmap);
        void remove(Bitmap bitmap);
    }

    @SuppressWarnings("unused")
    // Only used for debugging
    private static class ThrowingBitmapTracker implements BitmapTracker {
        private final Set<Bitmap> bitmaps = Collections.synchronizedSet(new HashSet<Bitmap>());

        @Override
        public void add(Bitmap bitmap) {
            if (bitmaps.contains(bitmap)) {
                throw new IllegalStateException("Can't add already added bitmap: " + bitmap + " [" + bitmap.getWidth()
                        + "x" + bitmap.getHeight() + "]");
            }
            bitmaps.add(bitmap);
        }

        @Override
        public void remove(Bitmap bitmap) {
            if (!bitmaps.contains(bitmap)) {
                throw new IllegalStateException("Cannot remove bitmap not in tracker");
            }
            bitmaps.remove(bitmap);
        }
    }

    private static class NullBitmapTracker implements BitmapTracker {
        @Override
        public void add(Bitmap bitmap) {
            // Do nothing.
        }

        @Override
        public void remove(Bitmap bitmap) {
            // Do nothing.
        }
    }
}
