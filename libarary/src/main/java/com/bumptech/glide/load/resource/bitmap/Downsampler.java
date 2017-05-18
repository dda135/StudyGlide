package com.bumptech.glide.load.resource.bitmap;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.util.ByteArrayPool;
import com.bumptech.glide.util.ExceptionCatchingInputStream;
import com.bumptech.glide.util.MarkEnforcingInputStream;
import com.bumptech.glide.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Queue;
import java.util.Set;

/**
 * A base class with methods for loading and decoding images from InputStreams.
 * 实际上的解析和处理Bitmap的工作者
 */
public abstract class Downsampler implements BitmapDecoder<InputStream> {
    private static final String TAG = "Downsampler";

    private static final Set<ImageHeaderParser.ImageType> TYPES_THAT_USE_POOL = EnumSet.of(
            ImageHeaderParser.ImageType.JPEG, ImageHeaderParser.ImageType.PNG_A, ImageHeaderParser.ImageType.PNG);

    private static final Queue<BitmapFactory.Options> OPTIONS_QUEUE = Util.createQueue(0);

    /**
     * Load and scale the image uniformly (maintaining the image's aspect ratio) so that the smallest edge of the
     * image will be between 1x and 2x the requested size. The larger edge has no maximum size.
     */
    public static final Downsampler AT_LEAST = new Downsampler() {
        @Override
        protected int getSampleSize(int inWidth, int inHeight, int outWidth, int outHeight) {
            //采用宽高中小的采样率
            return Math.min(inHeight / outHeight, inWidth / outWidth);
        }

        @Override
        public String getId() {
            return "AT_LEAST.com.bumptech.glide.load.data.bitmap";
        }
    };

    /**
     * Load and scale the image uniformly (maintaining the image's aspect ratio) so that largest edge of the image
     * will be between 1/2x and 1x of the requested size. The smaller edge has no minimum size.
     */
    public static final Downsampler AT_MOST = new Downsampler() {
        @Override
        protected int getSampleSize(int inWidth, int inHeight, int outWidth, int outHeight) {
            int maxIntegerFactor = (int) Math.ceil(Math.max(inHeight / (float) outHeight,
                inWidth / (float) outWidth));
            int lesserOrEqualSampleSize = Math.max(1, Integer.highestOneBit(maxIntegerFactor));
            return lesserOrEqualSampleSize << (lesserOrEqualSampleSize < maxIntegerFactor ? 1 : 0);
        }

        @Override
        public String getId() {
            return "AT_MOST.com.bumptech.glide.load.data.bitmap";
        }
    };

    /**
     * Load the image at its original size.
     */
    public static final Downsampler NONE = new Downsampler() {
        @Override
        protected int getSampleSize(int inWidth, int inHeight, int outWidth, int outHeight) {
            return 0;
        }

        @Override
        public String getId() {
            return "NONE.com.bumptech.glide.load.data.bitmap";
        }
    };

    // 5MB. This is the max image header size we can handle, we preallocate a much smaller buffer but will resize up to
    // this amount if necessary.
    private static final int MARK_POSITION = 5 * 1024 * 1024;


    /**
     * Load the image for the given InputStream. If a recycled Bitmap whose dimensions exactly match those of the image
     * for the given InputStream is available, the operation is much less expensive in terms of memory.
     *
     * <p>
     *     Note - this method will throw an exception of a Bitmap with dimensions not matching
     *     those of the image for the given InputStream is provided.
     * </p>
     *
     * @param is An {@link InputStream} to the data for the image.
     * @param pool A pool of recycled bitmaps.
     * @param outWidth The width the final image should be close to.
     * @param outHeight The height the final image should be close to.
     * @return A new bitmap containing the image from the given InputStream, or recycle if recycle is not null.
     */
    @SuppressWarnings("resource")
    // see BitmapDecoder.decode
    @Override
    public Bitmap decode(InputStream is, BitmapPool pool, int outWidth, int outHeight, DecodeFormat decodeFormat) {
        final ByteArrayPool byteArrayPool = ByteArrayPool.get();
        final byte[] bytesForOptions = byteArrayPool.getBytes();
        final byte[] bytesForStream = byteArrayPool.getBytes();
        //获取默认配置，重点其实就是还原一堆配置，然后inMutable=true（可复用）
        final BitmapFactory.Options options = getDefaultOptions();

        // Use to fix the mark limit to avoid allocating buffers that fit entire images.
        RecyclableBufferedInputStream bufferedStream = new RecyclableBufferedInputStream(
                is, bytesForStream);
        // Use to retrieve exceptions thrown while reading.
        // TODO(#126): when the framework no longer returns partially decoded Bitmaps or provides a way to determine
        // if a Bitmap is partially decoded, consider removing.
        ExceptionCatchingInputStream exceptionStream =
                ExceptionCatchingInputStream.obtain(bufferedStream);
        // Use to read data.
        // Ensures that we can always reset after reading an image header so that we can still attempt to decode the
        // full image even when the header decode fails and/or overflows our read buffer. See #283.
        MarkEnforcingInputStream invalidatingStream = new MarkEnforcingInputStream(exceptionStream);
        try {
            exceptionStream.mark(MARK_POSITION);
            int orientation = 0;
            try {
                //读取当前图片的方向
                orientation = new ImageHeaderParser(exceptionStream).getOrientation();
            } catch (IOException e) {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Cannot determine the image orientation from header", e);
                }
            } finally {
                try {
                    exceptionStream.reset();
                } catch (IOException e) {
                    if (Log.isLoggable(TAG, Log.WARN)) {
                        Log.w(TAG, "Cannot reset the input stream", e);
                    }
                }
            }

            options.inTempStorage = bytesForOptions;

            final int[] inDimens = getDimensions(invalidatingStream, bufferedStream, options);
            final int inWidth = inDimens[0];
            final int inHeight = inDimens[1];
            //通过图片方向获取旋转角度
            final int degreesToRotate = TransformationUtils.getExifOrientationDegrees(orientation);
            //获得采样率
            final int sampleSize = getRoundedSampleSize(degreesToRotate, inWidth, inHeight, outWidth, outHeight);
            //通过bitmapPool和采样率等数据获得bitmap
            final Bitmap downsampled =
                    downsampleWithSize(invalidatingStream, bufferedStream, options, pool, inWidth, inHeight, sampleSize,
                            decodeFormat);

            // BitmapFactory swallows exceptions during decodes and in some cases when inBitmap is non null, may catch
            // and log a stack trace but still return a non null bitmap. To avoid displaying partially decoded bitmaps,
            // we catch exceptions reading from the stream in our ExceptionCatchingInputStream and throw them here.
            final Exception streamException = exceptionStream.getException();
            if (streamException != null) {
                throw new RuntimeException(streamException);
            }

            Bitmap rotated = null;
            if (downsampled != null) {
                //通过之前计算的图片方向来旋转Bitmap
                rotated = TransformationUtils.rotateImageExif(downsampled, pool, orientation);
                //如果重新创建了一个bitmap，并且之前的bitmap不可复用，手动回收bitmap资源
                if (!downsampled.equals(rotated) && !pool.put(downsampled)) {
                    downsampled.recycle();
                }
            }

            return rotated;
        } finally {
            byteArrayPool.releaseBytes(bytesForOptions);
            byteArrayPool.releaseBytes(bytesForStream);
            exceptionStream.release();
            releaseOptions(options);
        }
    }

    /**
     * 计算采样率
     */
    private int getRoundedSampleSize(int degreesToRotate, int inWidth, int inHeight, int outWidth, int outHeight) {
        int targetHeight = outHeight == Target.SIZE_ORIGINAL ? inHeight : outHeight;
        int targetWidth = outWidth == Target.SIZE_ORIGINAL ? inWidth : outWidth;

        final int exactSampleSize;
        if (degreesToRotate == 90 || degreesToRotate == 270) {
            // If we're rotating the image +-90 degrees, we need to downsample accordingly so the image width is
            // decreased to near our target's height and the image height is decreased to near our target width.
            //noinspection SuspiciousNameCombination
            exactSampleSize = getSampleSize(inHeight, inWidth, targetWidth, targetHeight);
        } else {
            //默认采用的是AT_LEAST
            exactSampleSize = getSampleSize(inWidth, inHeight, targetWidth, targetHeight);
        }
        //比方说一个100X200的图片，载体为50X50，则采样率为2，默认使用小的
        //根据这个情况得到的应该是50X100

        // BitmapFactory only accepts powers of 2, so it will round down to the nearest power of two that is less than
        // or equal to the sample size we provide. Because we need to estimate the final image width and height to
        // re-use Bitmaps, we mirror BitmapFactory's calculation here. For bug, see issue #224. For algorithm see
        // http://stackoverflow.com/a/17379704/800716.
        // 因为采样率要求是2的倍数，同时Bitmap要复用的的话要求要大一些，所以这里要往最接近的小的2的倍数走
        // highestOneBit就是保留最高位的1，比方说0000 1010就是0000 1000，即10变成了8
        final int powerOfTwoSampleSize = exactSampleSize == 0 ? 0 : Integer.highestOneBit(exactSampleSize);

        //比方说一个150X200的图片，载体为50X50，则采样率为3，默认使用小的
        //然后向下去的话为2
        //根据这个情况得到的应该是75X100

        // Although functionally equivalent to 0 for BitmapFactory, 1 is a safer default for our code than 0.
        // BitmapFactory要求最小为1
        return Math.max(1, powerOfTwoSampleSize);
    }

    /**
     * 根据指定的
     */
    private Bitmap downsampleWithSize(MarkEnforcingInputStream is, RecyclableBufferedInputStream  bufferedStream,
            BitmapFactory.Options options, BitmapPool pool, int inWidth, int inHeight, int sampleSize,
            DecodeFormat decodeFormat) {
        // Prior to KitKat, the inBitmap size must exactly match the size of the bitmap we're decoding.
        Bitmap.Config config = getConfig(is, decodeFormat);//获得解析类型或者说像素存储类型
        options.inSampleSize = sampleSize;//设置采样率
        options.inPreferredConfig = config;//设置像素存储类型，如果没有，默认使用ARGB_8888
        //采样率为1或者API>19的时候
        //并且满足bitmap复用的条件
        //设置inBitmap来使用bitmap复用
        if ((options.inSampleSize == 1 || Build.VERSION_CODES.KITKAT <= Build.VERSION.SDK_INT) && shouldUsePool(is)) {
            int targetWidth = (int) Math.ceil(inWidth / (double) sampleSize);
            int targetHeight = (int) Math.ceil(inHeight / (double) sampleSize);
            // BitmapFactory will clear out the Bitmap before writing to it, so getDirty is safe.
            setInBitmap(options, pool.getDirty(targetWidth, targetHeight, config));
        }
        //稍微总结一下Bitmap可以尝试复用的条件（不代表一定有可复用的bitmap）
        //1.API19以下，大小必须完全匹配，并且图片类型必须为JPEG、PNG
        //2.API19以上都可以尝试复用
        return decodeStream(is, bufferedStream, options);
    }

    /**
     * 判断是否应该使用BitmapPool
     * 即是否可以使用bitmap复用
     */
    private static boolean shouldUsePool(InputStream is) {
        // On KitKat+, any bitmap can be used to decode any other bitmap.
        if (Build.VERSION_CODES.KITKAT <= Build.VERSION.SDK_INT) {//API>19的时候默认都是可以的
            return true;
        }

        is.mark(1024);
        try {
            final ImageHeaderParser.ImageType type = new ImageHeaderParser(is).getType();
            // cannot reuse bitmaps when decoding images that are not PNG or JPG.
            // look at : https://groups.google.com/forum/#!msg/android-developers/Mp0MFVFi1Fo/e8ZQ9FGdWdEJ
            // 在API<19的时候，只有JPEG,PNG_A和PNG可以尝试复用
            return TYPES_THAT_USE_POOL.contains(type);
        } catch (IOException e) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Cannot determine the image type from header", e);
            }
        } finally {
            try {
                is.reset();
            } catch (IOException e) {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Cannot reset the input stream", e);
                }
            }
        }
        return false;
    }

    /**
     * 通过指定的解析类型和不同的情况决定实际的解析或者像素存储类型
     */
    @SuppressWarnings("deprecation")
    private static Bitmap.Config getConfig(InputStream is, DecodeFormat format) {
        // Changing configs can cause skewing on 4.1, see issue #128.
        // API16的时候默认使用ARGB8888
        if (format == DecodeFormat.ALWAYS_ARGB_8888 || format == DecodeFormat.PREFER_ARGB_8888
                || Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN) {
            return Bitmap.Config.ARGB_8888;
        }

        boolean hasAlpha = false;
        // We probably only need 25, but this is safer (particularly since the buffer size is > 1024).
        is.mark(1024);
        try {
            //获得当前图片是否有alpha通道
            //实际上就是获得图片类型，JPEG和PNG（非PNG_A）默认false
            hasAlpha = new ImageHeaderParser(is).hasAlpha();
        } catch (IOException e) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Cannot determine whether the image has alpha or not from header for format " + format, e);
            }
        } finally {
            try {
                is.reset();
            } catch (IOException e) {
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Cannot reset the input stream", e);
                }
            }
        }
        //通过当前图片是否有透明通道决定解析类型
        //这也就是说就算默认设置了RGB_565，但是遇上GIF的时候还是会采用ARGB_8888
        return hasAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
    }

    /**
     * Determine the amount of downsampling to use for a load given the dimensions of the image to be downsampled and
     * the dimensions of the view/target the image will be displayed in.
     *
     * @see android.graphics.BitmapFactory.Options#inSampleSize
     *
     * @param inWidth The width in pixels of the image to be downsampled.
     * @param inHeight The height in piexels of the image to be downsampled.
     * @param outWidth The width in pixels of the view/target the image will be displayed in.
     * @param outHeight The height in pixels of the view/target the imag will be displayed in.
     * @return An integer to pass in to {@link BitmapFactory#decodeStream(java.io.InputStream, android.graphics.Rect,
     *          android.graphics.BitmapFactory.Options)}.
     */
    protected abstract int getSampleSize(int inWidth, int inHeight, int outWidth, int outHeight);

    /**
     * A method for getting the dimensions of an image from the given InputStream.
     * 获得当前Bitmap的宽高
     * @param is The InputStream representing the image.
     * @param options The options to pass to
     *          {@link BitmapFactory#decodeStream(java.io.InputStream, android.graphics.Rect,
     *              android.graphics.BitmapFactory.Options)}.
     * @return an array containing the dimensions of the image in the form {width, height}.
     */
    public int[] getDimensions(MarkEnforcingInputStream is, RecyclableBufferedInputStream bufferedStream,
            BitmapFactory.Options options) {
        options.inJustDecodeBounds = true;
        decodeStream(is, bufferedStream, options);
        options.inJustDecodeBounds = false;
        return new int[] { options.outWidth, options.outHeight };
    }

    /**
     * 通过BitmapFactory解析输入流得到Bitmap
     */
    private static Bitmap decodeStream(MarkEnforcingInputStream is, RecyclableBufferedInputStream bufferedStream,
            BitmapFactory.Options options) {
         if (options.inJustDecodeBounds) {
             // This is large, but jpeg headers are not size bounded so we need something large enough to minimize
             // the possibility of not being able to fit enough of the header in the buffer to get the image size so
             // that we don't fail to load images. The BufferedInputStream will create a new buffer of 2x the
             // original size each time we use up the buffer space without passing the mark so this is a maximum
             // bound on the buffer size, not a default. Most of the time we won't go past our pre-allocated 16kb.
             is.mark(MARK_POSITION);
         } else {
             // Once we've read the image header, we no longer need to allow the buffer to expand in size. To avoid
             // unnecessary allocations reading image data, we fix the mark limit so that it is no larger than our
             // current buffer size here. See issue #225.
             bufferedStream.fixMarkLimit();
         }

        final Bitmap result = BitmapFactory.decodeStream(is, null, options);

        try {
            if (options.inJustDecodeBounds) {
                is.reset();
            }
        } catch (IOException e) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "Exception loading inDecodeBounds=" + options.inJustDecodeBounds
                        + " sample=" + options.inSampleSize, e);
            }
        }

        return result;
    }

    /**
     * 设置复用的bitmap空间，通过这种方式来实现内存的优化
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private static void setInBitmap(BitmapFactory.Options options, Bitmap recycled) {
        if (Build.VERSION_CODES.HONEYCOMB <= Build.VERSION.SDK_INT) {
            options.inBitmap = recycled;
        }
    }

    /**
     * 获得解析前的默认配置
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private static synchronized BitmapFactory.Options getDefaultOptions() {
        BitmapFactory.Options decodeBitmapOptions;
        //尝试复用之前保存的默认配置
        synchronized (OPTIONS_QUEUE) {
            decodeBitmapOptions = OPTIONS_QUEUE.poll();
        }
        if (decodeBitmapOptions == null) {
            decodeBitmapOptions = new BitmapFactory.Options();
            resetOptions(decodeBitmapOptions);//新建一个默认配置，并且缓存之
        }

        return decodeBitmapOptions;
    }

    /**
     * 将当前配置变为默认配置，并且缓存一份默认配置
     */
    private static void releaseOptions(BitmapFactory.Options decodeBitmapOptions) {
        resetOptions(decodeBitmapOptions);
        synchronized (OPTIONS_QUEUE) {
            OPTIONS_QUEUE.offer(decodeBitmapOptions);//添加到队列中，后续尝试复用默认配置
        }
    }

    /**
     * 设置默认配置
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private static void resetOptions(BitmapFactory.Options decodeBitmapOptions) {
        decodeBitmapOptions.inTempStorage = null;
        decodeBitmapOptions.inDither = false;
        decodeBitmapOptions.inScaled = false;
        decodeBitmapOptions.inSampleSize = 1;
        decodeBitmapOptions.inPreferredConfig = null;
        decodeBitmapOptions.inJustDecodeBounds = false;
        decodeBitmapOptions.outWidth = 0;
        decodeBitmapOptions.outHeight = 0;
        decodeBitmapOptions.outMimeType = null;
        //实际上就这里注意一下
        if (Build.VERSION_CODES.HONEYCOMB <= Build.VERSION.SDK_INT)  {
            decodeBitmapOptions.inBitmap = null;
            decodeBitmapOptions.inMutable = true;//这个决定了当前Bitmap可以复用，否则BitmapPool就没有意义了
        }
    }
}
