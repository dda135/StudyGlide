package com.bumptech.glide.load.engine;

import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter;
import com.bumptech.glide.load.engine.cache.MemoryCache;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.bumptech.glide.provider.DataLoadProvider;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Responsible for starting loads and managing active and cached resources.
 * 负责开始请求并且在主线程处理内存缓存相关的逻辑
 * 因为Glide持有，而Glide是单例，所以Engine实际上是单例
 */
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {
    private static final String TAG = "Engine";
    //当前工作中的key和任务集合
    private final Map<Key, EngineJob> jobs;
    private final EngineKeyFactory keyFactory;
    private final MemoryCache cache;
    //请求任务工厂
    private final EngineJobFactory engineJobFactory;
    //当一个请求可以缓存的时候，加载完成和从内存缓存中拿出的时候会尝试保存一个弱应用
    //再次读取后可能命中该缓存
    //同时这个可以有效避免低内存的时候当内存回收的时候导致正在使用的资源被回收
    private final Map<Key, WeakReference<EngineResource<?>>> activeResources;
    private final ResourceRecycler resourceRecycler;
    private final LazyDiskCacheProvider diskCacheProvider;

    // Lazily instantiate to avoid exceptions if Glide is initialized on a background thread. See #295.
    // 引用队列，用于记录activeResources里的弱应用
    private ReferenceQueue<EngineResource<?>> resourceReferenceQueue;

    /**
     * Allows a request to indicate it no longer is interested in a given load.
     */
    public static class LoadStatus {
        private final EngineJob engineJob;
        private final ResourceCallback cb;

        public LoadStatus(ResourceCallback cb, EngineJob engineJob) {
            this.cb = cb;
            this.engineJob = engineJob;
        }

        public void cancel() {
            engineJob.removeCallback(cb);
        }
    }

    public Engine(MemoryCache memoryCache, DiskCache.Factory diskCacheFactory, ExecutorService diskCacheService,
            ExecutorService sourceService) {
        this(memoryCache, diskCacheFactory, diskCacheService, sourceService, null, null, null, null, null);
    }

    // Visible for testing.
    Engine(MemoryCache cache, DiskCache.Factory diskCacheFactory, ExecutorService diskCacheService,
            ExecutorService sourceService, Map<Key, EngineJob> jobs, EngineKeyFactory keyFactory,
            Map<Key, WeakReference<EngineResource<?>>> activeResources, EngineJobFactory engineJobFactory,
            ResourceRecycler resourceRecycler) {
        this.cache = cache;
        this.diskCacheProvider = new LazyDiskCacheProvider(diskCacheFactory);

        if (activeResources == null) {
            activeResources = new HashMap<Key, WeakReference<EngineResource<?>>>();
        }
        this.activeResources = activeResources;

        if (keyFactory == null) {
            keyFactory = new EngineKeyFactory();
        }
        this.keyFactory = keyFactory;

        if (jobs == null) {
            jobs = new HashMap<Key, EngineJob>();
        }
        this.jobs = jobs;

        if (engineJobFactory == null) {
            engineJobFactory = new EngineJobFactory(diskCacheService, sourceService, this);
        }
        this.engineJobFactory = engineJobFactory;

        if (resourceRecycler == null) {
            resourceRecycler = new ResourceRecycler();
        }
        this.resourceRecycler = resourceRecycler;
        //添加强内存缓存的资源移除回调，默认回调到Engine的onResourceRemoved处理
        cache.setResourceRemovedListener(this);
    }

    /**
     * Starts a load for the given arguments. Must be called on the main thread.
     *
     * <p>
     *     The flow for any request is as follows:
     *     <ul>
     *         <li>Check the memory cache and provide the cached resource if present</li>
     *         <li>Check the current set of actively used resources and return the active resource if present</li>
     *         <li>Check the current set of in progress loads and add the cb to the in progress load if present</li>
     *         <li>Start a new load</li>
     *     </ul>
     * </p>
     *
     * <p>
     *     Active resources are those that have been provided to at least one request and have not yet been released.
     *     Once all consumers of a resource have released that resource, the resource then goes to cache. If the
     *     resource is ever returned to a new consumer from cache, it is re-added to the active resources. If the
     *     resource is evicted from the cache, its resources are recycled and re-used if possible and the resource is
     *     discarded. There is no strict requirement that consumers release their resources so active resources are
     *     held weakly.
     * </p>
     *
     * @param signature A non-null unique key to be mixed into the cache key that identifies the version of the data to
     *                  be loaded.
     * @param width The target width in pixels of the desired resource.
     * @param height The target height in pixels of the desired resource.
     * @param fetcher The fetcher to use to retrieve data not in the disk cache.
     * @param loadProvider The load provider containing various encoders and decoders use to decode and encode data.
     * @param transformation The transformation to use to transform the decoded resource.
     * @param transcoder The transcoder to use to transcode the decoded and transformed resource.
     * @param priority The priority with which the request should run.
     * @param isMemoryCacheable True if the transcoded resource can be cached in memory.
     * @param diskCacheStrategy The strategy to use that determines what type of data, if any,
     *                          will be cached in the local disk cache.
     * @param cb The callback that will be called when the load completes.
     *
     * @param <T> The type of data the resource will be decoded from.
     * @param <Z> The type of the resource that will be decoded.
     * @param <R> The type of the resource that will be transcoded from the decoded resource.
     */
    public <T, Z, R> LoadStatus load(Key signature, int width, int height, DataFetcher<T> fetcher,
            DataLoadProvider<T, Z> loadProvider, Transformation<Z> transformation, ResourceTranscoder<Z, R> transcoder,
            Priority priority, boolean isMemoryCacheable, DiskCacheStrategy diskCacheStrategy, ResourceCallback cb) {
        //当一个Request中确定载体大小之后会过来尝试进行加载
        //检查当前是否为主线程
        Util.assertMainThread();
        long startTime = LogTime.getLogTime();

        final String id = fetcher.getId();
        //创建当前请求对应的key，这里虽然有工厂，但是实际上是直接new出来的
        EngineKey key = keyFactory.buildKey(id, signature, width, height, loadProvider.getCacheDecoder(),
                loadProvider.getSourceDecoder(), transformation, loadProvider.getEncoder(),
                transcoder, loadProvider.getSourceEncoder());

        //尝试从强内存缓存中获取数据
        EngineResource<?> cached = loadFromCache(key, isMemoryCacheable);
        if (cached != null) {//击中缓存
            cb.onResourceReady(cached);//注意这里回调的实际上在Request里面
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Loaded resource from cache", startTime, key);
            }
            return null;
        }
        //尝试从当前活动中的弱应用资源获取数据，这部分也是glide的内存缓存之一
        EngineResource<?> active = loadFromActiveResources(key, isMemoryCacheable);
        if (active != null) {
            cb.onResourceReady(active);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Loaded resource from active resources", startTime, key);
            }
            return null;
        }
        //先看看当前key是否已经有工作任务进行当中
        EngineJob current = jobs.get(key);
        if (current != null) {//不需要开始重复的任务
            //将当前回调关联到之前的任务，等待之前的任务完成之后进行回调就好
            current.addCallback(cb);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Added to existing load", startTime, key);
            }
            //注意到此时的任务不能认为是完成，返回一个状态对象
            return new LoadStatus(cb, current);
        }
        //通过工厂创建一个新的任务，这里实际上也是直接new的
        EngineJob engineJob = engineJobFactory.build(key, isMemoryCacheable);
        //构建一个解析处理器，用于处理硬盘和网络等操作
        DecodeJob<T, Z, R> decodeJob = new DecodeJob<T, Z, R>(key, width, height, fetcher, loadProvider, transformation,
                transcoder, diskCacheProvider, diskCacheStrategy, priority);
        //构建一个Runnable，用于在子线程中进行任务
        EngineRunnable runnable = new EngineRunnable(engineJob, decodeJob, priority);
        //标记当前任务
        jobs.put(key, engineJob);
        //设置Request的相关回调，主要是用于回调结果之类的到Request处理
        engineJob.addCallback(cb);
        //开始任务
        engineJob.start(runnable);

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Started new load", startTime, key);
        }
        return new LoadStatus(cb, engineJob);
    }

    private static void logWithTimeAndKey(String log, long startTime, Key key) {
        Log.v(TAG, log + " in " + LogTime.getElapsedMillis(startTime) + "ms, key: " + key);
    }

    /**
     * 尝试从活动中的资源获取，即弱的内存缓存中获取
     */
    private EngineResource<?> loadFromActiveResources(Key key, boolean isMemoryCacheable) {
        if (!isMemoryCacheable) {//当前不允许使用内存缓存
            return null;
        }

        EngineResource<?> active = null;
        WeakReference<EngineResource<?>> activeRef = activeResources.get(key);
        if (activeRef != null) {
            active = activeRef.get();
            if (active != null) {//击中缓存
                active.acquire();//标记获取数量+1
            } else {//因为弱应用，当前资源已经被回收，这个时候key已经没有意义，直接移除
                activeResources.remove(key);
            }
        }

        return active;
    }

    /**
     * 从内存缓存中读取，注意到不是长久保留的
     * @param key
     * @param isMemoryCacheable
     * @return
     */
    private EngineResource<?> loadFromCache(Key key, boolean isMemoryCacheable) {
        if (!isMemoryCacheable) {//当前不能使用内存缓存
            return null;
        }
        //当前可以使用内存缓存
        //尝试通过key获取内存缓存
        EngineResource<?> cached = getEngineResourceFromCache(key);
        if (cached != null) {//击中内存缓存
            cached.acquire();//这里标记了资源的获取次数
            //添加到活动资源缓存中
            activeResources.put(key, new ResourceWeakReference(key, cached, getReferenceQueue()));
        }
        return cached;
    }

    /**
     * 从强内存缓存中尝试获取资源
     */
    @SuppressWarnings("unchecked")
    private EngineResource<?> getEngineResourceFromCache(Key key) {
        //移除指定key的资源，并且获取
        //因为当前认为要将资源转为活动中资源，所以后续要转为弱引用的内存缓存
        Resource<?> cached = cache.remove(key);

        final EngineResource result;
        if (cached == null) {//没有击中内存缓存
            result = null;
        } else if (cached instanceof EngineResource) {
            // Save an object allocation if we've cached an EngineResource (the typical case).
            result = (EngineResource) cached;
        } else {
            result = new EngineResource(cached, true /*isCacheable*/);
        }
        return result;
    }

    public void release(Resource resource) {
        Util.assertMainThread();
        if (resource instanceof EngineResource) {
            ((EngineResource) resource).release();
        } else {
            throw new IllegalArgumentException("Cannot release anything but an EngineResource");
        }
    }

    /**
     * EngineJob在任务结束之后的回调
     * @param resource null表示异常回调，否则正常回调
     */
    @SuppressWarnings("unchecked")
    @Override
    public void onEngineJobComplete(Key key, EngineResource<?> resource) {
        Util.assertMainThread();
        // A null resource indicates that the load failed, usually due to an exception.
        if (resource != null) {//资源正常回调
            //添加resource回调，实际上这里就是根据资源引用情况回调onResourceReleased方法
            resource.setResourceListener(key, this);

            if (resource.isCacheable()) {//可以使用内存缓存的话
                //当前资源因为是刚刚加载完成的，认为是当前活动所需要的，所以通过弱应用的内存缓存进行保存即可
                activeResources.put(key, new ResourceWeakReference(key, resource, getReferenceQueue()));
            }
        }
        // TODO: should this check that the engine job is still current?
        jobs.remove(key);//任务完成，从当前运行集合中移除
    }

    /**
     * 当前EngineJob任务取消的时候会进行回调
     */
    @Override
    public void onEngineJobCancelled(EngineJob engineJob, Key key) {
        Util.assertMainThread();
        //判断当前任务是否在运行集合中
        EngineJob current = jobs.get(key);
        if (engineJob.equals(current)) {
            jobs.remove(key);//从当前运行集合中移除
        }
    }

    /**
     * 这里目前只有MemoryCache在清理资源的时候会进行回调
     */
    @Override
    public void onResourceRemoved(final Resource<?> resource) {
        Util.assertMainThread();
        //资源移除以后，尝试回收资源
        resourceRecycler.recycle(resource);
    }

    /**
     * 获取资源成功并且回调完onEngineJobComplete之后会给resource设置监听
     * 当resource的引用最终为0的时候，回调该方法用于处理资源缓存的转换和回收
     */
    @Override
    public void onResourceReleased(Key cacheKey, EngineResource resource) {
        Util.assertMainThread();
        activeResources.remove(cacheKey);//尝试从当前活动中资源缓存中移除
        if (resource.isCacheable()) {//当前资源在请求的时候允许使用内存缓存，存入到强内存缓存中，以便后期快速重用
            cache.put(cacheKey, resource);
        } else {//否则当前资源已经没有使用意义，尝试回收它
            resourceRecycler.recycle(resource);
        }
    }

    public void clearDiskCache() {
        diskCacheProvider.getDiskCache().clear();
    }

    private ReferenceQueue<EngineResource<?>> getReferenceQueue() {
        if (resourceReferenceQueue == null) {
            resourceReferenceQueue = new ReferenceQueue<EngineResource<?>>();
            MessageQueue queue = Looper.myQueue();
            queue.addIdleHandler(new RefQueueIdleHandler(activeResources, resourceReferenceQueue));
        }
        return resourceReferenceQueue;
    }

    private static class LazyDiskCacheProvider implements DecodeJob.DiskCacheProvider {

        private final DiskCache.Factory factory;
        public LazyDiskCacheProvider(DiskCache.Factory factory) {
            this.factory = factory;
        }

        private volatile DiskCache diskCache;

        @Override
        public DiskCache getDiskCache() {
            if (diskCache == null) {
                synchronized (this) {
                    if (diskCache == null) {
                        diskCache = factory.build();
                    }
                    if (diskCache == null) {
                        diskCache = new DiskCacheAdapter();
                    }
                }
            }
            return diskCache;
        }
    }

    /**
     * 资源的弱应用
     */
    private static class ResourceWeakReference extends WeakReference<EngineResource<?>> {
        private final Key key;

        public ResourceWeakReference(Key key, EngineResource<?> r, ReferenceQueue<? super EngineResource<?>> q) {
            super(r, q);
            this.key = key;
        }
    }

    // Responsible for cleaning up the active resource map by remove weak references that have been cleared.
    // 注意到实现了IdleHandler，即当前队列处于空闲的时候的回调处理
    private static class RefQueueIdleHandler implements MessageQueue.IdleHandler {
        private final Map<Key, WeakReference<EngineResource<?>>> activeResources;
        private final ReferenceQueue<EngineResource<?>> queue;

        public RefQueueIdleHandler(Map<Key, WeakReference<EngineResource<?>>> activeResources,
                ReferenceQueue<EngineResource<?>> queue) {
            this.activeResources = activeResources;
            this.queue = queue;
        }

        @Override
        public boolean queueIdle() {
            //结合弱应用来说，此处意义为即将被回收的对象
            ResourceWeakReference ref = (ResourceWeakReference) queue.poll();
            if (ref != null) {//从缓存中清除
                activeResources.remove(ref.key);
            }
            //返回true意味着保留当前回调，否则回调一次之后就会移除
            return true;
        }
    }

    // Visible for testing.
    static class EngineJobFactory {
        private final ExecutorService diskCacheService;
        private final ExecutorService sourceService;
        private final EngineJobListener listener;

        public EngineJobFactory(ExecutorService diskCacheService, ExecutorService sourceService,
                EngineJobListener listener) {
            this.diskCacheService = diskCacheService;
            this.sourceService = sourceService;
            this.listener = listener;
        }

        public EngineJob build(Key key, boolean isMemoryCacheable) {
            return new EngineJob(key, diskCacheService, sourceService, isMemoryCacheable, listener);
        }
    }
}
