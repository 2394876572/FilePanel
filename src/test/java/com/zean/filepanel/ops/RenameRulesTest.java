package com.zean.filepanel.ops;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 批量重命名规则引擎。规则算错会让用户看到"预览是对的、执行出来不对"，所以逐条钉死。 */
class RenameRulesTest {

    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 9, 20, 15, 30, 45);

    private static FileItem file(String name) {
        return new FileItem(name, Path.of("C:/demo/" + name), name, "",
                false, FileItem.extensionOf(name), FileKind.ofExtension(FileItem.extensionOf(name)),
                1, null, null, null, 0, false, false, false);
    }

    private static FileItem dir(String name) {
        return new FileItem(name, Path.of("C:/demo/" + name), name, "",
                true, "", FileKind.DIRECTORY, 0, null, null, null, 0, false, false, false);
    }

    private static String gen(RenameRules rules, String name, int index) {
        return rules.generate(file(name), index, FIXED);
    }

    // ------------------------------------------------------------ 查找替换

    @Test
    @DisplayName("字面量查找替换，扩展名不受影响")
    void literalReplace() {
        RenameRules rules = new RenameRules();
        rules.find = "2025";
        rules.replace = "2026";

        assertEquals("会议记录2026.docx", gen(rules, "会议记录2025.docx", 0));
        assertEquals("2026.docx", gen(rules, "2025.docx", 0));
    }

    @Test
    @DisplayName("忽略大小写的字面量替换：替换串里的 $ 与 \\ 不被当成特殊字符")
    void literalReplaceIgnoresCaseAndSpecialChars() {
        RenameRules rules = new RenameRules();
        rules.find = "abc";
        rules.replace = "$1\\n";   // 在 replaceAll 里 $1 与 \n 都有特殊含义

        assertEquals("$1\\n-def.TXT", gen(rules, "ABC-def.TXT", 0));
    }

    @Test
    @DisplayName("正则查找替换支持捕获组")
    void regexReplaceWithGroups() {
        RenameRules rules = new RenameRules();
        rules.useRegex = true;
        rules.find = "(\\d{4})-(\\d{2})-(\\d{2})";
        rules.replace = "$1年$2月$3日";

        assertEquals("报告2026年09月20日.docx", gen(rules, "报告2026-09-20.docx", 0));
    }

    @Test
    @DisplayName("正则写到一半（语法错误）时原样返回，而不是抛异常打断预览")
    void brokenRegexIsSafe() {
        RenameRules rules = new RenameRules();
        rules.useRegex = true;
        rules.find = "(未闭合";
        rules.replace = "x";

        assertEquals("报告.docx", gen(rules, "报告.docx", 0));
    }

    @Test
    @DisplayName("区分大小写开关生效")
    void caseSensitiveToggle() {
        RenameRules rules = new RenameRules();
        rules.find = "ABC";
        rules.replace = "x";
        rules.caseSensitive = true;
        assertEquals("abc-def.txt", gen(rules, "abc-def.txt", 0));
        assertEquals("x-def.txt", gen(rules, "ABC-def.txt", 0));

        RenameRules insensitive = new RenameRules();
        insensitive.find = "ABC";
        insensitive.replace = "x";
        assertEquals("x-def.txt", gen(insensitive, "abc-def.txt", 0));
    }

    // ------------------------------------------------------------ 前后缀与序号

    @Test
    @DisplayName("前缀后缀")
    void prefixAndSuffix() {
        RenameRules rules = new RenameRules();
        rules.prefix = "2026-";
        rules.suffix = "-定稿";

        assertEquals("2026-报告-定稿.docx", gen(rules, "报告.docx", 0));
    }

    @Test
    @DisplayName("序号：位数、起始值、步长、位置")
    void numbering() {
        RenameRules rules = new RenameRules();
        rules.numbering = true;
        rules.numberStart = 1;
        rules.numberStep = 2;
        rules.numberDigits = 3;
        rules.numberPosition = RenameRules.NumberPosition.AFTER;

        assertEquals("报告-001.docx", gen(rules, "报告.docx", 0));
        assertEquals("报告-003.docx", gen(rules, "报告.docx", 1));
        assertEquals("报告-005.docx", gen(rules, "报告.docx", 2));

        rules.numberPosition = RenameRules.NumberPosition.BEFORE;
        assertEquals("001-报告.docx", gen(rules, "报告.docx", 0));
    }

    @Test
    @DisplayName("序号位数不足以容纳数值时自动加宽，不截断")
    void numberingWidensWhenNeeded() {
        RenameRules rules = new RenameRules();
        rules.numbering = true;
        rules.numberDigits = 2;
        assertEquals("照片-100.png", gen(rules, "照片.png", 99), "100 不能被截成 10");
    }

    // ------------------------------------------------------------ 大小写

    @Test
    @DisplayName("主名大小写转换：小写、大写、每词首字母大写")
    void caseModes() {
        RenameRules rules = new RenameRules();

        rules.caseMode = RenameRules.CaseMode.LOWER;
        assertEquals("safety-valve report.docx", gen(rules, "Safety-Valve Report.docx", 0));

        rules.caseMode = RenameRules.CaseMode.UPPER;
        assertEquals("SAFETY-VALVE REPORT.docx", gen(rules, "Safety-Valve Report.docx", 0));

        rules.caseMode = RenameRules.CaseMode.TITLE;
        assertEquals("Safety-Valve Report.docx", gen(rules, "safety-valve report.docx", 0));
    }

    @Test
    @DisplayName("扩展名单独处理，不影响主名")
    void extensionMode() {
        RenameRules rules = new RenameRules();
        rules.extensionMode = RenameRules.ExtensionMode.UPPER;
        assertEquals("报告.DOCX", gen(rules, "报告.docx", 0));

        rules.extensionMode = RenameRules.ExtensionMode.LOWER;
        assertEquals("报告.docx", gen(rules, "报告.DOCX", 0));
    }

    @Test
    @DisplayName("扩展名处理不会误伤没有扩展名的文件与点开头的文件")
    void extensionModeOnEdgeCases() {
        RenameRules rules = new RenameRules();
        rules.extensionMode = RenameRules.ExtensionMode.UPPER;

        assertEquals("README", gen(rules, "README", 0));
        assertEquals(".gitignore", gen(rules, ".gitignore", 0), "点开头应视为无扩展名");
    }

    // ------------------------------------------------------------ 模板

    @Test
    @DisplayName("模板变量：原名、扩展名、序号、日期与时间")
    void templateVariables() {
        RenameRules rules = new RenameRules();
        rules.template = "{name}-{date}-{n}";

        assertEquals("报告-2026-09-20-1.docx", gen(rules, "报告.docx", 0));
        assertEquals("报告-2026-09-20-2.docx", gen(rules, "报告.docx", 1));

        rules.template = "{yyyy}{mm}{dd}-{name}.{ext}";
        assertEquals("20260920-报告.docx", gen(rules, "报告.docx", 0));

        rules.template = "备份-{time}";
        assertEquals("备份-153045.txt", gen(rules, "a.txt", 0));
    }

    @Test
    @DisplayName("模板里的序号位数可用 {nn}/{nnn} 指定")
    void templateNumberPadding() {
        RenameRules rules = new RenameRules();
        rules.template = "IMG_{nnn}";

        assertEquals("IMG_001.jpg", gen(rules, "a.jpg", 0));
        assertEquals("IMG_012.jpg", gen(rules, "a.jpg", 11));

        rules.template = "IMG_{nn}";
        assertEquals("IMG_03.jpg", gen(rules, "a.jpg", 2));
    }

    @Test
    @DisplayName("不认识的变量原样保留，用户一眼能看出写错了")
    void unknownTemplateVariableIsKept() {
        RenameRules rules = new RenameRules();
        rules.template = "{name}-{typo}";

        assertEquals("报告-{typo}.docx", gen(rules, "报告.docx", 0),
                "保留原文比替换成空串更容易发现问题");
    }

    @Test
    @DisplayName("模板模式下未闭合的花括号原样保留，不抛异常")
    void unclosedBraceIsSafe() {
        RenameRules rules = new RenameRules();
        rules.template = "{name}-{date";

        assertEquals("报告-{date.docx", gen(rules, "报告.docx", 0));
    }

    // ------------------------------------------------------------ 其他

    @Test
    @DisplayName("目录没有扩展名概念，整名参与规则")
    void directoryHasNoExtension() {
        RenameRules rules = new RenameRules();
        rules.prefix = "归档-";

        FileItem directory = dir("技术方案");
        assertEquals("归档-技术方案", rules.generate(directory, 0, FIXED));
    }

    @Test
    @DisplayName("hasAnyRule 能识别「什么都没设置」")
    void detectsEmptyRules() {
        RenameRules rules = new RenameRules();
        assertFalse(rules.hasAnyRule());
        assertTrue(rules.describe().contains("尚未设置"));

        rules.prefix = "x";
        assertTrue(rules.hasAnyRule());

        RenameRules templateOnly = new RenameRules();
        templateOnly.template = "{name}";
        assertTrue(templateOnly.hasAnyRule());
    }

    @Test
    @DisplayName("规则摘要能说清在做什么")
    void describesRules() {
        RenameRules rules = new RenameRules();
        rules.prefix = "2026-";
        rules.numbering = true;
        rules.numberDigits = 2;
        rules.extensionMode = RenameRules.ExtensionMode.UPPER;

        String text = rules.describe();
        assertTrue(text.contains("2026-"), text);
        assertTrue(text.contains("序号"), text);
        assertTrue(text.contains("扩展名"), text);
    }

    @Test
    @DisplayName("规则按顺序叠加：查找替换 → 前后缀 → 序号 → 大小写")
    void rulesComposeInOrder() {
        RenameRules rules = new RenameRules();
        rules.find = "v1";
        rules.replace = "v2";
        rules.prefix = "P-";
        rules.suffix = "-S";
        rules.numbering = true;
        rules.numberDigits = 2;
        rules.caseMode = RenameRules.CaseMode.UPPER;
        rules.extensionMode = RenameRules.ExtensionMode.UPPER;

        // v1 -> v2 ; 加前缀 P- ; 加后缀 -S ; 加序号 -01 ; 最后整体大写
        assertEquals("P-V2-S-01.DOCX", gen(rules, "v1.docx", 0));
    }
}
