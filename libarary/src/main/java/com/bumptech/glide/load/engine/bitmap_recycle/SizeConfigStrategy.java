package com.bumptech.glide.load.engine.bitmap_recycle;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.os.Build;

import com.bumptech.glide.util.Util;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Keys {@link android.graphics.Bitmap Bitmaps} using both {@link android.graphics.Bitmap#getAllocationByteCount()} and
 * the {@link android.graphics.Bitmap.Config} returned from {@link android.graphics.Bitmap#getConfig()}.
 * LruBitmapPool在API>19的时候的默认使用策略
 * 在API19之后可以重新配置Bitmap的配置，从而做到从大到小的复用
 * <p>
 *     Using both the config and the byte size allows us to safely re-use a greater variety of
 *     {@link android.graphics.Bitmap Bitmaps}, which increases the hit rate of the pool and therefore the performance
 *     of applications. This class works around #301 by only allowing re-use of {@link android.graphics.Bitmap Bitmaps}
 *     with a matching number of bytes per pixel.
 * </p>
 */
@TargetApi(Build.VERSION_CODES.KITKAT)
public class SizeConfigStrategy implements LruPoolStrategy {
    //复用的时候允许的最大倍数
    private static final int MAX_SIZE_MULTIPLE = 8;
    private static final Bitmap.Config[] ARGB_8888_IN_CONFIGS = new Bitmap.Config[] {
            Bitmap.Config.ARGB_8888,
            // The value returned by Bitmaps with the hidden Bitmap config.
            null,
    };
    // We probably could allow ARGB_4444 and RGB_565 to decode into each other, but ARGB_4444 is deprecated and we'd
    // rather be safe.
    private static final Bitmap.Config[] RGB_565_IN_CONFIGS = new Bitmap.Config[] {
            Bitmap.Config.RGB_565
    };
    private static final Bitmap.Config[] ARGB_4444_IN_CONFIGS = new Bitmap.Config[] {
            Bitmap.Config.ARGB_4444
    };
    private static final Bitmap.Config[] ALPHA_8_IN_CONFIGS = new Bitmap.Config[] {
            Bitmap.Config.ALPHA_8
    };
    //键名队列
    private final KeyPool keyPool = new KeyPool();
    /**
     * key:size和config组成的对象
     * value:bitmap
     * 数据结构本身是一个双向链表，内部带有缓存，可以快速命中链表中的节点
     */
    private final GroupedLinkedMap<Key, Bitmap> groupedMap = new GroupedLinkedMap<Key, Bitmap>();
    /**
     * key:bitmap.config
     * value:<bitmap.size,当前同样的bitmap.size的数量>
     */
    private final Map<Bitmap.Config, NavigableMap<Integer, Integer>> sortedSizes =
            new HashMap<Bitmap.Config, NavigableMap<Integer, Integer>>();

    @Override
    public void put(Bitmap bitmap) {
        //获得当前bitmap的大小
        int size = Util.getBitmapByteSize(bitmap);
        //从复用池中根据指定的参数获取指定的key
        Key key = keyPool.get(size, bitmap.getConfig());
        //缓存bitmap
        groupedMap.put(key, bitmap);
        //记录当前config对应的size所缓存的数量
        NavigableMap<Integer, Integer> sizes = getSizesForConfig(bitmap.getConfig());
        Integer current = sizes.get(key.size);
        sizes.put(key.size, current == null ? 1 : current + 1);
    }

    @Override
    public Bitmap get(int width, int height, Bitmap.Config config) {
        //计算当前想要获得的bitmap的size
        int size = Util.getBitmapByteSize(width, height, config);
        //从复用池中根据指定的参数获得键值
        Key targetKey = keyPool.get(size, config);
        //尝试从通过key和参数中获取一个可用的值，这样增加命中groupedMap的可能
        Key bestKey = findBestKey(targetKey, size, config);

        Bitmap result = groupedMap.get(bestKey);
        if (result != null) {
            // Decrement must be called before reconfigure.
            // 因为当前从缓存中获取bitmap，需要清理sortKey中的数据
            decrementBitmapOfSize(Util.getBitmapByteSize(result), result.getConfig());
            // 当前要求复用Bitmap，但是因为匹配的问题，当前Bitmap可能大于理想的大小或者说宽高不同
            // 需要重新设置Bitmap的大小和config
            // 具体可以看reconfigure的注释
            result.reconfigure(width, height,
                    result.getConfig() != null ? result.getConfig() : Bitmap.Config.ARGB_8888);
        }
        return result;
    }

    /**
     * 根据参数，找到一个最好的键值
     * 因为在API19以上可以接受从大到小的bitmap的size
     */
    private Key findBestKey(Key key, int size, Bitmap.Config config) {
        Key result = key;
        for (Bitmap.Config possibleConfig : getInConfigs(config)) {//遍历支持的bitmap.config
            //获得size和数量的红黑树
            NavigableMap<Integer, Integer> sizesForPossibleConfig = getSizesForConfig(possibleConfig);
            //获取红黑树中大于等于给定size的一个节点的key
            Integer possibleSize = sizesForPossibleConfig.ceilingKey(size);
            //注意这里有上限
            if (possibleSize != null && possibleSize <= size * MAX_SIZE_MULTIPLE) {
                if (possibleSize != size
                        || (possibleConfig == null ? config != null : !possibleConfig.equals(config))) {
                    //这里说明键值有所变化，简单说就是通过RGB565和4M，然后匹配到了缓存中的RGB565和6M的一个key
                    //需要修改键的内容
                    keyPool.offer(key);
                    result = keyPool.get(possibleSize, possibleConfig);
                }
                break;
            }
        }
        return result;
    }

    /**
     * 清理数据
     */
    @Override
    public Bitmap removeLast() {
        Bitmap removed = groupedMap.removeLast();
        if (removed != null) {
            int removedSize = Util.getBitmapByteSize(removed);
            decrementBitmapOfSize(removedSize, removed.getConfig());
        }
        return removed;
    }

    /**
     *  减少bitmap.config和size所记录的数量-1
     */
    private void decrementBitmapOfSize(Integer size, Bitmap.Config config) {
        //每次从缓存中命中bitmap，都需要清理config和size的计数
        NavigableMap<Integer, Integer> sizes = getSizesForConfig(config);
        Integer current = sizes.get(size);
        if (current == 1) {
            sizes.remove(size);
        } else {
            sizes.put(size, current - 1);
        }
    }

    /**
     * 根据Bitmap.config获得指定的集合
     * key:bitmap的size大小
     * value:count数量
     */
    private NavigableMap<Integer, Integer> getSizesForConfig(Bitmap.Config config) {
        NavigableMap<Integer, Integer> sizes = sortedSizes.get(config);
        if (sizes == null) {
            sizes = new TreeMap<Integer, Integer>();
            sortedSizes.put(config, sizes);
        }
        return sizes;
    }

    @Override
    public String logBitmap(Bitmap bitmap) {
        int size = Util.getBitmapByteSize(bitmap);
        return getBitmapString(size, bitmap.getConfig());
    }

    @Override
    public String logBitmap(int width, int height, Bitmap.Config config) {
        int size = Util.getBitmapByteSize(width, height, config);
        return getBitmapString(size, config);
    }

    @Override
    public int getSize(Bitmap bitmap) {
        return Util.getBitmapByteSize(bitmap);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder()
                .append("SizeConfigStrategy{groupedMap=")
                .append(groupedMap)
                .append(", sortedSizes=(");
        for (Map.Entry<Bitmap.Config, NavigableMap<Integer, Integer>> entry : sortedSizes.entrySet()) {
            sb.append(entry.getKey()).append('[').append(entry.getValue()).append("], ");
        }
        if (!sortedSizes.isEmpty()) {
            sb.replace(sb.length() - 2, sb.length(), "");
        }
        return sb.append(")}").toString();
    }

    // Visible for testing.
    static class KeyPool extends BaseKeyPool<Key> {

        public Key get(int size, Bitmap.Config config) {
            Key result = get();
            result.init(size, config);
            return result;
        }

        @Override
        protected Key create() {
            return new Key(this);
        }
    }

    // Visible for testing.
    /**
     * 一个简单的复用池模型
     */
    static final class Key implements Poolable {
        private final KeyPool pool;

        private int size;
        private Bitmap.Config config;

        public Key(KeyPool pool) {
            this.pool = pool;
        }

        // Visible for testing.
        Key(KeyPool pool, int size, Bitmap.Config config) {
            this(pool);
            init(size, config);
        }

        public void init(int size, Bitmap.Config config) {
            this.size = size;
            this.config = config;
        }

        @Override
        public void offer() {
            pool.offer(this);
        }

        @Override
        public String toString() {
            return getBitmapString(size, config);
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Key) {
                Key other = (Key) o;
                return size == other.size && (config == null ? other.config == null : config.equals(other.config));
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = size;
            result = 31 * result + (config != null ? config.hashCode() : 0);
            return result;
        }
    }

    private static String getBitmapString(int size, Bitmap.Config config) {
        return "[" + size + "](" + config + ")";
    }

    private static Bitmap.Config[] getInConfigs(Bitmap.Config requested) {
        switch (requested) {
            case ARGB_8888:
                return ARGB_8888_IN_CONFIGS;
            case RGB_565:
                return RGB_565_IN_CONFIGS;
            case ARGB_4444:
                return ARGB_4444_IN_CONFIGS;
            case ALPHA_8:
                return ALPHA_8_IN_CONFIGS;
            default:
                return new Bitmap.Config[] { requested };
        }
    }
}
