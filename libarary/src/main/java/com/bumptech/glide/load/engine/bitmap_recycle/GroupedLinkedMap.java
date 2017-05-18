package com.bumptech.glide.load.engine.bitmap_recycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Similar to {@link java.util.LinkedHashMap} when access ordered except that it is access ordered on groups
 * of bitmaps rather than individual objects. The idea is to be able to find the LRU bitmap size, rather than the
 * LRU bitmap object. We can then remove bitmaps from the least recently used size of bitmap when we need to
 * reduce our cache size.
 *
 * For the purposes of the LRU, we count gets for a particular size of bitmap as an access, even if no bitmaps
 * of that size are present. We do not count addition or removal of bitmaps as an access.
 */
class GroupedLinkedMap<K extends Poolable, V> {
    private final LinkedEntry<K, V> head = new LinkedEntry<K, V>();
    //这个相当于一个缓存，不通过遍历列表的方式来查询某个key是否添加，空间换时间
    private final Map<K, LinkedEntry<K, V>> keyToEntry = new HashMap<K, LinkedEntry<K, V>>();

    /**
     * 放入数据
     */
    public void put(K key, V value) {
        //先看看链表中是否已经有添加过当前的key
        LinkedEntry<K, V> entry = keyToEntry.get(key);

        if (entry == null) {//如果没有，则重新创建并且尝试添加
            entry = new LinkedEntry<K, V>(key);
            makeTail(entry);//新数据会插入到双向链表的尾部
            keyToEntry.put(key, entry);//记录当前数据已经添加
        } else {
            //不需要重复创建LinkedEntry
            //回收当前key
            key.offer();
        }
        //添加value，注意这个可以重复
        entry.add(value);
    }

    /**
     * 获取数据
     * @param key 键名
     */
    public V get(K key) {
        //当前链表中没有这个key对应的数据
        LinkedEntry<K, V> entry = keyToEntry.get(key);
        if (entry == null) {
            //新建节点对象
            entry = new LinkedEntry<K, V>(key);
            //记录当前链表中有当前key
            keyToEntry.put(key, entry);
        } else {
            //回收key用于复用
            key.offer();
        }
        //将当前节点移动到双向链表的头部
        makeHead(entry);
        //拿出当前节点里面的最后一个数据
        return entry.removeLast();
    }

    /**
     * 从尾部开始向头部移除一个数据
     * @return 被移除的数据,null的话说明没有数据可以移除
     */
    public V removeLast() {
        //实际上就是拿到尾部节点，当然也可能是head本身
        LinkedEntry<K, V> last = head.prev;
        //实际上就是反向遍历一遍
        while (!last.equals(head)) {
            V removed = last.removeLast();
            if (removed != null) {
                return removed;
            } else {
                // We will clean up empty lru entries since they are likely to have been one off or unusual sizes and
                // are not likely to be requested again so the gc thrash should be minimal. Doing so will speed up our
                // removeLast operation in the future and prevent our linked list from growing to arbitrarily large
                // sizes.
                // 当前节点数据为空，可以移除当前节点
                removeEntry(last);
                keyToEntry.remove(last.key);
                last.key.offer();
            }
            //当前节点数据为空，没有意义，继续反向遍历
            last = last.prev;
        }

        return null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GroupedLinkedMap( ");
        LinkedEntry<K, V> current = head.next;
        boolean hadAtLeastOneItem = false;
        while (!current.equals(head)) {
            hadAtLeastOneItem = true;
            sb.append('{').append(current.key).append(':').append(current.size()).append("}, ");
            current = current.next;
        }
        if (hadAtLeastOneItem) {
            sb.delete(sb.length() - 2, sb.length());
        }
        return sb.append(" )").toString();
    }

    // Make the entry the most recently used item.

    /**
     * 将当前节点插入到head节点后面
     */
    private void makeHead(LinkedEntry<K, V> entry) {
        removeEntry(entry);
        entry.prev = head;
        entry.next = head.next;
        updateEntry(entry);
    }

    // Make the entry the least recently used item.

    /**
     * 将当前指定节点插入链表中作为尾部
     */
    private void makeTail(LinkedEntry<K, V> entry) {
        removeEntry(entry);//将当前添加的entry从旧的链表中移除
        entry.prev = head.prev;//前指针指向末位的节点
        entry.next = head;//后指针指向头节点
        updateEntry(entry);
    }

    /**
     * 双向链表中添加某一个节点的链表中已有节点的通用操作
     */
    private static <K, V> void updateEntry(LinkedEntry<K, V> entry) {
        entry.next.prev = entry;
        entry.prev.next = entry;
    }

    /**
     * 双向链表中已有节点移除某一个指定的节点的通用操作
     */
    private static <K, V> void removeEntry(LinkedEntry<K, V> entry) {
        entry.prev.next = entry.next;
        entry.next.prev = entry.prev;
    }

    /**
     * 节点数据结构
     * 本身是一个key和List的node
     * 组成的双向链表
     */
    private static class LinkedEntry<K, V> {
        private final K key;
        //允许重复添加
        private List<V> values;
        //双向链表
        LinkedEntry<K, V> next;
        LinkedEntry<K, V> prev;

        // Used only for the first item in the list which we will treat specially and which will not contain a value.
        public LinkedEntry() {
            this(null);
        }

        public LinkedEntry(K key) {
            next = prev = this;
            this.key = key;
        }

        /**
         * 删除当前节点里面的内容列表里面的最后一个数据，如果有的话
         * @return 被删除的数据
         */
        public V removeLast() {
            final int valueSize = size();
            return valueSize > 0 ? values.remove(valueSize - 1) : null;
        }

        public int size() {
            return values != null ? values.size() : 0;
        }

        public void add(V value) {
            if (values == null) {
                values = new ArrayList<V>();
            }
            values.add(value);
        }
    }
}
