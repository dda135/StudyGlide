package com.bumptech.glide.load.engine;

import android.util.Log;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.bumptech.glide.provider.DataLoadProvider;
import com.bumptech.glide.util.LogTime;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A class responsible for decoding resources either from cached data or from the original source and applying
 * transformations and transcodes.
 * 用于处理资源的获取、解析、转换和硬盘缓存等操作的类
 *
 * @param <A> The type of the source data the resource can be decoded from. 需要解析的资源，比方说InputStream
 * @param <T> The type of resource that will be decoded. 需要解析后的资源类型，比方说Bitmap
 * @param <Z> The type of resource that will be transcoded from the decoded and transformed resource.
 *           资源进过解析和转换之后的最终类型，比方说GlideBitmapDrawable
 */
class DecodeJob<A, T, Z> {
    private static final String TAG = "DecodeJob";
    private static final FileOpener DEFAULT_FILE_OPENER = new FileOpener();

    private final EngineKey resultKey;
    private final int width;
    private final int height;
    private final DataFetcher<A> fetcher;
    private final DataLoadProvider<A, T> loadProvider;
    private final Transformation<T> transformation;
    private final ResourceTranscoder<T, Z> transcoder;
    //硬盘缓存的提供者/工厂
    private final DiskCacheProvider diskCacheProvider;
    //硬盘缓存策略
    private final DiskCacheStrategy diskCacheStrategy;
    private final Priority priority;
    private final FileOpener fileOpener;

    private volatile boolean isCancelled;

    public DecodeJob(EngineKey resultKey, int width, int height, DataFetcher<A> fetcher,
            DataLoadProvider<A, T> loadProvider, Transformation<T> transformation, ResourceTranscoder<T, Z> transcoder,
            DiskCacheProvider diskCacheProvider, DiskCacheStrategy diskCacheStrategy, Priority priority) {
        this(resultKey, width, height, fetcher, loadProvider, transformation, transcoder, diskCacheProvider,
                diskCacheStrategy, priority, DEFAULT_FILE_OPENER);
    }

    // Visible for testing.
    DecodeJob(EngineKey resultKey, int width, int height, DataFetcher<A> fetcher,
            DataLoadProvider<A, T> loadProvider, Transformation<T> transformation, ResourceTranscoder<T, Z> transcoder,
            DiskCacheProvider diskCacheProvider, DiskCacheStrategy diskCacheStrategy, Priority priority, FileOpener
            fileOpener) {
        this.resultKey = resultKey;
        this.width = width;
        this.height = height;
        this.fetcher = fetcher;
        this.loadProvider = loadProvider;
        this.transformation = transformation;
        this.transcoder = transcoder;
        this.diskCacheProvider = diskCacheProvider;
        this.diskCacheStrategy = diskCacheStrategy;
        this.priority = priority;
        this.fileOpener = fileOpener;
    }

    /**
     * Returns a transcoded resource decoded from transformed resource data in the disk cache, or null if no such
     * resource exists.
     * 返回一个来源于硬盘缓存中已经经过压缩、transform操作的资源
     * 比方说Bitmap，此时也是最适应载体的bitmap
     */
    public Resource<Z> decodeResultFromCache() throws Exception {
        if (!diskCacheStrategy.cacheResult()) {//不允许从硬盘缓存中读取经过transcode之后的资源
            return null;
        }

        long startTime = LogTime.getLogTime();
        //尝试硬盘缓存中读取经过decoder的资源
        Resource<T> transformed = loadFromCache(resultKey);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Decoded transformed from cache", startTime);
        }
        startTime = LogTime.getLogTime();
        //尝试将当前资源变为target可以直接使用的资源
        //比方说bitmap封装成GlideBitmapDrawable
        Resource<Z> result = transcode(transformed);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Transcoded transformed from cache", startTime);
        }
        return result;
    }

    /**
     * Returns a transformed and transcoded resource decoded from source data in the disk cache, or null if no such
     * resource exists.
     * 从硬盘中读取一个原始资源，一般来说就是从网络上读取的输入流直接扔入文件里面
     * null的话表示资源不存在
     */
    public Resource<Z> decodeSourceFromCache() throws Exception {
        if (!diskCacheStrategy.cacheSource()) {//不允许从硬盘缓存中读取原始资源
            return null;
        }

        long startTime = LogTime.getLogTime();
        //注意这个key和decodeResult的区别
        Resource<T> decoded = loadFromCache(resultKey.getOriginalKey());
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Decoded source from cache", startTime);
        }
        //转换为GlideBitmapDrawable等载体可装载的对象返回
        return transformEncodeAndTranscode(decoded);
    }

    /**
     * Returns a transformed and transcoded resource decoded from source data, or null if no source data could be
     * obtained or no resource could be decoded.
     * 从网络等途径获取经过压缩、transform、transcode的资源
     * <p>
     *     Depending on the {@link com.bumptech.glide.load.engine.DiskCacheStrategy} used, source data is either decoded
     *     directly or first written to the disk cache and then decoded from the disk cache.
     * </p>
     *
     * @throws Exception
     */
    public Resource<Z> decodeFromSource() throws Exception {
        Resource<T> decoded = decodeSource();//获得压缩过的资源
        return transformEncodeAndTranscode(decoded);//进行transform和transcode操作并返回
    }

    /**
     * 尝试取消网络等途径请求
     */
    public void cancel() {
        isCancelled = true;
        fetcher.cancel();
    }

    /**
     * 在得到原始资源之后
     * 要对数据进行转换，并且重新写到硬盘缓存中，以用于后续可以直接击中result，不需要再次进行转换操作
     * 最后进行transcode操作，转换为类似GlideBitmapDrawable之类的Target可装载对象
     */
    private Resource<Z> transformEncodeAndTranscode(Resource<T> decoded) {
        long startTime = LogTime.getLogTime();
        //这里将已经压缩过的数据再次转换
        Resource<T> transformed = transform(decoded);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Transformed resource from source", startTime);
        }
        //来到这里意味着transform之后的资源没有在硬盘缓存中命中
        //于是重新将进过压缩和transform的数据写回硬盘缓存中，注意key的不同
        writeTransformedToCache(transformed);

        startTime = LogTime.getLogTime();
        //进行transcode操作
        Resource<Z> result = transcode(transformed);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Transcoded transformed from source", startTime);
        }
        return result;
    }

    /**
     * 将经过压缩和transform的资源写入硬盘缓存当中
     */
    private void writeTransformedToCache(Resource<T> transformed) {
        if (transformed == null || !diskCacheStrategy.cacheResult()) {//当前是否允许缓存结果
            return;
        }
        long startTime = LogTime.getLogTime();
        //实际上Encoder就是决定图片压缩质量和透明度（PNG和JPG）
        SourceWriter<Resource<T>> writer = new SourceWriter<Resource<T>>(loadProvider.getEncoder(), transformed);
        diskCacheProvider.getDiskCache().put(resultKey, writer);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Wrote transformed from source to cache", startTime);
        }
    }

    /**
     * 通过Fetcher获取资源，实际上一般来说就是网络请求之类的
     */
    private Resource<T> decodeSource() throws Exception {
        Resource<T> decoded = null;
        try {
            long startTime = LogTime.getLogTime();
            //这里一般来讲是InputStream
            final A data = fetcher.loadData(priority);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Fetched data", startTime);
            }
            if (isCancelled) {
                return null;
            }
            //尝试将原始资源写入硬盘缓存中，然后再通过压缩拿出资源
            decoded = decodeFromSourceData(data);
        } finally {
            fetcher.cleanup();
        }
        return decoded;
    }

    /**
     * 当从Fetcher中获取数据完成后，解析数据并尝试存储到硬盘
     */
    private Resource<T> decodeFromSourceData(A data) throws IOException {
        final Resource<T> decoded;
        if (diskCacheStrategy.cacheSource()) {//允许存储原始资源
            //先存储原始资源，然后通过decode再从硬盘中取出并压缩
            decoded = cacheAndDecodeSourceData(data);
        } else {
            //不允许存储原始资源，直接对当前资源进行decode处理
            long startTime = LogTime.getLogTime();
            decoded = loadProvider.getSourceDecoder().decode(data, width, height);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Decoded from source", startTime);
            }
        }
        return decoded;
    }

    /**
     * 从硬盘缓存中根据key获取指定的资源，注意在取出的过程中重新进行了decode操作
     * 因为对于同一个资源，可能载体大小之类的发生了变化，这时候也是需要decode的
     * @param key 硬盘缓存中的key值，如果是OriginalKey则为原始资源，否则为处理过的资源
     * @return 资源，null表示获取失败
     */
    private Resource<T> loadFromCache(Key key) throws IOException {
        //1.通过工厂获得硬盘缓存实现类
        //2.通过实现类和key获得指定文件
        File cacheFile = diskCacheProvider.getDiskCache().get(key);
        if (cacheFile == null) {//本身就没有硬盘缓存的存储
            return null;
        }

        Resource<T> result = null;
        try {
            //通过在Glide当中注册的DataLoadProvider
            //集合loadProvider中的泛型获得指定的CacheDecoder
            //这里实际上就是先从文件读取输入流，然后再通过decoder（压缩之类）处理输入流得到当前资源result，比方说Bitmap
            result = loadProvider.getCacheDecoder().decode(cacheFile, width, height);
        } finally {
            if (result == null) {//解析异常，但是却有这个key，说明这个key已经没有意义，可以从硬盘缓存中移除
                diskCacheProvider.getDiskCache().delete(key);
            }
        }
        return result;
    }

    /**
     * 当从网络等途径获取资源之后。尝试将原始资源存入硬盘当中
     * 并且通过decoder读取资源并返回
     */
    private Resource<T> cacheAndDecodeSourceData(A data) throws IOException {
        long startTime = LogTime.getLogTime();
        //将原始资源写入硬盘缓存当中
        SourceWriter<A> writer = new SourceWriter<A>(loadProvider.getSourceEncoder(), data);
        diskCacheProvider.getDiskCache().put(resultKey.getOriginalKey(), writer);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Wrote source to cache", startTime);
        }

        startTime = LogTime.getLogTime();
        Resource<T> result = loadFromCache(resultKey.getOriginalKey());
        if (Log.isLoggable(TAG, Log.VERBOSE) && result != null) {
            logWithTimeAndKey("Decoded source from cache", startTime);
        }
        return result;
    }

    /**
     * 这里实际上的处理者在Glide有注册
     * 基本上就是fitCenter、centerCrop等转换操作
     */
    private Resource<T> transform(Resource<T> decoded) {
        if (decoded == null) {
            return null;
        }

        Resource<T> transformed = transformation.transform(decoded, width, height);
        if (!decoded.equals(transformed)) {
            decoded.recycle();
        }
        return transformed;
    }

    private Resource<Z> transcode(Resource<T> transformed) {
        if (transformed == null) {
            return null;
        }
        return transcoder.transcode(transformed);
    }

    private void logWithTimeAndKey(String message, long startTime) {
        Log.v(TAG, message + " in " + LogTime.getElapsedMillis(startTime) + ", key: " + resultKey);
    }

    class SourceWriter<DataType> implements DiskCache.Writer {

        private final Encoder<DataType> encoder;
        private final DataType data;

        public SourceWriter(Encoder<DataType> encoder, DataType data) {
            this.encoder = encoder;
            this.data = data;
        }

        @Override
        public boolean write(File file) {
            boolean success = false;
            OutputStream os = null;
            try {
                os = fileOpener.open(file);
                success = encoder.encode(data, os);
            } catch (FileNotFoundException e) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Failed to find file to write to disk cache", e);
                }
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (IOException e) {
                        // Do nothing.
                    }
                }
            }
            return success;
        }
    }

    interface DiskCacheProvider {
        DiskCache getDiskCache();
    }

    static class FileOpener {
        public OutputStream open(File file) throws FileNotFoundException {
            return new BufferedOutputStream(new FileOutputStream(file));
        }
    }
}
