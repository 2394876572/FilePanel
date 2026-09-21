package com.zean.filepanel.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最近使用的读写与迁移语义。
 *
 * <p>重点是两类"数据看着还在、其实已经错位"的情况：
 * 同一个文件被反复操作时是否去重计数，以及文件改名后记录有没有跟着走。
 */
class RecentStoreTest {

    private static Path touch(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "x");
    }

    @Test
    @DisplayName("同一路径只记一条，重复操作累加次数并刷新时间")
    void upsertsByPath(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("a.txt"));
        RecentStore store = new RecentStore(dataDir);

        store.record(file, RecentStore.Op.OPEN, false, 100);
        long first = store.recent(1).get(0).timestamp;
        store.record(file, RecentStore.Op.OPEN, false, 100);
        store.record(file, RecentStore.Op.COPY_PATH, false, 100);

        assertEquals(1, store.size(), "同一个文件不应占多行");
        RecentStore.Entry entry = store.recent(1).get(0);
        assertEquals(3, entry.count);
        assertEquals(RecentStore.Op.COPY_PATH, entry.op, "应记录最后一次操作类型");
        assertTrue(entry.timestamp >= first);
    }

    @Test
    @DisplayName("按最后操作时间倒序排列；同一毫秒内的连续操作也保持确定的先后")
    void sortedByTimeDesc(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = touch(dir.resolve("a.txt"));
        Path b = touch(dir.resolve("b.txt"));
        Path c = touch(dir.resolve("c.txt"));
        RecentStore store = new RecentStore(dataDir);

        store.record(a, RecentStore.Op.OPEN, false, 1);
        store.record(b, RecentStore.Op.OPEN, false, 1);
        store.record(c, RecentStore.Op.OPEN, false, 1);
        // 再碰一次 a，它应该回到最前
        store.record(a, RecentStore.Op.OPEN, false, 1);

        List<RecentStore.Entry> recent = store.all();
        assertEquals(3, recent.size());
        assertEquals("a.txt", recent.get(0).name);
        // 这三条很可能落在同一毫秒里，靠次序号保证 c 在 b 之前而不是"看谁先被遍历到"
        assertEquals("c.txt", recent.get(1).name);
        assertEquals("b.txt", recent.get(2).name);

        // 顺序必须是确定的：反复读取结果一致
        assertEquals(recent.stream().map(e -> e.name).toList(),
                store.all().stream().map(e -> e.name).toList());
    }

    @Test
    @DisplayName("重命名后记录跟着迁移，不会从“最近使用”里消失")
    void remapsOnRename(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path oldPath = touch(dir.resolve("旧名字.txt"));
        Path newPath = dir.resolve("新名字.txt");
        RecentStore store = new RecentStore(dataDir);
        store.record(oldPath, RecentStore.Op.OPEN, false, 10);

        assertTrue(store.remapPath(oldPath, newPath));

        assertFalse(store.contains(oldPath), "旧路径不应再能查到");
        assertTrue(store.contains(newPath));
        assertEquals("新名字.txt", store.recent(1).get(0).name, "展示名也要跟着更新");
        assertEquals(1, store.size(), "迁移不应产生多余记录");
    }

    @Test
    @DisplayName("迁移不存在的记录返回 false，不抛异常")
    void remapMissingIsSafe(@TempDir Path dir, @TempDir Path dataDir) {
        RecentStore store = new RecentStore(dataDir);
        assertFalse(store.remapPath(dir.resolve("never.txt"), dir.resolve("x.txt")));
        assertFalse(store.remapPath(null, dir.resolve("x.txt")));
    }

    @Test
    @DisplayName("删除后移除记录，并清理指向已不存在文件的死链")
    void removesAndPrunes(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path alive = touch(dir.resolve("alive.txt"));
        Path gone = touch(dir.resolve("gone.txt"));
        RecentStore store = new RecentStore(dataDir);
        store.record(alive, RecentStore.Op.OPEN, false, 1);
        store.record(gone, RecentStore.Op.OPEN, false, 1);

        assertTrue(store.remove(alive));
        assertEquals(1, store.size());

        Files.delete(gone);
        assertEquals(1, store.pruneMissing(), "指向已删除文件的记录应被清理");
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("落盘后重新加载保持一致（重启后最近记录还在）")
    void survivesRestart(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("persist.txt"));
        RecentStore first = new RecentStore(dataDir);
        first.record(file, RecentStore.Op.RENAME, false, 42);

        RecentStore second = new RecentStore(dataDir);
        assertEquals(1, second.size());
        RecentStore.Entry entry = second.recent(1).get(0);
        assertEquals(RecentStore.Op.RENAME, entry.op);
        assertEquals(42, entry.size);
        assertEquals("persist.txt", entry.name);
    }

    @Test
    @DisplayName("大小写不同的同一路径视为同一条（Windows 忽略大小写）")
    void caseInsensitiveOnWindows(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path lower = touch(dir.resolve("report.txt"));
        RecentStore store = new RecentStore(dataDir);
        store.record(lower, RecentStore.Op.OPEN, false, 1);

        if (PathKey.caseInsensitive()) {
            store.record(dir.resolve("REPORT.TXT"), RecentStore.Op.OPEN, false, 1);
            assertEquals(1, store.size(), "大小写不同不应产生第二条记录");
            assertEquals(2, store.recent(1).get(0).count);
        }
    }

    @Test
    @DisplayName("超过上限后丢弃最旧的记录")
    void enforcesCap(@TempDir Path dataDir) {
        RecentStore store = new RecentStore(dataDir);
        for (int i = 0; i < RecentStore.MAX_ENTRIES + 20; i++) {
            store.record(Path.of("C:/nonexistent/f" + i + ".txt"), RecentStore.Op.OPEN, false, 1);
        }
        assertEquals(RecentStore.MAX_ENTRIES, store.size());
    }

    @Test
    @DisplayName("数据文件损坏时安静地返回空，而不是抛异常")
    void toleratesCorruptFile(@TempDir Path dataDir) throws IOException {
        RecentStore store = new RecentStore(dataDir);
        Files.createDirectories(dataDir);
        Files.writeString(store.file(), "{ 这不是数组");

        assertEquals(0, store.size());
        assertTrue(store.all().isEmpty());
    }
}
