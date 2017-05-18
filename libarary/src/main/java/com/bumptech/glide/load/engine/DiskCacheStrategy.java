package com.bumptech.glide.load.engine;

/**
 * Set of available caching strategies for media.
 */
public enum DiskCacheStrategy {
    /** Caches with both {@link #SOURCE} and {@link #RESULT}. */
    ALL(true, true),
    /** Saves no data to cache. */
    NONE(false, false),
    /** Saves just the original data to cache. */
    SOURCE(true, false),
    /** Saves the media item after all transformations to cache. */
    RESULT(false, true);
    //是否允许从硬盘缓存中读取原始资源
    private final boolean cacheSource;
    //是否允许从硬盘缓存中读取经过transform之后的资源
    //如果硬盘中只有原图，那么也会在transform之后重新写入到硬盘中
    //下次使用的时候很大可能集中cacheResult
    private final boolean cacheResult;

    DiskCacheStrategy(boolean cacheSource, boolean cacheResult) {
        this.cacheSource = cacheSource;
        this.cacheResult = cacheResult;
    }

    /**
     * Returns true if this request should cache the original unmodified data.
     */
    public boolean cacheSource() {
        return cacheSource;
    }

    /**
     * Returns true if this request should cache the final transformed result.
     */
    public boolean cacheResult() {
        return cacheResult;
    }
}
