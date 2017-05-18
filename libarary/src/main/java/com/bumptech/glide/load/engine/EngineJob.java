package com.bumptech.glide.load.engine;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.Util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * A class that manages a load by adding and removing callbacks for for the load and notifying callbacks when the
 * load completes.
 * 用于管理线程调度子线程的加载任务，并且管理（Request）回调的添加、移除、回调时机（获取失败或者成功）的类
 */
class EngineJob implements EngineRunnable.EngineRunnableManager {
    private static final EngineResourceFactory DEFAULT_FACTORY = new EngineResourceFactory();
    private static final Handler MAIN_THREAD_HANDLER = new Handler(Looper.getMainLooper(), new MainThreadCallback());

    private static final int MSG_COMPLETE = 1;
    private static final int MSG_EXCEPTION = 2;

    private final List<ResourceCallback> cbs = new ArrayList<ResourceCallback>();
    private final EngineResourceFactory engineResourceFactory;
    //稍微注意一下这里的listener默认是Engine
    private final EngineJobListener listener;
    private final Key key;
    private final ExecutorService diskCacheService;
    private final ExecutorService sourceService;
    private final boolean isCacheable;

    private boolean isCancelled;
    // Either resource or exception (particularly exception) may be returned to us null, so use booleans to track if
    // we've received them instead of relying on them to be non-null. See issue #180.
    private Resource<?> resource;
    //当前任务是否已经加载到资源
    private boolean hasResource;
    private Exception exception;
    //加载中是否出现异常
    private boolean hasException;
    // A set of callbacks that are removed while we're notifying other callbacks of a change in status.
    private Set<ResourceCallback> ignoredCallbacks;
    private EngineRunnable engineRunnable;
    private EngineResource<?> engineResource;

    private volatile Future<?> future;

    public EngineJob(Key key, ExecutorService diskCacheService, ExecutorService sourceService, boolean isCacheable,
            EngineJobListener listener) {
        this(key, diskCacheService, sourceService, isCacheable, listener, DEFAULT_FACTORY);
    }

    public EngineJob(Key key, ExecutorService diskCacheService, ExecutorService sourceService, boolean isCacheable,
            EngineJobListener listener, EngineResourceFactory engineResourceFactory) {
        this.key = key;
        this.diskCacheService = diskCacheService;
        this.sourceService = sourceService;
        this.isCacheable = isCacheable;
        this.listener = listener;
        this.engineResourceFactory = engineResourceFactory;
    }

    /**
     * 通过硬盘缓存的线程池运行任务
     * 注意diskCacheService默认是单线程池，这个也许会有一定的阻塞
     */
    public void start(EngineRunnable engineRunnable) {
        this.engineRunnable = engineRunnable;
        future = diskCacheService.submit(engineRunnable);
    }

    /**
     * 通过资源加载的线程池运行任务（比方说网络请求之类的）
     * 这个默认是当前核心线程数的线程池
     */
    @Override
    public void submitForSource(EngineRunnable runnable) {
        future = sourceService.submit(runnable);
    }

    /**
     * 如果一个key的请求在当前已经有任务进行当中
     * 将会直接关联回调
     * @param cb 实际上就是Request本身
     */
    public void addCallback(ResourceCallback cb) {
        Util.assertMainThread();
        if (hasResource) {
            //当前任务已经加载资源完成，回调资源准备完毕
            cb.onResourceReady(engineResource);
        } else if (hasException) {
            //当前任务加载中已经出现异常，回调异常失败处理
            cb.onException(exception);
        } else {//否则直接关联，等待任务的回调
            cbs.add(cb);
        }
    }

    public void removeCallback(ResourceCallback cb) {
        Util.assertMainThread();
        if (hasResource || hasException) {
            addIgnoredCallback(cb);
        } else {
            cbs.remove(cb);
            if (cbs.isEmpty()) {
                cancel();
            }
        }
    }

    // We cannot remove callbacks while notifying our list of callbacks directly because doing so would cause a
    // ConcurrentModificationException. However, we need to obey the cancellation request such that if notifying a
    // callback early in the callbacks list cancels a callback later in the request list, the cancellation for the later
    // request is still obeyed. Using a set of ignored callbacks allows us to avoid the exception while still meeting
    // the requirement.
    private void addIgnoredCallback(ResourceCallback cb) {
        if (ignoredCallbacks == null) {
            ignoredCallbacks = new HashSet<ResourceCallback>();
        }
        ignoredCallbacks.add(cb);
    }

    private boolean isInIgnoredCallbacks(ResourceCallback cb) {
        return ignoredCallbacks != null && ignoredCallbacks.contains(cb);
    }

    // Exposed for testing.
    void cancel() {
        if (hasException || hasResource || isCancelled) {
            return;
        }
        engineRunnable.cancel();
        Future currentFuture = future;
        if (currentFuture != null) {
            currentFuture.cancel(true);
        }
        isCancelled = true;
        listener.onEngineJobCancelled(this, key);
    }

    // Exposed for testing.
    boolean isCancelled() {
        return isCancelled;
    }

    /**
     * 资源最终加载完成会回调到这里
     * 之后进入主线程中处理结果
     */
    @Override
    public void onResourceReady(final Resource<?> resource) {
        this.resource = resource;
        MAIN_THREAD_HANDLER.obtainMessage(MSG_COMPLETE, this).sendToTarget();
    }

    /**
     * 加载资源成功之后在主线程的回调处理
     */
    private void handleResultOnMainThread() {
        if (isCancelled) {//当前任务被取消
            //此时的资源没有意义，直接回收，这里没有回到Engine的resourceRecycler中进行回收，是因为当前资源是全新加载，不存在被使用之类的问题
            resource.recycle();
            return;
        } else if (cbs.isEmpty()) {
            throw new IllegalStateException("Received a resource without any callbacks to notify");
        }
        //虽然有工厂，但是这里实际上就是一个new
        engineResource = engineResourceFactory.build(resource, isCacheable);
        hasResource = true;

        // Hold on to resource for duration of request so we don't recycle it in the middle of notifying if it
        // synchronously released by one of the callbacks.
        engineResource.acquire();//这里主要是防止被回收
        //首先回调到Engine中
        listener.onEngineJobComplete(key, engineResource);

        for (ResourceCallback cb : cbs) {
            if (!isInIgnoredCallbacks(cb)) {//这里是回调到Request中，这里就是设置展示之类的操作
                engineResource.acquire();
                cb.onResourceReady(engineResource);
            }
        }
        // Our request is complete, so we can release the resource.
        engineResource.release();//这里对应之前放置被回收，如果该资源有效，那么会被request持有，则不用担心被回收
    }

    @Override
    public void onException(final Exception e) {
        this.exception = e;
        //在主线程回调异常处理handleExceptionOnMainThread
        MAIN_THREAD_HANDLER.obtainMessage(MSG_EXCEPTION, this).sendToTarget();
    }

    /**
     * 资源加载完毕但是出现异常，这里在主线程处理异常
     */
    private void handleExceptionOnMainThread() {
        if (isCancelled) {//当前请求已经取消，不需要回调
            return;
        } else if (cbs.isEmpty()) {
            throw new IllegalStateException("Received an exception without any callbacks to notify");
        }
        hasException = true;//标记当前任务出现异常

        listener.onEngineJobComplete(key, null);//回调任务完成，但是失败所以资源为空

        for (ResourceCallback cb : cbs) {
            if (!isInIgnoredCallbacks(cb)) {
                cb.onException(exception);//回调Request的失败处理
            }
        }
    }

    // Visible for testing.
    static class EngineResourceFactory {
        public <R> EngineResource<R> build(Resource<R> resource, boolean isMemoryCacheable) {
            return new EngineResource<R>(resource, isMemoryCacheable);
        }
    }

    private static class MainThreadCallback implements Handler.Callback {

        @Override
        public boolean handleMessage(Message message) {
            if (MSG_COMPLETE == message.what || MSG_EXCEPTION == message.what) {
                EngineJob job = (EngineJob) message.obj;
                if (MSG_COMPLETE == message.what) {
                    job.handleResultOnMainThread();
                } else {
                    job.handleExceptionOnMainThread();
                }
                return true;
            }

            return false;
        }
    }
}
