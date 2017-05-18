package com.bumptech.glide.load.engine.cache;

import android.annotation.SuppressLint;

import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.util.LruCache;

/**
 * An LRU in memory cache for {@link com.bumptech.glide.load.engine.Resource}s.
 * 一个LruCache，扩展了低内存的操作
 */
public class LruResourceCache extends LruCache<Key, Resource<?>> implements MemoryCache {
    private ResourceRemovedListener listener;

    /**
     * Constructor for LruResourceCache.
     *
     * @param size The maximum size in bytes the in memory cache can use.
     */
    public LruResourceCache(int size) {
        super(size);
    }

    @Override
    public void setResourceRemovedListener(ResourceRemovedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onItemEvicted(Key key, Resource<?> item) {
        if (listener != null) {
            listener.onResourceRemoved(item);
        }
    }

    @Override
    protected int getSize(Resource<?> item) {
        return item.getSize();
    }

    /**
     * 主要就是实现这个。同步生命周期的时候
     */
    @SuppressLint("InlinedApi")
    @Override
    public void trimMemory(int level) {
        //内存低，可能要清理应用
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // Nearing middle of list of cached background apps
            // Evict our entire bitmap cache
            // 靠近被回收列表的中部，释放所有缓存，减少被回收的可能
            clearMemory();
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // Entering list of cached background apps
            // Evict oldest half of our bitmap cache
            // 还在被回收列表的尾部，但是此时系统内存低，释放一半的缓存减少系统负担
            trimToSize(getCurrentSize() / 2);
        }
    }
}
