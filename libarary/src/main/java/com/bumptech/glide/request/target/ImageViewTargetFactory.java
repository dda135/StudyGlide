package com.bumptech.glide.request.target;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.load.resource.drawable.GlideDrawable;

/**
 * A factory responsible for producing the correct type of {@link com.bumptech.glide.request.target.Target} for a given
 * {@link android.view.View} subclass.
 * ImageView性质的目标工厂
 */
public class ImageViewTargetFactory {
    /**
     * 通过ImageView和指定的资源Class构建对应的Target
     * @param <Z> 指定的资源class，可以看到这里处理GlideDrawable、Bitmap和Drawable
     */
    @SuppressWarnings("unchecked")
    public <Z> Target<Z> buildTarget(ImageView view, Class<Z> clazz) {
        if (GlideDrawable.class.isAssignableFrom(clazz)) {//这个就是ImageView的默认Target
            return (Target<Z>) new GlideDrawableImageViewTarget(view);
        } else if (Bitmap.class.equals(clazz)) {//这里实际上就是setImageBitmap
            return (Target<Z>) new BitmapImageViewTarget(view);
        } else if (Drawable.class.isAssignableFrom(clazz)) {//这里实际上就是setImageDrawable
            return (Target<Z>) new DrawableImageViewTarget(view);
        } else {
            throw new IllegalArgumentException("Unhandled class: " + clazz
                    + ", try .as*(Class).transcode(ResourceTranscoder)");
        }
    }
}
