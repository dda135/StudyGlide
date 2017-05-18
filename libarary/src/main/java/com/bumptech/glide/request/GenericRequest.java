package com.bumptech.glide.request;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.Engine;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.bumptech.glide.provider.LoadProvider;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.animation.GlideAnimationFactory;
import com.bumptech.glide.request.target.SizeReadyCallback;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Util;

import java.util.Queue;

/**
 * A {@link Request} that loads a {@link com.bumptech.glide.load.engine.Resource} into a given {@link Target}.
 * 一个基础的请求基类
 * 每一个请求对应一个Request，内部有复用机制
 * 注意到内部的加载采用的是状态模式的转换
 *
 * @param <A> The type of the model that the resource will be loaded from.
 * @param <T> The type of the data that the resource will be loaded from.
 * @param <Z> The type of the resource that will be loaded.
 * @param <R> The type of the resource that will be transcoded from the loaded resource.
 */
public final class GenericRequest<A, T, Z, R> implements Request, SizeReadyCallback,
        ResourceCallback {
    private static final String TAG = "GenericRequest";
    //Request的废弃队列，通过这个队列进行复用
    private static final Queue<GenericRequest<?, ?, ?, ?>> REQUEST_POOL = Util.createQueue(0);
    private static final double TO_MEGABYTE = 1d / (1024d * 1024d);

    /**
     * 当前请求所处于的状态枚举
     */
    private enum Status {
        /** Created but not yet running. */
        PENDING,
        /** In the process of fetching media. */
        RUNNING,
        /** Waiting for a callback given to the Target to be called to determine target dimensions. */
        WAITING_FOR_SIZE,
        /** Finished loading media successfully. */
        COMPLETE,
        /** Failed to load media, may be restarted. */
        FAILED,
        /** Cancelled by the user, may not be restarted. */
        CANCELLED,
        /** Cleared by the user with a placeholder set, may not be restarted. */
        CLEARED,
        /** Temporarily paused by the system, may be restarted. */
        PAUSED,
    }

    private final String tag = String.valueOf(hashCode());

    private Key signature;
    private Drawable fallbackDrawable;
    private int fallbackResourceId;
    private int placeholderResourceId;
    private int errorResourceId;
    private Context context;
    private Transformation<Z> transformation;
    private LoadProvider<A, T, Z, R> loadProvider;
    //请求的协调者，用于处理一些特殊的标记
    private RequestCoordinator requestCoordinator;
    private A model;
    private Class<R> transcodeClass;
    private boolean isMemoryCacheable;
    private Priority priority;
    private Target<R> target;
    private RequestListener<? super A, R> requestListener;
    private float sizeMultiplier;
    private Engine engine;
    private GlideAnimationFactory<R> animationFactory;
    private int overrideWidth;
    private int overrideHeight;
    private DiskCacheStrategy diskCacheStrategy;

    private Drawable placeholderDrawable;
    private Drawable errorDrawable;
    private boolean loadedFromMemoryCache;
    // doing our own type check
    // 记录当前Request对应的资源
    private Resource<?> resource;
    private Engine.LoadStatus loadStatus;
    private long startTime;
    private Status status;

    public static <A, T, Z, R> GenericRequest<A, T, Z, R> obtain(
            LoadProvider<A, T, Z, R> loadProvider,
            A model,
            Key signature,
            Context context,
            Priority priority,
            Target<R> target,
            float sizeMultiplier,
            Drawable placeholderDrawable,
            int placeholderResourceId,
            Drawable errorDrawable,
            int errorResourceId,
            Drawable fallbackDrawable,
            int fallbackResourceId,
            RequestListener<? super A, R> requestListener,
            RequestCoordinator requestCoordinator,
            Engine engine,
            Transformation<Z> transformation,
            Class<R> transcodeClass,
            boolean isMemoryCacheable,
            GlideAnimationFactory<R> animationFactory,
            int overrideWidth,
            int overrideHeight,
            DiskCacheStrategy diskCacheStrategy) {
        //尝试复用一个Request，如果没有则新建
        @SuppressWarnings("unchecked")
        GenericRequest<A, T, Z, R> request = (GenericRequest<A, T, Z, R>) REQUEST_POOL.poll();
        if (request == null) {
            request = new GenericRequest<A, T, Z, R>();
        }
        //设置一堆参数
        request.init(loadProvider,
                model,
                signature,
                context,
                priority,
                target,
                sizeMultiplier,
                placeholderDrawable,
                placeholderResourceId,
                errorDrawable,
                errorResourceId,
                fallbackDrawable,
                fallbackResourceId,
                requestListener,
                requestCoordinator,
                engine,
                transformation,
                transcodeClass,
                isMemoryCacheable,
                animationFactory,
                overrideWidth,
                overrideHeight,
                diskCacheStrategy);
        return request;
    }

    private GenericRequest() {
        // just create, instances are reused with recycle/init
    }

    @Override
    public void recycle() {
        loadProvider = null;
        model = null;
        context = null;
        target = null;
        placeholderDrawable = null;
        errorDrawable = null;
        fallbackDrawable = null;
        requestListener = null;
        requestCoordinator = null;
        transformation = null;
        animationFactory = null;
        loadedFromMemoryCache = false;
        loadStatus = null;
        REQUEST_POOL.offer(this);//添加到废弃队列中，等待复用
    }

    private void init(
            LoadProvider<A, T, Z, R> loadProvider,
            A model,
            Key signature,
            Context context,
            Priority priority,
            Target<R> target,
            float sizeMultiplier,
            Drawable placeholderDrawable,
            int placeholderResourceId,
            Drawable errorDrawable,
            int errorResourceId,
            Drawable fallbackDrawable,
            int fallbackResourceId,
            RequestListener<? super A, R> requestListener,
            RequestCoordinator requestCoordinator,
            Engine engine,
            Transformation<Z> transformation,
            Class<R> transcodeClass,
            boolean isMemoryCacheable,
            GlideAnimationFactory<R> animationFactory,
            int overrideWidth,
            int overrideHeight,
            DiskCacheStrategy diskCacheStrategy) {
        this.loadProvider = loadProvider;
        this.model = model;
        this.signature = signature;
        this.fallbackDrawable = fallbackDrawable;
        this.fallbackResourceId = fallbackResourceId;
        this.context = context.getApplicationContext();
        this.priority = priority;
        this.target = target;
        this.sizeMultiplier = sizeMultiplier;
        this.placeholderDrawable = placeholderDrawable;
        this.placeholderResourceId = placeholderResourceId;
        this.errorDrawable = errorDrawable;
        this.errorResourceId = errorResourceId;
        this.requestListener = requestListener;
        this.requestCoordinator = requestCoordinator;
        this.engine = engine;
        this.transformation = transformation;
        this.transcodeClass = transcodeClass;
        this.isMemoryCacheable = isMemoryCacheable;
        this.animationFactory = animationFactory;
        this.overrideWidth = overrideWidth;
        this.overrideHeight = overrideHeight;
        this.diskCacheStrategy = diskCacheStrategy;
        status = Status.PENDING;//标识状态为等待中

        // We allow null models by just setting an error drawable. Null models will always have empty providers, we
        // simply skip our sanity checks in that unusual case.
        if (model != null) {
            check("ModelLoader", loadProvider.getModelLoader(), "try .using(ModelLoader)");
            check("Transcoder", loadProvider.getTranscoder(), "try .as*(Class).transcode(ResourceTranscoder)");
            check("Transformation", transformation, "try .transform(UnitTransformation.get())");
            if (diskCacheStrategy.cacheSource()) {
                check("SourceEncoder", loadProvider.getSourceEncoder(),
                        "try .sourceEncoder(Encoder) or .diskCacheStrategy(NONE/RESULT)");
            } else {
                check("SourceDecoder", loadProvider.getSourceDecoder(),
                        "try .decoder/.imageDecoder/.videoDecoder(ResourceDecoder) or .diskCacheStrategy(ALL/SOURCE)");
            }
            if (diskCacheStrategy.cacheSource() || diskCacheStrategy.cacheResult()) {
                // TODO if(resourceClass.isAssignableFrom(InputStream.class) it is possible to wrap sourceDecoder
                // and use it instead of cacheDecoder: new FileToStreamDecoder<Z>(sourceDecoder)
                // in that case this shouldn't throw
                check("CacheDecoder", loadProvider.getCacheDecoder(),
                        "try .cacheDecoder(ResouceDecoder) or .diskCacheStrategy(NONE)");
            }
            if (diskCacheStrategy.cacheResult()) {
                check("Encoder", loadProvider.getEncoder(),
                        "try .encode(ResourceEncoder) or .diskCacheStrategy(NONE/SOURCE)");
            }
        }
    }

    private static void check(String name, Object object, String suggestion) {
        if (object == null) {
            StringBuilder message = new StringBuilder(name);
            message.append(" must not be null");
            if (suggestion != null) {
                message.append(", ");
                message.append(suggestion);
            }
            throw new NullPointerException(message.toString());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void begin() {
        startTime = LogTime.getLogTime();
        if (model == null) {
            onException(null);
            return;
        }
        //修改状态为等待加载的大小
        status = Status.WAITING_FOR_SIZE;
        //当前是否有设置合理的宽和高
        if (Util.isValidDimensions(overrideWidth, overrideHeight)) {
            //如果有直接开始加载准备
            onSizeReady(overrideWidth, overrideHeight);
        } else {
            //否则回到到Target的GetSize中
            //注意这里是为了获得一个确切的大小，比方说等待绘制结束之类的
            //总之最后会通过SizeReadyCallback进行回调回到onSizeReady
            target.getSize(this);
        }
        //当前先设置占位图
        if (!isComplete() && !isFailed() && canNotifyStatusChanged()) {
            target.onLoadStarted(getPlaceholderDrawable());
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("finished run method in " + LogTime.getElapsedMillis(startTime));
        }
    }

    /**
     * Cancels the current load but does not release any resources held by the request and continues to display
     * the loaded resource if the load completed before the call to cancel.
     * 取消当前的请求，但是不会释放资源，因为取消的时候有可能资源加载已经完成，此时的资源在使用中不应该被回收
     * <p>
     *     Cancelled requests can be restarted with a subsequent call to {@link #begin()}.
     *     取消的请求可以通过begin重新开始
     * </p>
     *
     * @see #clear()
     */
    void cancel() {
        status = Status.CANCELLED;//标识当前状态为被取消
        if (loadStatus != null) {
            loadStatus.cancel();//取消EngineJob的回调
            loadStatus = null;
        }
    }

    /**
     * Cancels the current load if it is in progress, clears any resources held onto by the request and replaces
     * the loaded resource if the load completed with the placeholder.
     * 取消当前的请求，并且释放资源，如果资源加载完成的话通过占位图替代资源
     * <p>
     *     Cleared requests can be restarted with a subsequent call to {@link #begin()}
     *     清除的请求可以通过begin重新启动
     * </p>
     *
     * @see #cancel()
     */
    @Override
    public void clear() {
        Util.assertMainThread();
        if (status == Status.CLEARED) {
            return;
        }
        cancel();//先取消当前请求
        // Resource must be released before canNotifyStatusChanged is called.
        if (resource != null) {//如果资源获取完毕，释放资源
            releaseResource(resource);
        }
        if (canNotifyStatusChanged()) {//尝试通过占位图替代被释放的资源
            target.onLoadCleared(getPlaceholderDrawable());
        }
        // Must be after cancel().
        status = Status.CLEARED;//状态由取消转为清除
    }

    @Override
    public boolean isPaused() {
        return status == Status.PAUSED;
    }

    /**
     * 暂停当前请求，可以通过begin重新启动
     */
    @Override
    public void pause() {
        clear();//清除当前请求，注意这时候显示可能是占位图
        status = Status.PAUSED;//标记为暂停状态
    }

    /**
     * 释放资源
     */
    private void releaseResource(Resource resource) {
        engine.release(resource);
        this.resource = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRunning() {
        return status == Status.RUNNING || status == Status.WAITING_FOR_SIZE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResourceSet() {
        return isComplete();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled() {
        return status == Status.CANCELLED || status == Status.CLEARED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFailed() {
        return status == Status.FAILED;
    }

    private Drawable getFallbackDrawable() {
      if (fallbackDrawable == null && fallbackResourceId > 0) {
        fallbackDrawable = context.getResources().getDrawable(fallbackResourceId);
      }
      return fallbackDrawable;
    }

    /**
     * 设置异常时候的占位图
     * 如果在builder中设置了特定的失败回调的图片，则使用之
     * 否则尝试使用同一的失败图片
     * 再否则尝试使用占位图
     */
    private void setErrorPlaceholder(Exception e) {
        if (!canNotifyStatusChanged()) {
            return;
        }

        Drawable error = model == null ? getFallbackDrawable() : null;
        if (error == null) {
          error = getErrorDrawable();
        }
        if (error == null) {
            error = getPlaceholderDrawable();
        }
        target.onLoadFailed(e, error);
    }

    private Drawable getErrorDrawable() {
        if (errorDrawable == null && errorResourceId > 0) {
            errorDrawable = context.getResources().getDrawable(errorResourceId);
        }
        return errorDrawable;
    }

    private Drawable getPlaceholderDrawable() {
        if (placeholderDrawable == null && placeholderResourceId > 0) {
            placeholderDrawable = context.getResources().getDrawable(placeholderResourceId);
        }
        return placeholderDrawable;
    }

    /**
     * A callback method that should never be invoked directly.
     * 当加载的大小确定之后应该进行的下一步处理
     */
    @Override
    public void onSizeReady(int width, int height) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("Got onSizeReady in " + LogTime.getElapsedMillis(startTime));
        }
        if (status != Status.WAITING_FOR_SIZE) {//需要合理的状态转换
            return;
        }
        status = Status.RUNNING;//修改状态为运行中
        //计算实际要处理的大小
        width = Math.round(sizeMultiplier * width);
        height = Math.round(sizeMultiplier * height);
        //这里的Loader可以到Glide的构造方法中看到具体的注册工厂
        ModelLoader<A, T> modelLoader = loadProvider.getModelLoader();
        //通过ModelLoader获得指定的DataFetcher，这个主要用于从网络之类的地方获取资源
        final DataFetcher<T> dataFetcher = modelLoader.getResourceFetcher(model, width, height);

        if (dataFetcher == null) {
            onException(new Exception("Failed to load model: \'" + model + "\'"));
            return;
        }
        ResourceTranscoder<Z, R> transcoder = loadProvider.getTranscoder();
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("finished setup for calling load in " + LogTime.getElapsedMillis(startTime));
        }
        loadedFromMemoryCache = true;
        //加载工作委托给了Engine进行
        loadStatus = engine.load(signature, width, height, dataFetcher, loadProvider, transformation, transcoder,
                priority, isMemoryCacheable, diskCacheStrategy, this);
        //这里内存缓存的处理在当前线程完成，所以这里可以通过资源是否为空判断是否从内存中读取
        loadedFromMemoryCache = resource != null;
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("finished onSizeReady in " + LogTime.getElapsedMillis(startTime));
        }
    }

    private boolean canSetResource() {
        return requestCoordinator == null || requestCoordinator.canSetImage(this);
    }

    private boolean canNotifyStatusChanged() {
        return requestCoordinator == null || requestCoordinator.canNotifyStatusChanged(this);
    }

    private boolean isFirstReadyResource() {
        return requestCoordinator == null || !requestCoordinator.isAnyResourceSet();
    }

    private void notifyLoadSuccess() {
      if (requestCoordinator != null) {
        requestCoordinator.onRequestSuccess(this);
      }
    }

    /**
     * A callback method that should never be invoked directly.
     * 当资源准备完毕之后的回调
     */
    @SuppressWarnings("unchecked")
    @Override
    public void onResourceReady(Resource<?> resource) {
        if (resource == null) {
            onException(new Exception("Expected to receive a Resource<R> with an object of " + transcodeClass
                    + " inside, but instead got null."));
            return;
        }
        //获得实际的加载对象
        Object received = resource.get();
        if (received == null || !transcodeClass.isAssignableFrom(received.getClass())) {//加载失败或者与期望的加载对象不同
            //释放资源
            releaseResource(resource);
            //回调失败
            onException(new Exception("Expected to receive an object of " + transcodeClass
                    + " but instead got " + (received != null ? received.getClass() : "") + "{" + received + "}"
                    + " inside Resource{" + resource + "}."
                    + (received != null ? "" : " "
                        + "To indicate failure return a null Resource object, "
                        + "rather than a Resource object containing null data.")
            ));
            return;
        }
        //如果requestCoordinator标记不允许设置资源
        if (!canSetResource()) {
            //释放资源
            releaseResource(resource);
            // We can't set the status to complete before asking canSetResource().
            //标记加载完成，因为是人为设置不允许设置资源，此时相当于加载正常完成，只是不设置资源
            status = Status.COMPLETE;
            return;
        }

        onResourceReady(resource, (R) received);
    }

    /**
     * Internal {@link #onResourceReady(Resource)} where arguments are known to be safe.
     * 处理资源加载完成的情况
     * @param resource original {@link Resource}, never <code>null</code>
     * @param result object returned by {@link Resource#get()}, checked for type and never <code>null</code>
     */
    private void onResourceReady(Resource<?> resource, R result) {
        // We must call isFirstReadyResource before setting status.
        boolean isFirstResource = isFirstReadyResource();
        status = Status.COMPLETE;//标记加载完成
        this.resource = resource;//标记当前request对应的资源

        if (requestListener == null || !requestListener.onResourceReady(result, model, target, loadedFromMemoryCache,
                isFirstResource)) {
            GlideAnimation<R> animation = animationFactory.build(loadedFromMemoryCache, isFirstResource);
            //这里将动画和资源最终交给Target处理了
            target.onResourceReady(result, animation);
        }
        //通知requestCoordinator加载完成
        notifyLoadSuccess();

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logV("Resource ready in " + LogTime.getElapsedMillis(startTime) + " size: "
                    + (resource.getSize() * TO_MEGABYTE) + " fromCache: " + loadedFromMemoryCache);
        }
    }

    /**
     * A callback method that should never be invoked directly.
     * 出现异常时候的回调
     */
    @Override
    public void onException(Exception e) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "load failed", e);
        }

        status = Status.FAILED;//标记状态为失败
        //TODO: what if this is a thumbnail request?
        //先回调requestListener
        if (requestListener == null || !requestListener.onException(e, model, target, isFirstReadyResource())) {
            setErrorPlaceholder(e);//设置失败时候的占位图
        }
    }

    private void logV(String message) {
        Log.v(TAG, message + " this: " + tag);
    }
}
