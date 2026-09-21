package com.zean.filepanel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索范围测试。
 *
 * <p>范围这个功能最容易出的错是"选了却没生效"——界面看起来正常、提示也在，
 * 结果集合却和没选一样。所以这里不只断言解析出来了什么条件，
 * 还断言<b>判定结果真的变了</b>：用同一个关键词分别在 ALL 与 NAME 下跑一遍。
 */
class SearchScopeTest {

    private static FileItem item(String name, String relPath, long size, String ext, FileKind kind) {
        return new FileItem(name, Path.of("C:/root/" + relPath), relPath,
                relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "",
                false, ext, kind, size, null, null, null, 0, false, false, false);
    }

    private static FileItem inFolder(String folder, String name) {
        String ext = FileItem.extensionOf(name);
        return item(name, folder + "/" + name, 1024, ext, FileKind.ofExtension(ext));
    }

    // ------------------------------------------------------------------ 文件名

    @Test
    @DisplayName("范围=文件名：同一个词不再命中「所在路径里有、文件名里没有」的文件")
    void nameScopeIgnoresPath() {
        FileItem unrelated = inFolder("灰度发布管理平台", "会议记录.docx");

        // 默认范围（全部）：关键词命中所在路径
        assertTrue(SearchParser.parse("灰度发布").matches(unrelated),
                "默认行为本来就该能按目录名找到东西");
        // 收紧到文件名后必须不再命中
        assertFalse(SearchParser.parse("灰度发布", SearchScope.NAME, SearchQuery.Op.GT).matches(unrelated),
                "范围=文件名时不该再看所在路径，否则这个下拉等于没做");

        // 文件名里真的有时，两个范围都要命中
        FileItem named = inFolder("别的目录", "灰度发布台账.xlsx");
        assertTrue(SearchParser.parse("灰度发布").matches(named));
        assertTrue(SearchParser.parse("灰度发布", SearchScope.NAME, SearchQuery.Op.GT).matches(named));
    }

    @Test
    @DisplayName("范围=文件名：条件描述必须写明「文件名包含」，用户才能看出范围生效了")
    void nameScopeIsVisibleInDescription() {
        SearchQuery query = SearchParser.parse("灰度发布", SearchScope.NAME, SearchQuery.Op.GT);

        assertTrue(query.describe().contains("文件名包含"), query.describe());
        // 与默认范围的说法必须能区分开，否则提示行无法反映用户的选择
        assertFalse(SearchParser.parse("灰度发布").describe().contains("文件名包含"));
    }

    // ------------------------------------------------------------------ 类型

    @Test
    @DisplayName("范围=类型：中文与英文类型名都认，且真的只留该类型")
    void kindScopeAcceptsChineseAndEnglish() {
        SearchQuery chinese = SearchParser.parse("图片", SearchScope.KIND, SearchQuery.Op.GT);
        FileItem png = inFolder("图片", "示意图.png");
        FileItem doc = inFolder("文档", "说明.docx");

        assertTrue(chinese.matches(png));
        assertFalse(chinese.matches(doc), "范围=图片时不该命中 Word 文档");

        SearchQuery english = SearchParser.parse("image", SearchScope.KIND, SearchQuery.Op.GT);
        assertTrue(english.matches(png));
        assertEquals(chinese.describe(), english.describe(), "中英文写法应得到同一个条件");

        // 一次写多个类型
        SearchQuery multi = SearchParser.parse("表格,文档", SearchScope.KIND, SearchQuery.Op.GT);
        assertTrue(multi.matches(doc));
        assertFalse(multi.matches(png));
    }

    @Test
    @DisplayName("范围=类型：不认识的词降级成普通文字，并留下说明（绝不静默变成 0 结果）")
    void kindScopeDegradesWithNote() {
        SearchParser.ParseResult result =
                SearchParser.parseWithNotes("绝不是一个类型名", SearchScope.KIND, SearchQuery.Op.GT);

        assertTrue(result.query().describe().contains("包含"),
                "应当退回普通文字匹配：" + result.query().describe());
        assertEquals(1, result.notes().size(), String.valueOf(result.notes()));
        assertTrue(result.notes().get(0).contains("已按普通文字搜索"), result.notes().get(0));
    }

    // ------------------------------------------------------------------ 大小

    @Test
    @DisplayName("范围=大小：裸关键词按界面选的比较方式解释，默认「大于」")
    void sizeScopeUsesChosenOperator() {
        FileItem big = inFolder("x", "big.bin");
        big = item("big.bin", "x/big.bin", 10L * 1024 * 1024, "bin", FileKind.ofExtension("bin"));
        FileItem small = item("small.bin", "x/small.bin", 1024, "bin", FileKind.ofExtension("bin"));

        SearchQuery greater = SearchParser.parse("1MB", SearchScope.SIZE, SearchQuery.Op.GT);
        assertTrue(greater.describe().contains("大小 >"), greater.describe());
        assertTrue(greater.matches(big));
        assertFalse(greater.matches(small));

        SearchQuery less = SearchParser.parse("1MB", SearchScope.SIZE, SearchQuery.Op.LT);
        assertTrue(less.describe().contains("大小 <"), less.describe());
        assertTrue(less.matches(small));
        assertFalse(less.matches(big));

        // 恰好等于阈值时：> 不命中，>= 命中——这是最容易写反的边界
        FileItem exact = item("exact.bin", "x/exact.bin", 1024 * 1024, "bin",
                FileKind.ofExtension("bin"));
        assertFalse(SearchParser.parse("1MB", SearchScope.SIZE, SearchQuery.Op.GT).matches(exact));
        assertTrue(SearchParser.parse("1MB", SearchScope.SIZE, SearchQuery.Op.GE).matches(exact));
    }

    @Test
    @DisplayName("范围=大小：不是有效大小时降级并说明")
    void sizeScopeDegradesWithNote() {
        SearchParser.ParseResult result =
                SearchParser.parseWithNotes("很大", SearchScope.SIZE, SearchQuery.Op.GT);

        assertTrue(result.query().describe().contains("包含"), result.query().describe());
        assertEquals(1, result.notes().size(), String.valueOf(result.notes()));
        assertTrue(result.notes().get(0).contains("不是有效的大小"), result.notes().get(0));
    }

    // ------------------------------------------------------ 显式语法优先于范围

    @Test
    @DisplayName("显式语法永远优先于范围（否则粘贴进来的复杂查询会被范围悄悄改义）")
    void explicitSyntaxAlwaysWins() {
        SearchQuery query = SearchParser.parse("size:<=100KB", SearchScope.SIZE, SearchQuery.Op.GT);

        // 范围说"默认大于"，但用户自己写了 <=，必须听用户的
        assertTrue(query.describe().contains("大小 ≤"), query.describe());
        assertFalse(query.describe().contains(">"), query.describe());

        // 范围=文件名时，显式的大小/类型条件不该被当成文件名
        SearchQuery mixed = SearchParser.parse("报表 size:>10MB", SearchScope.NAME, SearchQuery.Op.GT);
        assertTrue(mixed.describe().contains("文件名包含「报表」"), mixed.describe());
        assertTrue(mixed.describe().contains("大小 >"), mixed.describe());

        // 扩展名与通配符本来就是文件名语义，范围不该把它们变成文字匹配
        SearchQuery glob = SearchParser.parse("*.docx", SearchScope.KIND, SearchQuery.Op.GT);
        assertTrue(glob.describe().contains("文件名匹配"), glob.describe());
        SearchQuery ext = SearchParser.parse(".docx", SearchScope.SIZE, SearchQuery.Op.GT);
        assertTrue(ext.describe().contains("扩展名"), ext.describe());
    }

    @Test
    @DisplayName("范围=全部时与历史行为完全一致（不能把老用户的选择改掉）")
    void allScopeKeepsLegacyBehaviour() {
        SearchQuery viaOldApi = SearchParser.parse("灰度发布 *.docx size:>1MB");
        SearchQuery viaScope = SearchParser.parse("灰度发布 *.docx size:>1MB",
                SearchScope.ALL, SearchQuery.Op.GT);

        assertEquals(viaOldApi.describe(), viaScope.describe());
        // 路径命中要用单独一个查询来验：上面那条还带着 *.docx 与 size:>1MB，
        // 拿它去断言"按路径命中"会被另外两个条件否掉（这是本测试第一次写错的地方）
        SearchQuery pathOnly = SearchParser.parse("灰度发布", SearchScope.ALL, SearchQuery.Op.GT);
        assertTrue(pathOnly.matches(inFolder("灰度发布", "任意.txt")),
                "全部范围仍应能按所在路径命中");
    }

    @Test
    @DisplayName("空白、null 与只有空格的输入在各范围下都安全返回空查询")
    void blankInputIsSafe() {
        for (SearchScope scope : SearchScope.values()) {
            assertEquals("", SearchParser.parse(null, scope, SearchQuery.Op.GT).describe());
            assertEquals("", SearchParser.parse("", scope, SearchQuery.Op.GT).describe());
            assertEquals("", SearchParser.parse("   ", scope, SearchQuery.Op.GT).describe());
            assertTrue(SearchParser.parseWithNotes(" ", scope, null).notes().isEmpty());
        }
        // 范围与运算符传 null 也不能抛
        assertTrue(SearchParser.parse("灰度发布", null, null).matches(inFolder("a", "灰度发布.txt")));
    }

    @Test
    @DisplayName("语法清单是浮层与面板的唯一来源，且每条的说明不为空")
    void syntaxItemsAreTheSingleSourceOfTruth() {
        var items = SearchParser.syntaxItems();

        assertTrue(items.size() >= 8, "语法条目太少：" + items.size());
        for (SearchParser.SyntaxItem item : items) {
            assertFalse(item.label().isBlank(), "条目名不能为空");
            assertFalse(item.tip().isBlank(), "每一条都该有一句大白话说明：" + item.label());
        }
        // 浮层文本必须把清单里的例子都带上，否则两处内容会开始各说各话
        String help = SearchParser.syntaxHelp();
        for (SearchParser.SyntaxItem item : items) {
            assertTrue(help.contains(item.label()), "浮层里缺少条目：" + item.label());
            for (String example : item.examples()) {
                assertTrue(help.contains(example), "浮层里缺少例子：" + example);
                // 每个例子都必须是能真的解析出东西的语法，不能是编出来好看的
                SearchQuery parsed = SearchParser.parse(example);
                assertFalse(parsed.isEmpty(), "例子「" + example + "」解析不出任何条件，等于骗用户");
            }
        }
    }

    @Test
    @DisplayName("每个语法例子的含义都必须与它所在条目的说明对得上")
    void syntaxExamplesMatchTheirLabel() {
        // 光断言"能解析"是不够的：ext:docx 若被解析成普通文字也"能解析"，但完全不是那个意思。
        assertTrue(SearchParser.parse("type:图片").describe().contains("类型"));
        assertTrue(SearchParser.parse("size:>10MB").describe().contains("大小"));
        assertTrue(SearchParser.parse("modified:>7d").describe().contains("修改时间"));
        assertTrue(SearchParser.parse("tag:重要").describe().contains("标签"));
        assertTrue(SearchParser.parse("fav:true").describe().contains("收藏"));
        assertTrue(SearchParser.parse("content:关键词").describe().contains("内容"));
        assertTrue(SearchParser.parse("ext:docx,xlsx").describe().contains("扩展名"));
        assertTrue(SearchParser.parse(".docx").describe().contains("扩展名"));
        assertTrue(SearchParser.parse("*.pdf").describe().contains("文件名匹配"));
        assertTrue(SearchParser.parse("\"会议 记录\"").describe().contains("会议 记录"));
    }

    @Test
    @DisplayName("类型的分类集合与侧栏分类一致（避免两套类型表）")
    void kindScopeUsesSameKindsAsSidebar() {
        // 侧栏与 type: 都走 FileKind.ofKey，这里只固定住"中文名可用"这个契约
        for (FileKind kind : EnumSet.allOf(FileKind.class)) {
            SearchQuery query = SearchParser.parse(kind.key(), SearchScope.KIND, SearchQuery.Op.GT);
            assertTrue(query.describe().contains("类型"),
                    "类型 key「" + kind.key() + "」在范围=类型下必须被识别，实际：" + query.describe());
        }
    }

    @Test
    @DisplayName("FileTime 为空的历史条目在大小/时间条件下不抛异常")
    void nullTimesAreSafe() {
        FileItem item = item("a.txt", "a.txt", 100, "txt", FileKind.ofExtension("txt"));

        assertFalse(item.modified() instanceof FileTime);
        SearchQuery dateQuery = SearchParser.parse("modified:>7d");
        assertFalse(dateQuery.matches(item), "没有修改时间的条目不该命中时间条件");
    }
}
