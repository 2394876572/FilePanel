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

/** 收藏与标签的读写、批量操作与重命名迁移。 */
class FavoriteAndTagStoreTest {

    private static Path touch(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "x");
    }

    // ------------------------------------------------------------------ 收藏

    @Test
    @DisplayName("收藏的增删改查与切换")
    void favoriteBasics(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("a.txt"));
        FavoriteStore store = new FavoriteStore(dataDir);

        assertFalse(store.isFavorite(file));
        assertTrue(store.toggle(file), "切换后应变为已收藏");
        assertTrue(store.isFavorite(file));
        assertFalse(store.toggle(file), "再切换应取消收藏");
        assertFalse(store.isFavorite(file));
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("重复收藏不产生重复条目")
    void favoriteIsIdempotent(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("a.txt"));
        FavoriteStore store = new FavoriteStore(dataDir);

        store.setFavorite(file, true);
        store.setFavorite(file, true);
        assertEquals(1, store.size());

        store.setFavorite(file, false);
        store.setFavorite(file, false);
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("重命名后收藏跟着走（否则用户会以为收藏丢了）")
    void favoriteFollowsRename(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path oldPath = touch(dir.resolve("旧.txt"));
        Path newPath = dir.resolve("新.txt");
        FavoriteStore store = new FavoriteStore(dataDir);
        store.setFavorite(oldPath, true);

        assertTrue(store.remapPath(oldPath, newPath));
        assertFalse(store.isFavorite(oldPath));
        assertTrue(store.isFavorite(newPath));
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("收藏在大小写不同的路径间互通（Windows 忽略大小写）")
    void favoriteCaseInsensitive(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("report.txt"));
        FavoriteStore store = new FavoriteStore(dataDir);
        store.setFavorite(file, true);

        if (PathKey.caseInsensitive()) {
            assertTrue(store.isFavorite(dir.resolve("REPORT.TXT")),
                    "大小写不同也应命中同一条收藏");
        }
    }

    @Test
    @DisplayName("收藏落盘后重启仍在")
    void favoriteSurvivesRestart(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("a.txt"));
        new FavoriteStore(dataDir).setFavorite(file, true);

        FavoriteStore reloaded = new FavoriteStore(dataDir);
        assertTrue(reloaded.isFavorite(file));
        assertEquals(List.of(file.toAbsolutePath().normalize()), reloaded.paths());
    }

    // ------------------------------------------------------------------ 标签

    @Test
    @DisplayName("标签的增删与清理")
    void tagBasics(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("a.txt"));
        TagStore store = new TagStore(dataDir);

        assertTrue(store.addTag(file, "重要"));
        assertTrue(store.addTag(file, "工作"));
        assertFalse(store.addTag(file, "重要"), "重复添加返回 false");
        assertEquals(List.of("重要", "工作"), store.tagsOf(file));

        assertTrue(store.removeTag(file, "重要"));
        assertEquals(List.of("工作"), store.tagsOf(file));
        assertFalse(store.removeTag(file, "不存在"));
    }

    @Test
    @DisplayName("标签规范化：去首尾空白、折叠内部空白、拒绝空与超长")
    void tagNormalization(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("a.txt"));
        TagStore store = new TagStore(dataDir);

        assertTrue(store.addTag(file, "  重要  "));
        assertEquals(List.of("重要"), store.tagsOf(file), "首尾空白应被去掉");

        assertFalse(store.addTag(file, "   "));
        assertFalse(store.addTag(file, "x".repeat(TagStore.MAX_TAG_LENGTH + 1)));

        assertEquals("重要", TagStore.normalize(" 重要 "));
        assertEquals("a b", TagStore.normalize("a   b"), "内部连续空白折叠成一个");
        assertEquals(null, TagStore.normalize(""));
        assertEquals(null, TagStore.normalize("   "));
        assertEquals(null, TagStore.normalize(null));
    }

    @Test
    @DisplayName("标签会去重且数量有上限")
    void tagDeduplicatesAndCaps(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("a.txt"));
        TagStore store = new TagStore(dataDir);

        store.setTags(file, List.of("a", "a", "b", " b "));
        assertEquals(List.of("a", "b"), store.tagsOf(file));

        for (int i = 0; i < TagStore.MAX_TAGS_PER_FILE + 5; i++) {
            store.addTag(file, "t" + i);
        }
        assertEquals(TagStore.MAX_TAGS_PER_FILE, store.tagsOf(file).size());
    }

    @Test
    @DisplayName("清空标签后该文件不再出现在标签统计里")
    void clearingTagsRemovesFile(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("a.txt"));
        TagStore store = new TagStore(dataDir);
        store.addTag(file, "重要");
        assertEquals(1, store.taggedFileCount());

        store.setTags(file, List.of());
        assertEquals(0, store.taggedFileCount());
        assertTrue(store.allTags().isEmpty(), "没有文件使用的标签应自然消失");
    }

    @Test
    @DisplayName("标签统计按使用次数降序")
    void tagCountsOrdered(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = touch(dir.resolve("a.txt"));
        Path b = touch(dir.resolve("b.txt"));
        Path c = touch(dir.resolve("c.txt"));
        TagStore store = new TagStore(dataDir);

        store.addTag(a, "常用");
        store.addTag(b, "常用");
        store.addTag(c, "常用");
        store.addTag(a, "少见");

        List<String> ordered = store.allTags();
        assertEquals("常用", ordered.get(0));
        assertEquals(3, store.tagCounts().get("常用"));
        assertEquals(1, store.tagCounts().get("少见"));
    }

    @Test
    @DisplayName("重命名后标签跟着走（这是最容易丢数据的地方）")
    void tagsFollowRename(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path oldPath = touch(dir.resolve("旧报告.docx"));
        Path newPath = dir.resolve("新报告.docx");
        TagStore store = new TagStore(dataDir);
        store.setTags(oldPath, List.of("重要", "2026"));

        assertTrue(store.remapPath(oldPath, newPath));

        assertTrue(store.tagsOf(oldPath).isEmpty(), "旧路径不应再有标签");
        assertEquals(List.of("重要", "2026"), store.tagsOf(newPath), "标签必须完整迁移");
        assertEquals(1, store.taggedFileCount());
        assertEquals(2, store.tagCounts().size(), "标签统计不应出现重复");
    }

    @Test
    @DisplayName("批量打标签只统计真正新增的")
    void bulkTagging(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = touch(dir.resolve("a.txt"));
        Path b = touch(dir.resolve("b.txt"));
        TagStore store = new TagStore(dataDir);
        store.addTag(a, "已有");

        int changed = store.addTagToAll(List.of(a, b), "已有");
        assertEquals(1, changed, "a 已有该标签，只有 b 是新增");
        assertEquals(List.of("已有"), store.tagsOf(b));
    }

    @Test
    @DisplayName("标签落盘后重启仍在，且顺序稳定")
    void tagsSurviveRestart(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = touch(dir.resolve("a.txt"));
        new TagStore(dataDir).setTags(file, List.of("重要", "工作"));

        TagStore reloaded = new TagStore(dataDir);
        assertEquals(List.of("重要", "工作"), reloaded.tagsOf(file));
        assertEquals(1, reloaded.taggedFileCount());
    }

    @Test
    @DisplayName("清理指向已不存在文件的标签")
    void prunesMissingTags(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path alive = touch(dir.resolve("alive.txt"));
        Path gone = touch(dir.resolve("gone.txt"));
        TagStore store = new TagStore(dataDir);
        store.addTag(alive, "保留");
        store.addTag(gone, "清理");

        Files.delete(gone);
        assertEquals(1, store.pruneMissing(Files::exists));

        assertTrue(store.tagsOf(gone).isEmpty());
        assertEquals(List.of("保留"), store.tagsOf(alive));
    }

    @Test
    @DisplayName("数据文件损坏时安静地返回空")
    void toleratesCorruptFile(@TempDir Path dataDir) throws IOException {
        TagStore store = new TagStore(dataDir);
        Files.createDirectories(dataDir);
        Files.writeString(store.file(), "不是 JSON");

        assertTrue(store.allTags().isEmpty());
        assertEquals(0, store.taggedFileCount());
    }
}
