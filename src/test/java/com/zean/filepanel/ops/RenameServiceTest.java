package com.zean.filepanel.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重命名服务测试。
 *
 * <p>重点在 Windows 上那些"看起来能用、实际会出问题"的情况：
 * 保留设备名、结尾的点、仅大小写变化、忽略大小写的重名判断。
 * 这些在 Linux 上都不是问题，所以很容易漏测。
 */
class RenameServiceTest {

    // ------------------------------------------------------------ 语法校验

    @Test
    @DisplayName("合法名称通过校验")
    void acceptsValidNames() {
        assertTrue(RenameService.validateName("报告.docx").ok());
        assertTrue(RenameService.validateName("2026年度 总结").ok());
        assertTrue(RenameService.validateName("a").ok());
        assertTrue(RenameService.validateName(".gitignore").ok(), "点开头的文件应合法");
        assertTrue(RenameService.validateName("带.多个.点.txt").ok());
    }

    @Test
    @DisplayName("非法字符被拦截，并说明是哪些字符")
    void rejectsIllegalCharacters() {
        for (String bad : new String[]{"a<b", "a>b", "a:b", "a\"b", "a/b", "a\\b", "a|b", "a?b", "a*b"}) {
            RenameService.Validation result = RenameService.validateName(bad);
            assertFalse(result.ok(), "应拦截：" + bad);
            assertTrue(result.message().contains("<"), "应说明非法字符，实际：" + result.message());
        }
    }

    @Test
    @DisplayName("控制字符被拦截")
    void rejectsControlCharacters() {
        assertFalse(RenameService.validateName("a\u0001b").ok());
        assertFalse(RenameService.validateName("a\nb").ok());
        assertFalse(RenameService.validateName("a\tb").ok());
    }

    @Test
    @DisplayName("空名字、纯空格、过长名字被拦截")
    void rejectsEmptyAndTooLong() {
        assertFalse(RenameService.validateName("").ok());
        assertFalse(RenameService.validateName("   ").ok());
        assertFalse(RenameService.validateName(null).ok());
        assertFalse(RenameService.validateName("x".repeat(256)).ok());
        assertTrue(RenameService.validateName("x".repeat(255)).ok(), "255 个字符应合法");
    }

    @Test
    @DisplayName("结尾的点和空格被拦截（Windows 会静默丢弃它们，用户会以为没生效）")
    void rejectsTrailingDotOrSpace() {
        RenameService.Validation dot = RenameService.validateName("报告.");
        assertFalse(dot.ok());
        assertTrue(dot.message().contains("静默"), dot.message());

        RenameService.Validation space = RenameService.validateName("报告 ");
        assertFalse(space.ok());
        assertTrue(space.message().contains("静默"), space.message());
    }

    @Test
    @DisplayName("Windows 保留设备名被拦截，带扩展名也不行")
    void rejectsReservedDeviceNames() {
        for (String reserved : new String[]{"CON", "con", "PRN", "AUX", "NUL",
                "COM1", "com9", "LPT1", "lpt9", "CON.txt", "nul.log"}) {
            assertFalse(RenameService.validateName(reserved).ok(), "应拦截保留名：" + reserved);
        }
        assertTrue(RenameService.validateName("CONSOLE.txt").ok(), "只有完全匹配才算保留名");
        assertTrue(RenameService.validateName("MYCON.txt").ok());
    }

    @Test
    @DisplayName("「.」与「..」被拦截")
    void rejectsDotDirectories() {
        assertFalse(RenameService.validateName(".").ok());
        assertFalse(RenameService.validateName("..").ok());
    }

    // ------------------------------------------------------------ 文件名拆分

    @Test
    @DisplayName("拆分主名与扩展名，点开头的文件视为无扩展名")
    void splitsNameParts() {
        RenameService.NameParts normal = RenameService.split("报告.docx");
        assertEquals("报告", normal.base());
        assertEquals(".docx", normal.extension());
        assertTrue(normal.splittable());
        assertEquals("新报告.docx", normal.join("新报告"));

        RenameService.NameParts dotfile = RenameService.split(".gitignore");
        assertEquals(".gitignore", dotfile.base());
        assertEquals("", dotfile.extension());
        assertFalse(dotfile.splittable(), "点开头应整体可编辑，避免把 .gitignore 变成 .ignore");

        RenameService.NameParts noExt = RenameService.split("README");
        assertEquals("README", noExt.base());
        assertEquals("", noExt.extension());

        RenameService.NameParts multi = RenameService.split("archive.tar.gz");
        assertEquals("archive.tar", multi.base());
        assertEquals(".gz", multi.extension());
    }

    // ------------------------------------------------------------ 冲突判断

    @Test
    @DisplayName("同名目标被判为冲突；仅大小写变化不算冲突")
    void detectsConflicts(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("a.txt"), "x");
        Files.writeString(dir.resolve("b.txt"), "y");

        assertTrue(RenameService.validateTarget(source, dir.resolve("b.txt")).ok() == false,
                "已存在的不同文件名应冲突");
        assertTrue(RenameService.validateTarget(source, dir.resolve("A.TXT")).ok(),
                "仅大小写变化指向同一文件，应允许");
        assertTrue(RenameService.validateTarget(source, dir.resolve("c.txt")).ok());
        assertTrue(RenameService.validateTarget(source, source).ok(), "名字没变不算冲突");
    }

    @Test
    @DisplayName("isCaseOnlyChange 只对“仅大小写不同”返回 true")
    void detectsCaseOnlyChange(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("readme.md"), "x");

        assertTrue(RenameService.isCaseOnlyChange(source, "README.md"));
        assertTrue(RenameService.isCaseOnlyChange(source, "ReadMe.MD"));
        assertFalse(RenameService.isCaseOnlyChange(source, "readme.md"), "完全没变不算");
        assertFalse(RenameService.isCaseOnlyChange(source, "other.md"));
    }

    // ------------------------------------------------------------ 实际重命名

    @Test
    @DisplayName("普通重命名真的改了名字")
    void renamesFile(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("旧名字.txt"), "内容");

        RenameService.Result result = RenameService.rename(source, "新名字.txt");

        assertTrue(result.success(), result.error());
        assertFalse(Files.exists(source));
        assertTrue(Files.exists(dir.resolve("新名字.txt")));
        assertEquals("内容", Files.readString(dir.resolve("新名字.txt")), "内容不能变");
        assertFalse(result.caseOnlyChange());
    }

    @Test
    @DisplayName("仅大小写变化的重命名也能成功（需要两步走，直接 move 会因“已存在”失败）")
    void renamesCaseOnly(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("readme.md"), "内容");

        RenameService.Result result = RenameService.rename(source, "README.md");

        assertTrue(result.success(), result.error());
        assertTrue(result.caseOnlyChange());
        // 目录里应恰好剩一个文件，且名字是新的
        try (var stream = Files.list(dir)) {
            var names = stream.map(p -> p.getFileName().toString()).toList();
            assertEquals(1, names.size(), "不应留下临时文件，实际：" + names);
            assertEquals("README.md", names.get(0));
        }
        assertEquals("内容", Files.readString(dir.resolve("README.md")));
    }

    @Test
    @DisplayName("重命名到已存在的名字会被拒绝，且绝不覆盖目标文件")
    void refusesToOverwrite(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("a.txt"), "源内容");
        Path existing = Files.writeString(dir.resolve("b.txt"), "目标内容");

        RenameService.Result result = RenameService.rename(source, "b.txt");

        assertFalse(result.success());
        assertTrue(result.error().contains("同名"), result.error());
        // 两个文件都必须原样存在——宁可失败也不能悄悄覆盖
        assertEquals("源内容", Files.readString(source));
        assertEquals("目标内容", Files.readString(existing));
    }

    @Test
    @DisplayName("非法名字在真正执行前就被拒绝，文件保持不变")
    void refusesIllegalName(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("a.txt"), "内容");

        RenameService.Result result = RenameService.rename(source, "bad:name.txt");

        assertFalse(result.success());
        assertTrue(Files.exists(source), "失败时源文件必须原样保留");
        assertEquals("内容", Files.readString(source));
    }

    @Test
    @DisplayName("目录重命名可用，且扩展名逻辑不干扰目录")
    void renamesDirectory(@TempDir Path dir) throws IOException {
        Path sub = Files.createDirectory(dir.resolve("旧目录"));
        Files.writeString(sub.resolve("inner.txt"), "x");

        RenameService.Result result = RenameService.rename(sub, "新目录");

        assertTrue(result.success(), result.error());
        assertTrue(Files.isDirectory(dir.resolve("新目录")));
        assertEquals("x", Files.readString(dir.resolve("新目录").resolve("inner.txt")));
    }

    @Test
    @DisplayName("名字完全没变时直接成功返回，不做无谓的磁盘操作")
    void sameNameIsNoOp(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("a.txt"), "x");

        RenameService.Result result = RenameService.rename(source, "a.txt");

        assertTrue(result.success());
        assertEquals(source, result.newPath());
        assertTrue(Files.exists(source));
    }
}
