package com.bumptech.glide.load.model;

import android.content.Context;

import com.bumptech.glide.load.data.DataFetcher;

import java.util.HashMap;
import java.util.Map;

/**
 * Maintains a map of model class to factory to retrieve a {@link ModelLoaderFactory} and/or a {@link ModelLoader}
 * for a given model type.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
// this is a general class capable of handling any generic combination
/**
 * 默认的ModelLoader工厂
 */
public class GenericLoaderFactory {
    //ModelClass、ResourceClass对应的ModelLoaderFactory的集合
    private final Map<Class/*T*/, Map<Class/*Y*/, ModelLoaderFactory/*T, Y*/>> modelClassToResourceFactories =
            new HashMap<Class, Map<Class, ModelLoaderFactory>>();
    //ModelClass、ResourceClass对应的ModelLoader的缓存
    private final Map<Class/*T*/, Map<Class/*Y*/, ModelLoader/*T, Y*/>> cachedModelLoaders =
            new HashMap<Class, Map<Class, ModelLoader>>();

    private static final ModelLoader NULL_MODEL_LOADER = new ModelLoader() {
        @Override
        public DataFetcher getResourceFetcher(Object model, int width, int height) {
            throw new NoSuchMethodError("This should never be called!");
        }

        @Override
        public String toString() {
            return "NULL_MODEL_LOADER";
        }
    };

    private final Context context;

    public GenericLoaderFactory(Context context) {
       this.context = context.getApplicationContext();
    }

    /**
     * Removes and returns the registered {@link ModelLoaderFactory} for the given model and resource classes. Returns
     * null if no such factory is registered. Clears all cached model loaders.
     * 移除指定的modelClass和resourceClass对应的ModelLoaderFactory
     * @param modelClass The model class.
     * @param resourceClass The resource class.
     * @param <T> The type of the model the class.
     * @param <Y> The type of the resource class.
     */
    public synchronized <T, Y> ModelLoaderFactory<T, Y> unregister(Class<T> modelClass, Class<Y> resourceClass) {
        cachedModelLoaders.clear();
        ModelLoaderFactory/*T, Y*/ result = null;
        Map<Class/*Y*/, ModelLoaderFactory/*T, Y*/> resourceToFactories = modelClassToResourceFactories.get(modelClass);
        if (resourceToFactories != null) {
            result = resourceToFactories.remove(resourceClass);
        }
        return result;
    }

    /**
     * Registers the given {@link ModelLoaderFactory} for the given model and resource classes and returns the previous
     * factory registered for the given model and resource classes or null if no such factory existed. Clears all cached
     * model loaders.
     *
     * @param modelClass The model class.
     * @param resourceClass The resource class.
     * @param factory The factory to register.
     * @param <T> The type of the model.
     * @param <Y> The type of the resource.
     */
    public synchronized <T, Y> ModelLoaderFactory<T, Y> register(Class<T> modelClass, Class<Y> resourceClass,
            ModelLoaderFactory<T, Y> factory) {
        //为了方便阅读，可以假设T(modelClass):String   Y(resourceClass)：InputStream
        //清空ModelLoader的缓存
        cachedModelLoaders.clear();
        //尝试获得String对应的资源和工厂的集合
        Map<Class/*Y*/, ModelLoaderFactory/*T, Y*/> resourceToFactories = modelClassToResourceFactories.get(modelClass);
        if (resourceToFactories == null) {//没有则新建
            resourceToFactories = new HashMap<Class/*Y*/, ModelLoaderFactory/*T, Y*/>();
            modelClassToResourceFactories.put(modelClass, resourceToFactories);
        }
        //放置指定的工厂
        ModelLoaderFactory/*T, Y*/ previous = resourceToFactories.put(resourceClass, factory);
        //对于String来说，获得InputStream的工厂已经设置过
        //对于没有用的工厂要进行移除，这里要确认previous这个工厂是否已经没用
        if (previous != null) {
            // This factory may be being used by another model. We don't want to say it has been removed unless we
            // know it has been removed for all models.
            // 当前工厂是否有用于其他model，比方说Uri之类
            // 如果有的话不应该进行移除
            for (Map<Class/*Y*/, ModelLoaderFactory/*T, Y*/> factories : modelClassToResourceFactories.values()) {
                if (factories.containsValue(previous)) {
                    previous = null;
                    break;
                }
            }
        }
        return previous;
    }

    /**
     * Returns a {@link ModelLoader} for the given model and resource classes by either returning a cached
     * {@link ModelLoader} or building a new a new {@link ModelLoader} using registered {@link ModelLoaderFactory}s.
     * Returns null if no {@link ModelLoaderFactory} is registered for the given classes.
     *
     * @deprecated Use {@link #buildModelLoader(Class, Class)} instead. Scheduled to be removed in Glide 4.0.
     * @param modelClass The model class.
     * @param resourceClass The resource class.
     * @param context Unused
     * @param <T> The type of the model.
     * @param <Y> The type of the resource.
     */
    @Deprecated
    public synchronized <T, Y> ModelLoader<T, Y> buildModelLoader(Class<T> modelClass, Class<Y> resourceClass,
            Context context) {
        return buildModelLoader(modelClass, resourceClass);
    }

    /**
     * Returns a {@link ModelLoader} for the given model and resource classes by either returning a cached
     * {@link ModelLoader} or building a new a new {@link ModelLoader} using registered {@link ModelLoaderFactory}s.
     * Returns null if no {@link ModelLoaderFactory} is registered for the given classes.
     *
     * @param modelClass The model class.
     * @param resourceClass The resource class.
     * @param <T> The type of the model.
     * @param <Y> The type of the resource.
     */
    public synchronized <T, Y> ModelLoader<T, Y> buildModelLoader(Class<T> modelClass, Class<Y> resourceClass) {
        //先尝试从缓存中获取ModelLoader
        ModelLoader<T, Y> result = getCachedLoader(modelClass, resourceClass);
        if (result != null) {
            // We've already tried to create a model loader and can't with the currently registered set of factories,
            // but we can't use null to demonstrate that failure because model loaders that haven't been requested
            // yet will be null in the cache. To avoid this, we use a special signal model loader.
            if (NULL_MODEL_LOADER.equals(result)) {
                return null;
            } else {
                return result;
            }
        }

        final ModelLoaderFactory<T, Y> factory = getFactory(modelClass, resourceClass);
        if (factory != null) {
            result = factory.build(context, this);//通过工厂构建ModelLoader
            cacheModelLoader(modelClass, resourceClass, result);//缓存ModelLoader
        } else {
            // We can't generate a model loader for the given arguments with the currently registered set of factories.
            cacheNullLoader(modelClass, resourceClass);//失败，但是也缓存默认的空ModelLoader
        }
        return result;
    }

    private <T, Y> void cacheNullLoader(Class<T> modelClass, Class<Y> resourceClass) {
        cacheModelLoader(modelClass, resourceClass, NULL_MODEL_LOADER);
    }

    private <T, Y> void cacheModelLoader(Class<T> modelClass, Class<Y> resourceClass, ModelLoader<T, Y> modelLoader) {
        Map<Class/*Y*/, ModelLoader/*T, Y*/> resourceToLoaders = cachedModelLoaders.get(modelClass);
        if (resourceToLoaders == null) {
            resourceToLoaders = new HashMap<Class/*Y*/, ModelLoader/*T, Y*/>();
            cachedModelLoaders.put(modelClass, resourceToLoaders);
        }
        resourceToLoaders.put(resourceClass, modelLoader);
    }

    /**
     * 尝试通过ModelClass和ResourceClass从缓存中获取ModelLoader
     */
    private <T, Y> ModelLoader<T, Y> getCachedLoader(Class<T> modelClass, Class<Y> resourceClass) {
        Map<Class/*Y*/, ModelLoader/*T, Y*/> resourceToLoaders = cachedModelLoaders.get(modelClass);
        ModelLoader/*T, Y*/ result = null;
        if (resourceToLoaders != null) {
            result = resourceToLoaders.get(resourceClass);
        }

        return result;
    }

    /**
     * 通过Model和Resource的类型获得ModelLoaderFactory
     */
    private <T, Y> ModelLoaderFactory<T, Y> getFactory(Class<T> modelClass, Class<Y> resourceClass) {
        Map<Class/*Y*/, ModelLoaderFactory/*T, Y*/> resourceToFactories = modelClassToResourceFactories.get(modelClass);
        ModelLoaderFactory/*T, Y*/ result = null;
        if (resourceToFactories != null) {
            result = resourceToFactories.get(resourceClass);
        }

        if (result == null) {//当前modelClass和resourceClass没有指定的ModelLoaderFactory
            for (Class<? super T> registeredModelClass : modelClassToResourceFactories.keySet()) {
                // This accounts for model subclasses, our map only works for exact matches. We should however still
                // match a subclass of a model with a factory for a super class of that model if if there isn't a
                // factory for that particular subclass. Uris are a great example of when this happens, most uris
                // are actually subclasses for Uri, but we'd generally rather load them all with the same factory rather
                // than trying to register for each subclass individually.
                // 尝试返回父类的ModelLoaderFactory
                // 比方说有的继承Uri实现的HttpUri，如果自身没有命中，尝试使用Uri的工厂
                if (registeredModelClass.isAssignableFrom(modelClass)) {
                    Map<Class/*Y*/, ModelLoaderFactory/*T, Y*/> currentResourceToFactories =
                            modelClassToResourceFactories.get(registeredModelClass);
                    if (currentResourceToFactories != null) {
                        result = currentResourceToFactories.get(resourceClass);
                        if (result != null) {
                            break;
                        }
                    }
                }
            }
        }

        return result;
    }
}
