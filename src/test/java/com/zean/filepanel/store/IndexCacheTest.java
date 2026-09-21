package com.zean.filepanel.store;

import com.zean.filepanel.core.Exclusions;
import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import com.zean.filepanel.core.ScanResult;
import com.zean.filepanel.core.Scanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 索引缓存的往返一致性。
 *
 * <p>缓存的价值是"二次启动秒开"，但它同时是<b>最容易把过期或错误数据展示给用户</b>的地方：
 * 一旦根目录判断失误，用户就会看到另一个文件夹的内容，而界面上没有任何提示。
 * 因此"根目录不匹配必须拒绝"这条比"能存能读"更重要。
 */
class IndexCacheTest {

    private static ScanResult scan(Path root) {
        return new Scanner(Exclusions.none(), null).scan(root, () -> false);
    }

    @Test
    @DisplayName("保存后能原样读回：条目数、大小、类型、时间、隐藏属性")
    void roundTrip(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("a.txt"), "hello");
        Files.writeString(root.resolve("sub/b.png"), "xx");
        Files.createDirectories(root.resolve("emptyDir"));

        ScanResult original = scan(root);
        IndexCache cache = new IndexCache(dataDir);
        assertTrue(cache.save(original));
        assertTrue(cache.exists());

        Optional<ScanResult> loaded = cache.load(root);
        assertTrue(loaded.isPresent(), "同一根目录应能读回缓存");

        ScanResult restored = loaded.get();
        assertEquals(original.fileCount(), restored.fileCount());
        assertEquals(original.dirCount(), restored.dirCount());
        assertEquals(original.totalBytes(), restored.totalBytes());

        FileItem a = restored.items().stream()
                .filter(i -> i.name().equals("a.txt")).findFirst().orElseThrow();
        assertEquals("txt", a.ext());
        assertEquals(FileKind.DOCUMENT, a.kind());
        assertEquals(5, a.size());
        assertEquals("a.txt", a.relPath());
        assertEquals(0, a.depth());

        FileItem b = restored.items().stream()
                .filter(i -> i.name().equals("b.png")).findFirst().orElseThrow();
        assertEquals("sub", b.parentRel());
        assertEquals("sub/b.png", b.relPath());
        assertEquals(1, b.depth(), "层级必须能从缓存还原，否则排序与展示会错");
        assertEquals(FileKind.IMAGE, b.kind());

        assertTrue(restored.items().stream().anyMatch(FileItem::directory),
                "目录条目也要能还原");
        assertFalse(restored.items().stream().anyMatch(i -> i.modified() == null),
                "时间戳必须还原，否则时间列会全是空的");
    }

    @Test
    @DisplayName("根目录不匹配时拒绝使用缓存（绝不能把 A 文件夹的内容当成 B 的）")
    void refusesMismatchedRoot(@TempDir Path rootA, @TempDir Path rootB, @TempDir Path dataDir)
            throws IOException {
        Files.writeString(rootA.resolve("a.txt"), "x");
        Files.writeString(rootB.resolve("b.txt"), "y");

        IndexCache cache = new IndexCache(dataDir);
        assertTrue(cache.save(scan(rootA)));

        assertTrue(cache.load(rootA).isPresent());
        assertTrue(cache.load(rootB).isEmpty(),
                "换到别的文件夹时必须拒绝这份缓存，改由重新扫描填充");
    }

    @Test
    @DisplayName("根目录大小写不同仍视为同一个（Windows 忽略大小写）")
    void acceptsCaseDifference(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");
        IndexCache cache = new IndexCache(dataDir);
        cache.save(scan(root));

        if (PathKey.caseInsensitive()) {
            Path upper = Path.of(root.toString().toUpperCase(java.util.Locale.ROOT));
            assertTrue(cache.load(upper).isPresent(),
                    "同一目录的不同大小写写法不应导致缓存失效");
        }
    }

    @Test
    @DisplayName("没有缓存、缓存损坏都返回空 Optional 而不是抛异常")
    void toleratesMissingAndCorrupt(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        IndexCache cache = new IndexCache(dataDir);
        assertTrue(cache.load(root).isEmpty(), "没有缓存文件时应返回空");
        assertFalse(cache.exists());

        Files.createDirectories(dataDir);
        Files.writeString(cache.file(), "{ 坏掉的");
        assertTrue(cache.exists(), "文件确实写进去了");
        assertTrue(cache.load(root).isEmpty(), "缓存损坏时应返回空，而不是抛异常");

        // 损坏的文件也该能被清掉，让下次启动重新扫描
        assertTrue(cache.invalidate(), "文件存在，删除应成功");
        assertFalse(cache.exists());
        assertFalse(cache.invalidate(), "再删一次就没了，返回 false");
    }

    @Test
    @DisplayName("条目过多时拒绝写缓存，而不是产生一个又大又慢的文件")
    void refusesHugeSnapshot(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        IndexCache cache = new IndexCache(dataDir);

        List<FileItem> many = new ArrayList<>();
        for (int i = 0; i <= IndexCache.MAX_CACHED_ITEMS; i++) {
            many.add(new FileItem("f" + i + ".txt", root.resolve("f" + i + ".txt"), "f" + i + ".txt",
                    "", false, "txt", FileKind.DOCUMENT, 1, null, null, null, 0, false, false, false));
        }
        ScanResult huge = new ScanResult(root, many, List.of(), 0, 0, List.of(), 0,
                many.size(), 0, many.size(), 0, 1, false, false);

        assertFalse(cache.save(huge), "超过上限应放弃缓存");
        assertFalse(cache.exists(), "放弃时不应留下半成品文件");
    }

    @Test
    @DisplayName("空扫描结果不写缓存")
    void refusesEmptySnapshot(@TempDir Path root, @TempDir Path dataDir) {
        IndexCache cache = new IndexCache(dataDir);
        ScanResult empty = new ScanResult(root, List.of(), List.of(), 0, 0, List.of(), 0,
                0, 0, 0, 0, 1, false, false);

        assertFalse(cache.save(empty));
        assertFalse(cache.exists());
    }

    @Test
    @DisplayName("invalidate 能删掉已有缓存")
    void invalidateRemovesFile(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        Files.writeString(root.resolve("a.txt"), "x");
        IndexCache cache = new IndexCache(dataDir);
        cache.save(scan(root));
        assertTrue(cache.exists());

        assertTrue(cache.invalidate());
        assertFalse(cache.exists());
    }
}
