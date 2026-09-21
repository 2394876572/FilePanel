package com.zean.filepanel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索语法测试。
 *
 * <p>本类里最重要的不是“正确语法能用”，而是<b>错误语法不能炸</b>。
 * 搜索框是逐字符触发的，用户输入过程中必然产生大量语法上不合法的中间状态；
 * 只要其中一个状态抛异常或让结果闪空，搜索功能就废了。
 * 所以 {@code malformedInputNeverThrows} 这一类断言比功能断言更重要。
 */
class SearchParserTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static FileItem file(String relPath, String ext, FileKind kind, long size) {
        return file(relPath, ext, kind, size, LocalDate.now());
    }

    private static FileItem file(String relPath, String ext, FileKind kind, long size, LocalDate modified) {
        String name = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
        FileTime t = modified == null ? null
                : FileTime.from(modified.atStartOfDay(ZONE).toInstant());
        return new FileItem(name, Path.of("C:/root/" + relPath), relPath,
                relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "",
                false, ext, kind, size, t, t, t, 0, false, false, false);
    }

    private static boolean match(String query, FileItem item) {
        return SearchParser.parse(query).matches(item, Set.of(), false);
    }

    // ------------------------------------------------------------ 文本匹配

    @Test
    @DisplayName("普通文本匹配文件名，忽略大小写")
    void textMatchesName() {
        FileItem doc = file("会议记录.docx", "docx", FileKind.DOCUMENT, 100);
        assertTrue(match("会议", doc));
        assertTrue(match("记录", doc));
        assertTrue(match("DOCX", doc));
        assertTrue(match("docx", doc));
        assertFalse(match("报表", doc));
    }

    @Test
    @DisplayName("普通文本也匹配相对路径，因此可以用目录名当搜索词")
    void textMatchesRelativePath() {
        FileItem nested = file("某项目/collector/main.py", "py", FileKind.CODE, 100);
        assertTrue(match("某项目", nested), "应能按所在目录搜索");
        assertTrue(match("collector", nested));
        assertFalse(match("其他项目", nested));
    }

    @Test
    @DisplayName("引号内的空格不作为分隔符，可搜含空格的文件名")
    void quotedPhraseKeepsSpaces() {
        FileItem spaced = file("灰度发布 数据采集.xlsx", "xlsx", FileKind.SPREADSHEET, 100);
        assertTrue(match("\"灰度发布 数据\"", spaced), "引号内的空格应保留在关键词里");
        // 不加引号则被拆成两个条件，仍然同时满足（AND），所以这条也是 true
        assertTrue(match("灰度发布 数据", spaced));
        // 顺序反了且当整体短语时不应命中
        assertFalse(match("\"数据 灰度发布\"", spaced));
    }

    @Test
    @DisplayName("中文全角引号也能被识别")
    void acceptsFullWidthQuotes() {
        FileItem spaced = file("灰度发布 数据采集.xlsx", "xlsx", FileKind.SPREADSHEET, 100);
        assertTrue(match("“灰度发布 数据”", spaced));
    }

    // ------------------------------------------------------------ 通配符与扩展名

    @Test
    @DisplayName("通配符只匹配文件名整体")
    void globMatchesWholeName() {
        FileItem pdf = file("接口文档.pdf", "pdf", FileKind.DOCUMENT, 100);
        assertTrue(match("*.pdf", pdf));
        assertTrue(match("接口*", pdf));
        assertFalse(match("*.docx", pdf));
        assertFalse(match("文档*", pdf), "通配符不做子串匹配");
    }

    @Test
    @DisplayName("裸写的 .ext 当扩展名；但过长的点开头名字仍按文本处理")
    void bareExtensionHeuristic() {
        FileItem docx = file("报告.docx", "docx", FileKind.DOCUMENT, 100);
        assertTrue(match(".docx", docx), "短的点扩展名应作为扩展名筛选");

        FileItem gitignore = file(".gitignore", "", FileKind.OTHER, 1);
        assertTrue(match(".gitignore", gitignore), "长点开头名字应按文本匹配");
    }

    @Test
    @DisplayName("ext: 支持多个扩展名，兼容逗号、分号、点前缀与大小写")
    void extensionListVariants() {
        FileItem xlsx = file("记录.xlsx", "xlsx", FileKind.SPREADSHEET, 100);
        assertTrue(match("ext:xlsx", xlsx));
        assertTrue(match("ext:docx,XLSX", xlsx));
        assertTrue(match("ext:.xlsx;.csv", xlsx));
        assertFalse(match("ext:docx", xlsx));
    }

    // ------------------------------------------------------------ 类型

    @Test
    @DisplayName("type: 同时接受英文 key 与中文名")
    void kindAcceptsEnglishAndChinese() {
        FileItem png = file("图.png", "png", FileKind.IMAGE, 100);
        assertTrue(match("type:image", png));
        assertTrue(match("type:图片", png));
        assertTrue(match("type:image,文档", png));
        assertFalse(match("type:document", png));
    }

    // ------------------------------------------------------------ 大小

    @Test
    @DisplayName("大小比较：运算符与单位换算正确")
    void sizeComparisons() {
        FileItem big = file("大.rar", "rar", FileKind.ARCHIVE, 5L * 1024 * 1024);
        FileItem small = file("小.txt", "txt", FileKind.DOCUMENT, 512);

        assertTrue(match("size:>1MB", big));
        assertFalse(match("size:>10MB", big));
        assertTrue(match("size:>1MB", big));
        assertTrue(match("size:<1MB", small));
        assertTrue(match("size:<=512", small), "不带单位按字节");
        assertTrue(match("size:>=5MB", big));
        assertTrue(match("size:=5MB", big), "1024 进制下 5MB=5242880，与文件大小相等应命中");
        assertFalse(match("size:=6MB", big), "相等判断不应误命中");
        // 小数
        assertTrue(match("size:>4.5MB", big));
        assertTrue(match("size:>1.5M", big));
        // 中文单位
        assertTrue(match("size:>1兆", big));
    }

    @Test
    @DisplayName("运算符前后的空格不影响结果（用户习惯不该决定功能是否生效）")
    void operatorSpacingIsTolerated() {
        FileItem fiveMb = file("a.bin", "bin", FileKind.EXECUTABLE, 5L * 1024 * 1024);

        assertTrue(match("size:>1MB", fiveMb));
        assertTrue(match("size:> 1MB", fiveMb), "运算符后有空格");
        assertTrue(match("size: >1MB", fiveMb), "运算符前有空格");
        assertTrue(match("size : > 1MB", fiveMb), "冒号与运算符两侧都有空格");
        assertTrue(match("size: >= 1MB", fiveMb));

        // 归一化只动运算符周围，不应破坏其他条件
        assertTrue(match("a.bin size:> 1MB", fiveMb));
        assertFalse(match("b.bin size:> 1MB", fiveMb), "相邻条件仍应是 AND");
    }

    @Test
    @DisplayName("值内含空格时需要用引号（写入语法说明的取舍）")
    void valueWithSpaceRequiresQuotes() {
        FileItem fiveMb = file("a.bin", "bin", FileKind.EXECUTABLE, 5L * 1024 * 1024);

        // 未加引号时 "10 MB" 会被当成两个条件：这是“空格分隔多个条件”规则的必然结果
        assertFalse(match("size:>10 MB", fiveMb), "10 与 MB 被拆成两个词，无法组成完整值");
        // 引号包住之后整体成为一个值
        assertTrue(match("\"size:>1 MB\"", fiveMb), "引号内的空格应被保留在值里");
    }

    @Test
    @DisplayName("缺省运算符视为“大于等于”，符合直觉")
    void sizeDefaultOperatorIsGreaterOrEqual() {
        FileItem fiveMb = file("a.bin", "bin", FileKind.EXECUTABLE, 5L * 1024 * 1024);
        assertTrue(match("size:5MB", fiveMb));
        assertFalse(match("size:6MB", fiveMb));
    }

    @Test
    @DisplayName("目录不参与大小筛选（目录没有大小语义）")
    void sizeDoesNotMatchDirectories() {
        FileItem dir = new FileItem("子目录", Path.of("C:/root/子目录"), "子目录", "",
                true, "", FileKind.DIRECTORY, 0L, null, null, null, 0, false, false, false);
        assertFalse(match("size:>0", dir));
        assertFalse(match("size:<9999", dir));
    }

    // ------------------------------------------------------------ 时间

    @Test
    @DisplayName("修改时间：绝对日期")
    void modifiedAbsoluteDate() {
        FileItem old = file("旧.docx", "docx", FileKind.DOCUMENT, 1, LocalDate.of(2020, 1, 1));
        assertTrue(match("modified:<2026-01-01", old));
        assertFalse(match("modified:>2026-01-01", old));
        // 宽松写法（不补零）也要能解析
        assertTrue(match("modified:<2026-1-1", old));
    }

    @Test
    @DisplayName("修改时间：相对天数，且边界按当天计算")
    void modifiedRelativeDays() {
        FileItem today = file("今.docx", "docx", FileKind.DOCUMENT, 1, LocalDate.now());
        FileItem longAgo = file("旧.docx", "docx", FileKind.DOCUMENT, 1, LocalDate.now().minusDays(30));

        assertTrue(match("modified:>7d", today));
        assertFalse(match("modified:>7d", longAgo));
        assertTrue(match("modified:<7d", longAgo));
        assertTrue(match("modified:>1w", today), "周为单位");
        assertTrue(match("modified:>1m", today), "月为单位");
    }

    // ------------------------------------------------------------ 标签与收藏

    @Test
    @DisplayName("tag:/fav: 语法已就位；M2 阶段没有标签数据时自然匹配为空")
    void tagAndFavoriteSyntax() {
        FileItem item = file("a.docx", "docx", FileKind.DOCUMENT, 1);

        assertFalse(SearchParser.parse("tag:重要").matches(item, Set.of(), false),
                "没有标签数据时应匹配为空，而不是报错");
        assertTrue(SearchParser.parse("tag:重要").matches(item, Set.of("重要"), false));
        assertTrue(SearchParser.parse("tag:重要").matches(item, Set.of("重要", "工作"), false));
        assertFalse(SearchParser.parse("tag:重要").matches(item, Set.of("次要"), false));

        assertTrue(SearchParser.parse("fav:true").matches(item, Set.of(), true));
        assertFalse(SearchParser.parse("fav:true").matches(item, Set.of(), false));
        assertTrue(SearchParser.parse("fav:false").matches(item, Set.of(), false));
        assertTrue(SearchParser.parse("fav:1").matches(item, Set.of(), true));
    }

    // ------------------------------------------------------------ AND 语义

    @Test
    @DisplayName("多个条件是 AND：加条件只会更严格，不会更宽松")
    void multipleTermsAreAnded() {
        FileItem bigPng = file("大图.png", "png", FileKind.IMAGE, 5L * 1024 * 1024);
        FileItem smallPng = file("小图.png", "png", FileKind.IMAGE, 1024);
        FileItem bigDoc = file("大文档.docx", "docx", FileKind.DOCUMENT, 5L * 1024 * 1024);

        assertTrue(match("type:image size:>1MB", bigPng));
        assertFalse(match("type:image size:>1MB", smallPng), "大小不满足");
        assertFalse(match("type:image size:>1MB", bigDoc), "类型不满足");

        // 单调性：同一组数据下，条件越多命中越少
        long one = 0;
        long two = 0;
        for (FileItem item : List.of(bigPng, smallPng, bigDoc)) {
            if (match("type:image", item)) {
                one++;
            }
            if (match("type:image size:>1MB", item)) {
                two++;
            }
        }
        assertTrue(two <= one, "增加条件后命中数不应增加");
    }

    // ------------------------------------------------------------ 降级与健壮性

    @Test
    @DisplayName("无法解析的片段一律降级为文本匹配，绝不抛异常")
    void malformedInputNeverThrows() {
        FileItem item = file("size 说明.txt", "txt", FileKind.DOCUMENT, 10);

        // 一条条列出来，因为每一条都是用户实际会打出来的中间状态
        String[] inputs = {
                "size:", "size:>", "size:>abc", "size:>10XB", "size:>>1MB", "size:1MB2",
                "modified:", "modified:>", "modified:2026-13-99", "modified:>99999d",
                "type:", "type:不存在的类型", "ext:", "ext:,,,",
                "tag:", "fav:", "fav:maybe", ":foo", "a:b:c", "\"", "\"未闭合",
                "*", "**", "?", "  ", "", null,
        };
        for (String input : inputs) {
            SearchQuery query = SearchParser.parse(input);
            assertNotNull(query, "解析 " + input + " 不应返回 null");
            // 关键：调用 matches 不能抛异常
            query.matches(item, Set.of(), false);
            query.describe();
        }
    }

    @Test
    @DisplayName("打了一半的 size: 不应让结果突然清空")
    void halfTypedSizeFallsBackToText() {
        // 用户想搜名字里含 "size:>" 的文件很少见，但关键是：这条查询的语义是“文本包含”
        // 而不是“匹配失败”，因此结果可预期，不会出现“打了冒号就全没了”
        FileItem item = file("报告.docx", "docx", FileKind.DOCUMENT, 1);
        SearchQuery query = SearchParser.parse("size:>");
        assertFalse(query.isEmpty(), "不应被当成空查询（空查询会匹配一切）");
        assertTrue(query.terms().get(0) instanceof SearchQuery.TextTerm,
                "无法解析时应降级为文本词，实际 " + query.terms().get(0).getClass().getSimpleName());
        assertFalse(query.matches(item, Set.of(), false));
    }

    @Test
    @DisplayName("空查询匹配一切")
    void emptyQueryMatchesEverything() {
        FileItem item = file("a.txt", "txt", FileKind.DOCUMENT, 1);
        assertTrue(SearchParser.parse("").isEmpty());
        assertTrue(SearchParser.parse("   ").isEmpty());
        assertTrue(SearchParser.parse(null).isEmpty());
        assertTrue(SearchParser.parse("").matches(item, Set.of(), false));
        assertTrue(SearchParser.parse("").describe().isEmpty());
    }

    // ------------------------------------------------------------ 分词与描述

    @Test
    @DisplayName("分词：空白分隔，引号包裹整体")
    void tokenizeBehaviour() {
        assertEquals(List.of("a", "b"), SearchParser.tokenize("a b"));
        assertEquals(List.of("a b"), SearchParser.tokenize("\"a b\""));
        assertEquals(List.of("x", "a b", "y"), SearchParser.tokenize("x \"a b\" y"));
        assertEquals(List.of("a"), SearchParser.tokenize("   a   "));
    }

    @Test
    @DisplayName("describe() 用自然语言说明筛选条件")
    void describesTerms() {
        String described = SearchParser.parse("type:image size:>1MB *.png").describe();
        assertTrue(described.contains("类型"), described);
        assertTrue(described.contains("图片"), described);
        assertTrue(described.contains("大小"), described);
        assertTrue(described.contains("1.00 MB"), described);
        assertTrue(described.contains("*.png"), described);
        assertTrue(described.contains(" · "), "多个条件之间应可读地分隔：" + described);
    }

    @Test
    @DisplayName("用户输入原文被保留，便于界面回显")
    void rawIsPreserved() {
        assertEquals("type:image", SearchParser.parse("type:image").raw());
        assertEquals("", SearchParser.parse(null).raw());
    }

    @Test
    @DisplayName("单位换算表覆盖常见写法")
    void unitMultipliers() {
        assertEquals(1L, SearchParser.unitMultiplier(""));
        assertEquals(1L, SearchParser.unitMultiplier("B"));
        assertEquals(1L, SearchParser.unitMultiplier("字节"));
        assertEquals(1024L, SearchParser.unitMultiplier("K"));
        assertEquals(1024L, SearchParser.unitMultiplier("KB"));
        assertEquals(1024L * 1024, SearchParser.unitMultiplier("M"));
        assertEquals(1024L * 1024, SearchParser.unitMultiplier("MB"));
        assertEquals(1024L * 1024 * 1024, SearchParser.unitMultiplier("GB"));
        assertEquals(0L, SearchParser.unitMultiplier("XB"), "无法识别的单位返回 0 以便降级");
    }
}
