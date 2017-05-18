package com.bumptech.glide.load.engine;

import android.util.Log;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.executor.Prioritized;
import com.bumptech.glide.request.ResourceCallback;

/**
 * A runnable class responsible for using an {@link com.bumptech.glide.load.engine.DecodeJob} to decode resources on a
 * background thread in two stages.
 * 任务的runnable，用于在子线程中处理硬盘和网络等操作
 * 实际上主要的任务都是委托DecodeJob进行的
 *
 * <p>
 *     In the first stage, this class attempts to decode a resource
 *     from cache, first using transformed data and then using source data. If no resource can be decoded from cache,
 *     this class then requests to be posted again. During the second stage this class then attempts to use the
 *     {@link com.bumptech.glide.load.engine.DecodeJob} to decode data directly from the original source.
 * </p>
 *
 * <p>
 *     Using two stages with a re-post in between allows us to run fast disk cache decodes on one thread and slow source
 *     fetches on a second pool so that loads for local data are never blocked waiting for loads for remote data to
 *     complete.
 * </p>
 */
class EngineRunnable implements Runnable, Prioritized {
    private static final String TAG = "EngineRunnable";

    private final Priority priority;
    //这个默认是EngineJob
    private final EngineRunnableManager manager;
    private final DecodeJob<?, ?, ?> decodeJob;

    private Stage stage;

    private volatile boolean isCancelled;

    public EngineRunnable(EngineRunnableManager manager, DecodeJob<?, ?, ?> decodeJob, Priority priority) {
        this.manager = manager;
        this.decodeJob = decodeJob;
        this.stage = Stage.CACHE;//默认状态为CACHE，即处理硬盘相关先
        this.priority = priority;
    }

    /**
     * 取消当前加载任务
     */
    public void cancel() {
        //标记当前任务取消
        isCancelled = true;
        //取消decodeJob可能进行中的任务
        decodeJob.cancel();
    }

    @Override
    public void run() {
        if (isCancelled) {
            return;//当前任务已经取消
        }

        Exception exception = null;
        Resource<?> resource = null;
        try {
            //尝试获取资源
            resource = decode();
        } catch (OutOfMemoryError e) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Out Of Memory Error decoding", e);
            }
            exception = new ErrorWrappingGlideException(e);
        } catch (Exception e) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Exception decoding", e);
            }
            exception = e;
        }

        //当前任务已经取消，但是资源加载已经完成，此时可以直接释放资源
        if (isCancelled) {
            if (resource != null) {
                resource.recycle();
            }
            return;
        }
        //处理结果
        //注意一下这里onLoadFailed的处理，这里包括硬盘读取失败和网络等读取失败的操作
        if (resource == null) {
            onLoadFailed(exception);
        } else {
            onLoadComplete(resource);
        }
    }

    /**
     * 当前状态是否为从硬盘缓存中读取图片
     * @return true是
     */
    private boolean isDecodingFromCache() {
        return stage == Stage.CACHE;
    }

    private void onLoadComplete(Resource resource) {
        //首先回调到EngineJob中
        manager.onResourceReady(resource);
    }

    /**
     *  回调资源加载失败
     *  如果当前仅仅是硬盘获取资源失败，那么要通过EngineJob重新开启线程执行从网络等方向去获取资源
     *  否则可以直接回调失败
     */
    private void onLoadFailed(Exception e) {
        //当前资源已经加载失败
        if (isDecodingFromCache()) {//如果是从硬盘缓存中加载失败
            stage = Stage.SOURCE;//修改状态为网络等资源获取
            //通过EngineJob中的资源线程池重新执行，此时状态改变，会通过DecodeJob来从网络等资源去解析资源
            manager.submitForSource(this);
        } else {//如果从硬盘缓存和网络等资源上解析资源都出现异常，回调EngineJob中的异常处理
            manager.onException(e);
        }
    }

    /**
     * 实际执行解析图片的地方
     * 其实就是根据当前所处状态决定操作
     */
    private Resource<?> decode() throws Exception {
        if (isDecodingFromCache()) {
            return decodeFromCache();
        } else {
            return decodeFromSource();
        }
    }

    /**
     * 委托DecodeJob尝试从硬盘缓存中获取图片
     * 可能获得原图或者压缩处理过的图片
     */
    private Resource<?> decodeFromCache() throws Exception {
        Resource<?> result = null;
        try {
            //通过decodeJob从硬盘缓存中读取已经进行transform和压缩处理过的资源
            result = decodeJob.decodeResultFromCache();
        } catch (Exception e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Exception decoding result from cache: " + e);
            }
        }
        //从硬盘缓存中读取transform后的资源失败
        if (result == null) {
            //从硬盘缓存中读原始资源（没有transfrom和压缩），理解为原图即可
            result = decodeJob.decodeSourceFromCache();
        }
        return result;
    }

    /**
     * 委托DecodeJob从网络等途径上获取资源
     */
    private Resource<?> decodeFromSource() throws Exception {
        return decodeJob.decodeFromSource();
    }

    @Override
    public int getPriority() {
        return priority.ordinal();
    }

    private enum Stage {
        /** Attempting to decode resource from cache. */
        CACHE,
        /** Attempting to decode resource from source data. */
        SOURCE
    }

    interface EngineRunnableManager extends ResourceCallback {
        void submitForSource(EngineRunnable runnable);
    }
}
