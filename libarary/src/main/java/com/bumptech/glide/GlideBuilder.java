package com.bumptech.glide;

import android.content.Context;
import android.os.Build;

import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.Engine;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPoolAdapter;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.load.engine.cache.MemoryCache;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.engine.executor.FifoPriorityThreadPoolExecutor;

import java.util.concurrent.ExecutorService;

/**
 * A builder class for setting default structural classes for Glide to use.
 * Glide通过工厂模式创建，内部需要配置一些基础参数
 */
public class GlideBuilder {
    //在这里默认都为Application的Context
    private final Context context;
    //运行引擎
    private Engine engine;
    //Bitmap保存和重复使用的循环池
    private BitmapPool bitmapPool;
    //内存缓存
    private MemoryCache memoryCache;
    //在Engine中实际工作执行的线程池
    private ExecutorService sourceService;
    //在Engine中工作与硬盘缓存相关的线程池
    private ExecutorService diskCacheService;
    //解析格式，其实就是ARGB8888、RGB565这种Bitmap存储格式
    private DecodeFormat decodeFormat;
    //硬盘缓存的工厂，用于产出不同的硬盘缓存
    private DiskCache.Factory diskCacheFactory;

    public GlideBuilder(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Sets the {@link com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool} implementation to use to store and
     * retrieve reused {@link android.graphics.Bitmap}s.
     *
     * @param bitmapPool The pool to use.
     * @return This builder.
     */
    public GlideBuilder setBitmapPool(BitmapPool bitmapPool) {
        this.bitmapPool = bitmapPool;
        return this;
    }

    /**
     * Sets the {@link com.bumptech.glide.load.engine.cache.MemoryCache} implementation to store
     * {@link com.bumptech.glide.load.engine.Resource}s that are not currently in use.
     *
     * @param memoryCache  The cache to use.
     * @return This builder.
     */
    public GlideBuilder setMemoryCache(MemoryCache memoryCache) {
        this.memoryCache = memoryCache;
        return this;
    }

    /**
     * Sets the {@link com.bumptech.glide.load.engine.cache.DiskCache} implementation to use to store
     * {@link com.bumptech.glide.load.engine.Resource} data and thumbnails.
     *
     * @deprecated Creating a disk cache directory on the main thread causes strict mode violations, use
     * {@link #setDiskCache(com.bumptech.glide.load.engine.cache.DiskCache.Factory)} instead. Scheduled to be removed
     * in Glide 4.0.
     * @param diskCache The disk cache to use.
     * @return This builder.
     */
    @Deprecated
    public GlideBuilder setDiskCache(final DiskCache diskCache) {
        return setDiskCache(new DiskCache.Factory() {
            @Override
            public DiskCache build() {
                return diskCache;
            }
        });
    }

    /**
     * Sets the {@link com.bumptech.glide.load.engine.cache.DiskCache.Factory} implementation to use to construct
     * the {@link com.bumptech.glide.load.engine.cache.DiskCache} to use to store
     * {@link com.bumptech.glide.load.engine.Resource} data on disk.
     *
     * @param diskCacheFactory The disk cche factory to use.
     * @return This builder.
     */
    public GlideBuilder setDiskCache(DiskCache.Factory diskCacheFactory) {
        this.diskCacheFactory = diskCacheFactory;
        return this;
    }

    /**
     * Sets the {@link java.util.concurrent.ExecutorService} implementation to use when retrieving
     * {@link com.bumptech.glide.load.engine.Resource}s that are not already in the cache.
     *
     * <p>
     *     Any implementation must order requests based on their {@link com.bumptech.glide.Priority} for thumbnail
     *     requests to work properly.
     * </p>
     *
     * @see #setDiskCacheService(java.util.concurrent.ExecutorService)
     * @see com.bumptech.glide.load.engine.executor.FifoPriorityThreadPoolExecutor
     *
     * @param service The ExecutorService to use.
     * @return This builder.
     */
    public GlideBuilder setResizeService(ExecutorService service) {
        this.sourceService = service;
        return this;
    }

    /**
     * Sets the {@link java.util.concurrent.ExecutorService} implementation to use when retrieving
     * {@link com.bumptech.glide.load.engine.Resource}s that are currently in cache.
     *
     * <p>
     *     Any implementation must order requests based on their {@link com.bumptech.glide.Priority} for thumbnail
     *     requests to work properly.
     * </p>
     *
     * @see #setResizeService(java.util.concurrent.ExecutorService)
     * @see com.bumptech.glide.load.engine.executor.FifoPriorityThreadPoolExecutor
     *
     * @param service The ExecutorService to use.
     * @return This builder.
     */
    public GlideBuilder setDiskCacheService(ExecutorService service) {
        this.diskCacheService = service;
        return this;
    }

    /**
     * Sets the {@link com.bumptech.glide.load.DecodeFormat} that will be the default format for all the default
     * decoders that can change the {@link android.graphics.Bitmap.Config} of the {@link android.graphics.Bitmap}s they
     * decode.
     *
     * <p>
     *     Decode format is always a suggestion, not a requirement. See {@link com.bumptech.glide.load.DecodeFormat} for
     *     more details.
     * </p>
     *
     * <p>
     *     If you instantiate and use a custom decoder, it will use
     *     {@link com.bumptech.glide.load.DecodeFormat#DEFAULT} as its default.
     * </p>
     *
     * <p>
     *     Calls to this method are ignored on KitKat and Lollipop. See #301.
     * </p>
     *
     * @param decodeFormat The format to use.
     * @return This builder.
     */
    public GlideBuilder setDecodeFormat(DecodeFormat decodeFormat) {
        this.decodeFormat = decodeFormat;
        return this;
    }

    // For testing.
    GlideBuilder setEngine(Engine engine) {
        this.engine = engine;
        return this;
    }

    /**
     * 从工厂中获取对应Glide，这里主要看默认值的设置
     * @return 新的Glide
     */
    Glide createGlide() {
        if (sourceService == null) {
            //最少1个核心线程，最多当前硬件支持的多线程数
            final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            //基于PriorityBlockingQueue的优先级执行线程池
            sourceService = new FifoPriorityThreadPoolExecutor(cores);
        }
        if (diskCacheService == null) {
            //默认基于PriorityBlockingQueue的优先级执行的单线程池
            diskCacheService = new FifoPriorityThreadPoolExecutor(1);
        }
        //Bitmap循环池和内存缓存大小的计算者，大小比例为1:2
        MemorySizeCalculator calculator = new MemorySizeCalculator(context);
        if (bitmapPool == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                int size = calculator.getBitmapPoolSize();
                //构建Bitmap循环池
                bitmapPool = new LruBitmapPool(size);
            } else {
                bitmapPool = new BitmapPoolAdapter();
            }
        }

        if (memoryCache == null) {
            //构建一个基于LruCache的内存缓存，内部同步了TrimMemory生命周期，用于在低内存模式下释放内存
            memoryCache = new LruResourceCache(calculator.getMemoryCacheSize());
        }

        if (diskCacheFactory == null) {
            //默认是基于DiskLruCache的硬盘缓存
            diskCacheFactory = new InternalCacheDiskCacheFactory(context);
        }

        if (engine == null) {
            engine = new Engine(memoryCache, diskCacheFactory, diskCacheService, sourceService);
        }

        if (decodeFormat == null) {//默认为RGB565
            decodeFormat = DecodeFormat.DEFAULT;
        }

        return new Glide(engine, memoryCache, bitmapPool, context, decodeFormat);
    }
}