package com.zean.filepanel.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 固定容量的 LRU 缓存。
 *
 * <p>缩略图是"看得见的内存开销"：一张 4000×3000 的照片缩到 64px 后仍是一张位图，
 * 几百张就是几十 MB。所以必须有上限，而且在超限时淘汰<b>最久没用过</b>的那一张——
 * 界面上刚滚过去的东西马上还要再看，而被滚出视野的短期不会再回来。
 *
 * <p>用 {@link LinkedHashMap} 的 {@code accessOrder=true} 而不是自己维护链表：
 * 那是 JDK 自带的、经过验证的实现，自己写链表只会多出一处能写错的地方。
 *
 * <p><b>刻意做成泛型且不依赖 JavaFX</b>：这样淘汰策略能用普通单测穷举验证
 * （顺序、命中是否刷新位置、重复 put 是否算一次），而不必启动图形工具包。
 */
public final class LruCache<K, V> {

    private final int maxEntries;
    private final LinkedHashMap<K, V> map;

    /** 因为容量上限被淘汰的次数，供自检与排查观察。 */
    private long evictions;

    public LruCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("容量至少为 1，实际 " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                boolean tooMany = size() > LruCache.this.maxEntries;
                if (tooMany) {
                    evictions++;
                }
                return tooMany;
            }
        };
    }

    /** 读取；命中会把这一项标记为"最近用过"。 */
    public V get(K key) {
        return key == null ? null : map.get(key);
    }

    public void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }
        map.put(key, value);
    }

    public boolean contains(K key) {
        return key != null && map.containsKey(key);
    }

    public int size() {
        return map.size();
    }

    public int maxEntries() {
        return maxEntries;
    }

    public long evictions() {
        return evictions;
    }

    public void clear() {
        map.clear();
    }
}
