package com.zean.filepanel.store;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import com.zean.filepanel.core.SearchParser;
import com.zean.filepanel.core.SearchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内容索引与 content: 查询语法。 */
class ContentIndexTest {

    private static FileItem item(Path path, long size, long modified) {
        String name = path.getFileName().toString();
        String ext = FileItem.extensionOf(name);
        return new FileItem(name, path, name, "", false, ext, FileKind.ofExtension(ext),
                size, null, java.nio.file.attribute.FileTime.fromMillis(modified),
                null, 0, false, false, false);
    }

    // ------------------------------------------------------------------ 索引

    @Test
    @DisplayName("写入后能按指纹读回；指纹变了就读不到（文件被改过必须重新抽取）")
    void invalidatesWhenFingerprintChanges(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = Files.writeString(dir.resolve("a.txt"), "内容");
        ContentIndex index = new ContentIndex(dataDir);
        index.put(file, 100, 5000, "灰度发布管理", false);

        assertEquals("灰度发布管理", index.textOf(file, 100, 5000));
        assertNull(index.textOf(file, 100, 6000), "修改时间变了应视为失效");
        assertNull(index.textOf(file, 200, 5000), "大小变了应视为失效");
        assertNull(index.textOf(dir.resolve("other.txt"), 100, 5000), "没索引过的文件返回 null");
    }

    @Test
    @DisplayName("FileItem 版本按条目自己的指纹判断")
    void looksUpByItem(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = Files.writeString(dir.resolve("a.txt"), "内容");
        ContentIndex index = new ContentIndex(dataDir);
        FileItem probe = item(file, 42, 9000);
        index.put(file, 42, 9000, "正文", false);

        assertTrue(index.isIndexed(probe));
        assertEquals("正文", index.textOf(probe));
        assertNull(index.textOf(item(file, 43, 9000)), "大小不同应失效");
    }

    @Test
    @DisplayName("重命名后索引跟着迁移")
    void remapsOnRename(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path oldPath = dir.resolve("旧.docx");
        Path newPath = dir.resolve("新.docx");
        ContentIndex index = new ContentIndex(dataDir);
        index.put(oldPath, 10, 20, "文档正文", false);

        assertTrue(index.remapPath(oldPath, newPath));

        assertNull(index.textOf(oldPath, 10, 20));
        assertEquals("文档正文", index.textOf(newPath, 10, 20), "改名不应丢掉已索引的正文");
        assertEquals(1, index.size());
    }

    @Test
    @DisplayName("清理指向已不存在文件的索引")
    void prunesMissing(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path alive = Files.writeString(dir.resolve("alive.txt"), "x");
        Path gone = Files.writeString(dir.resolve("gone.txt"), "y");
        ContentIndex index = new ContentIndex(dataDir);
        index.put(alive, 1, 1, "保留", false);
        index.put(gone, 1, 1, "清理", false);

        Files.delete(gone);
        assertEquals(1, index.pruneMissing());
        assertEquals(1, index.size());
    }

    @Test
    @DisplayName("达到总量上限后停止收录，已索引的保留")
    void stopsAtTotalCap(@TempDir Path dataDir) {
        ContentIndex index = new ContentIndex(dataDir);
        String chunk = "字".repeat(ContentIndex.MAX_CHARS_PER_ENTRY);

        int accepted = 0;
        for (int i = 0; i < 100; i++) {
            if (index.put(Path.of("C:/demo/f" + i + ".txt"), 1, 1, chunk, false)) {
                accepted++;
            } else {
                break;
            }
        }

        assertTrue(accepted > 0, "至少应接受一个");
        assertTrue(index.isFull(), "应达到上限");
        assertTrue(index.totalChars() <= ContentIndex.MAX_TOTAL_CHARS);
        assertEquals(accepted, index.size(), "被拒绝的条目不该进索引");
    }

    @Test
    @DisplayName("落盘后重新加载仍能查到（重启后不必重新抽取）")
    void survivesRestart(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = Files.writeString(dir.resolve("a.docx"), "x");
        ContentIndex first = new ContentIndex(dataDir);
        first.put(file, 10, 20, "灰度发布整定压力", false);
        assertTrue(first.save());

        ContentIndex reloaded = new ContentIndex(dataDir);
        assertEquals("灰度发布整定压力", reloaded.textOf(file, 10, 20));
        assertEquals(1, reloaded.size());
    }

    @Test
    @DisplayName("清空会同时删掉磁盘上的索引文件")
    void clearRemovesFile(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        ContentIndex index = new ContentIndex(dataDir);
        index.put(file, 1, 1, "正文", false);
        index.save();
        assertTrue(index.exists());

        index.clear();
        assertEquals(0, index.size());
        assertFalse(index.exists());
    }

    // ------------------------------------------------------- content: 语法

    @Test
    @DisplayName("content: 语法被正确解析，且 needsContent 能识别出来")
    void parsesContentSyntax() {
        SearchQuery query = SearchParser.parse("content:灰度发布");
        assertTrue(query.needsContent());
        assertEquals(1, query.contentTerms().size());
        assertEquals("灰度发布", query.contentTerms().get(0));
        assertTrue(query.describe().contains("内容"), query.describe());

        assertFalse(SearchParser.parse("灰度发布").needsContent(), "普通搜索不该触发内容索引");
        assertFalse(SearchParser.parse("type:image").needsContent());
        assertEquals(0, SearchParser.parse("content:").contentTerms().size(),
                "content: 后面为空时应降级为文本匹配");
    }

    @Test
    @DisplayName("内容命中：命中/不命中/未索引三种情况")
    void contentMatching(@TempDir Path dir) throws IOException {
        FileItem file = item(dir.resolve("a.docx"), 1, 1);
        SearchQuery query = SearchParser.parse("content:整定压力");

        assertTrue(query.matches(file, java.util.Set.of(), false, "本文提到整定压力为0.8MPa"));
        assertFalse(query.matches(file, java.util.Set.of(), false, "本文与压力无关"));
        assertFalse(query.matches(file, java.util.Set.of(), false, null),
                "未索引时不算命中——把未知当成命中会造成假阳性");
        assertFalse(query.matches(file, java.util.Set.of(), false),
                "旧的三参数重载不涉及正文，含 content: 时一律不命中");
    }

    @Test
    @DisplayName("内容条件与文件名条件之间是 AND")
    void contentCombinesWithOtherTerms(@TempDir Path dir) throws IOException {
        FileItem docx = item(dir.resolve("报告.docx"), 1, 1);
        SearchQuery query = SearchParser.parse("*.docx content:灰度发布");

        assertTrue(query.matches(docx, java.util.Set.of(), false, "灰度发布整定压力"));
        assertFalse(query.matches(docx, java.util.Set.of(), false, "无关正文"),
                "内容里有词但文件名不匹配时不该命中");
        assertFalse(query.matches(item(dir.resolve("报告.png"), 1, 1),
                java.util.Set.of(), false, "灰度发布整定压力"), "文件名不匹配");
    }

    @Test
    @DisplayName("摘要只截取命中位置附近的一小段，并带省略号")
    void buildsSnippet() {
        SearchQuery.ContentTerm term = new SearchQuery.ContentTerm("整定压力");
        String longText = "开头".repeat(100) + "整定压力为0.8MPa" + "结尾".repeat(100);

        String snippet = term.snippet(longText);

        assertTrue(snippet.contains("整定压力"), snippet);
        assertTrue(snippet.length() < 150, "摘要不该把整篇正文搬出来：" + snippet.length());
        assertTrue(snippet.startsWith("…"), snippet);
        assertTrue(snippet.endsWith("…"), snippet);

        assertEquals("", term.snippet("完全无关的正文"));
        assertEquals("", term.snippet(null));
    }

    @Test
    @DisplayName("摘要把换行折成空格，避免单元格里出现多行")
    void snippetFlattensWhitespace() {
        SearchQuery.ContentTerm term = new SearchQuery.ContentTerm("压力");
        String snippet = term.snippet("第一行\n第二行 压力\n第三行");
        assertFalse(snippet.contains("\n"), snippet);
    }
}
