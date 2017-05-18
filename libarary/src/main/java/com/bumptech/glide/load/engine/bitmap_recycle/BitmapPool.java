package com.bumptech.glide.load.engine.bitmap_recycle;

import android.graphics.Bitmap;

/**
 * An interface for a pool that allows users to reuse {@link android.graphics.Bitmap} objects.
 */
public interface BitmapPool {

    /**
     * Returns the current maximum size of the pool in bytes.
     * 返回当前循环池的最大可容纳大小
     */
    int getMaxSize();

    /**
     * Multiplies the initial size of the pool by the given multipler to dynamically and synchronously allow users to
     * adjust the size of the pool.
     * 指定当前循环池大小的比例，基于当前循环池中的maxSize，通过0到1的一个比例来调整循环池中的maxSize。
     * 一般来说可能用于校正内存的过多使用
     * <p>
     *     If the current total size of the pool is larger than the max size after the given multiplier is applied,
     *     {@link Bitmap}s should be evicted until the pool is smaller than the new max size.
     *     一般来说在修改了大小之后要重新校验，从而保证当前循环池的大小满足新设置的大小
     * </p>
     *
     * @param sizeMultiplier The size multiplier to apply between 0 and 1.
     */
    void setSizeMultiplier(float sizeMultiplier);

    /**
     * Adds the given {@link android.graphics.Bitmap} and returns {@code true} if the {@link android.graphics.Bitmap}
     * was eligible to be added and {@code false} otherwise.
     * 将当前Bitmap添加到循环池中，返回值为true表示回收成功，否则失败。
     *
     * <p>
     *     Note - If the {@link android.graphics.Bitmap} is rejected (this method returns false) then it is the caller's
     *     responsibility to call {@link android.graphics.Bitmap#recycle()}.
     *     如果当前Bitmap被拒绝参与循环使用，那么意味着使用者应该手动调用Bitmap的recycle来释放Bitmap所占用的内存
     * </p>
     *
     * <p>
     *     Note - This method will return {@code true} if the given {@link android.graphics.Bitmap} is synchronously
     *     evicted after being accepted. The only time this method will return {@code false} is if the
     *     {@link android.graphics.Bitmap} is not eligible to be added to the pool (either it is not mutable or it is
     *     larger than the max pool size).
     *      这个方法只会在一些不合适的情况下返回false，比方说当前Bitmap不允许draw（mutable）、当前Bitmap自身的大小大于整个循环池的大小
     *      等。
     * </p>
     *
     * @see android.graphics.Bitmap#isMutable()
     * @see android.graphics.Bitmap#recycle()
     *
     * @param bitmap The {@link android.graphics.Bitmap} to attempt to add.
     */
    boolean put(Bitmap bitmap);

    /**
     * Returns a {@link android.graphics.Bitmap} of exactly the given width, height, and configuration, and containing
     * only transparent pixels or null if no such {@link android.graphics.Bitmap} could be obtained from the pool.
     * 返回一个包括准确的宽高、配置信息、并且所有像素为透明的Bitmap，如果池中没有则返回null。
     *
     * <p>
     *     Because this method erases all pixels in the {@link Bitmap}, this method is slightly slower than
     *     {@link #getDirty(int, int, android.graphics.Bitmap.Config)}. If the {@link android.graphics.Bitmap} is being
     *     obtained to be used in {@link android.graphics.BitmapFactory} or in any other case where every pixel in the
     *     {@link android.graphics.Bitmap} will always be overwritten or cleared,
     *     {@link #getDirty(int, int, android.graphics.Bitmap.Config)} will be faster. When in doubt, use this method
     *     to ensure correctness.
     *     相对于getDirty来说这个方法会慢一些，因为要擦除所有的像素。
     *     需要根据场景正确的决定使用get()还是getDirty()
     * </p>
     *
     * <pre>
     *     Implementations can should clear out every returned Bitmap using the following:
     *
     * {@code
     * bitmap.eraseColor(Color.TRANSPARENT);
     * }
     * </pre>
     *
     * @see #getDirty(int, int, android.graphics.Bitmap.Config)
     *
     * @param width The width in pixels of the desired {@link android.graphics.Bitmap}.
     * @param height The height in pixels of the desired {@link android.graphics.Bitmap}.
     * @param config The {@link android.graphics.Bitmap.Config} of the desired {@link android.graphics.Bitmap}.
     */
    Bitmap get(int width, int height, Bitmap.Config config);

    /**
     * Identical to {@link #get(int, int, android.graphics.Bitmap.Config)} except that any returned non-null
     * {@link android.graphics.Bitmap} may <em>not</em> have been erased and may contain random data.
     *
     * <p>
     *     Although this method is slightly more efficient than {@link #get(int, int, android.graphics.Bitmap.Config)}
     *     it should be used with caution and only when the caller is sure that they are going to erase the
     *     {@link android.graphics.Bitmap} entirely before writing new data to it.
     *     尽管相对于get来说效率更高，但是在写入数据的时候要注意确保像素的擦除问题。
     * </p>
     *
     * @see #get(int, int, android.graphics.Bitmap.Config)
     *
     * @param width The width in pixels of the desired {@link android.graphics.Bitmap}.
     * @param height The height in pixels of the desired {@link android.graphics.Bitmap}.
     * @param config The {@link android.graphics.Bitmap.Config} of the desired {@link android.graphics.Bitmap}.
     * @return A {@link android.graphics.Bitmap} with exactly the given width, height, and config potentially containing
     * random image data or null if no such {@link android.graphics.Bitmap} could be obtained from the pool.
     */
    Bitmap getDirty(int width, int height, Bitmap.Config config);

    /**
     * Removes all {@link android.graphics.Bitmap}s from the pool.
     * 从循环池中移除所有的bitmap
     */
    void clearMemory();

    /**
     * Reduces the size of the cache by evicting items based on the given level.
     * 减少当前缓存的大小基于给定的等级（一般就是对应生命周期回调低内存的同时清理一下缓存）
     *
     * @see android.content.ComponentCallbacks2
     *
     * @param level The level from {@link android.content.ComponentCallbacks2} to use to determine how many
     * {@link android.graphics.Bitmap}s to evict.
     */
    void trimMemory(int level);
}
