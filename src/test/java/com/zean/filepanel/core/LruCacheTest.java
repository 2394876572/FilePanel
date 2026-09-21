package com.zean.filepanel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LRU 缓存测试（缩略图缓存的基础）。
 *
 * <p>这类结构的错法很安静：容量上限失效只会让内存慢慢涨、
 * 淘汰顺序写错只会让滚动时反复重新解码。都不会抛异常，所以必须逐条断言行为。
 */
class LruCacheTest {

    @Test
    @DisplayName("超过容量时淘汰最久未使用的那一项")
    void evictsLeastRecentlyUsed() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        cache.put("d", 4);

        assertEquals(3, cache.size());
        assertNull(cache.get("a"), "a 最久没用过，应当被淘汰");
        assertEquals(2, cache.get("b"));
        assertEquals(4, cache.get("d"));
        assertEquals(1, cache.evictions());
    }

    @Test
    @DisplayName("get 会把命中项标记为「最近使用」，从而改变淘汰顺序")
    void getRefreshesRecency() {
        LruCache<String, Integer> cache = new LruCache<>(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);

        // 摸一下 a，让它变成"最近用过"
        assertEquals(1, cache.get("a"));

        cache.put("d", 4);

        assertNull(cache.get("b"), "b 现在才是最久没用过的");
        assertEquals(1, cache.get("a"), "a 被 get 过，应当还在");
        assertEquals(3, cache.get("c"));
    }

    @Test
    @DisplayName("重复 put 同一个键不会占用两个位置，也不会被立刻淘汰")
    void putSameKeyUpdatesValue() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("k", "v1");
        cache.put("k", "v2");

        assertEquals(1, cache.size());
        assertEquals("v2", cache.get("k"));
        assertEquals(0, cache.evictions());
    }

    @Test
    @DisplayName("容量为 1 也能正常工作")
    void worksWithCapacityOne() {
        LruCache<String, Integer> cache = new LruCache<>(1);
        cache.put("a", 1);
        cache.put("b", 2);

        assertEquals(1, cache.size());
        assertNull(cache.get("a"));
        assertEquals(2, cache.get("b"));
    }

    @Test
    @DisplayName("null 键与 null 值被忽略，不会写入缓存也不会抛异常")
    void ignoresNulls() {
        LruCache<String, Integer> cache = new LruCache<>(2);
        cache.put(null, 1);
        cache.put("a", null);

        assertEquals(0, cache.size());
        assertNull(cache.get(null));
        assertFalse(cache.contains(null));
    }

    @Test
    @DisplayName("容量小于 1 视为编程错误，直接抛出（而不是静默变成一个不缓存的空壳）")
    void rejectsInvalidCapacity() {
        try {
            new LruCache<String, Integer>(0);
            org.junit.jupiter.api.Assertions.fail("容量 0 应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("至少为 1"), expected.getMessage());
        }
    }

    @Test
    @DisplayName("clear 之后一切归零，且淘汰计数保留（便于观察历史）")
    void clearEmptiesButKeepsStats() {
        LruCache<String, Integer> cache = new LruCache<>(1);
        cache.put("a", 1);
        cache.put("b", 2);
        long before = cache.evictions();

        cache.clear();

        assertEquals(0, cache.size());
        assertNull(cache.get("b"));
        assertEquals(before, cache.evictions());
    }
}
