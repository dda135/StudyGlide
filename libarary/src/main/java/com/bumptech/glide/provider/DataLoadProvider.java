package com.bumptech.glide.provider;

import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.ResourceEncoder;

import java.io.File;

/**
 * A load provider that provides the necessary encoders and decoders to decode a specific type of resource from a
 * specific type of data.
 * 数据加载的基础接口
 *
 * @param <T> The type of data the resource will be decoded from.
 * @param <Z> The type of resource that will be decoded.
 */
public interface DataLoadProvider<T, Z> {

    /**
     * Returns the {@link com.bumptech.glide.load.ResourceDecoder} to use to decode the resource from the disk cache.
     * 返回一个执行者用于从硬盘缓存中通过File读取资源
     */
    ResourceDecoder<File, Z> getCacheDecoder();

    /**
     * Returns the {@link com.bumptech.glide.load.ResourceDecoder} to use to decode the resource from the original data.
     * 将流转换为资源
     */
    ResourceDecoder<T, Z> getSourceDecoder();

    /**
     * Returns the {@link com.bumptech.glide.load.Encoder} to use to write the original data to the disk cache.
     */
    Encoder<T> getSourceEncoder();

    /**
     * Returns the {@link com.bumptech.glide.load.ResourceEncoder} to use to write the decoded and transformed resource
     * to the disk cache.
     */
    ResourceEncoder<Z> getEncoder();
}
