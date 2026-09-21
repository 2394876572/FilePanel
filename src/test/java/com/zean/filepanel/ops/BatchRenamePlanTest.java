package com.zean.filepanel.ops;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量重命名预览与冲突检测。
 *
 * <p>预览是"按下执行之前唯一能看清后果的地方"，所以判错冲突的代价很直接：
 * 要么把本来能改的判成冲突（用户莫名其妙改不了），
 * 要么把真正会互相覆盖的判成没问题（执行时丢文件）。
 */
class BatchRenamePlanTest {

    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 9, 20, 12, 0);

    private static FileItem item(Path path) {
        String name = path.getFileName().toString();
        String ext = FileItem.extensionOf(name);
        return new FileItem(name, path, name, "", false, ext, FileKind.ofExtension(ext),
                1, null, null, null, 0, false, false, false);
    }

    private static List<FileItem> itemsOf(Path... paths) {
        List<FileItem> list = new ArrayList<>(paths.length);
        for (Path p : paths) {
            list.add(item(p));
        }
        return list;
    }

    private static BatchRenamePlan plan(List<FileItem> items, RenameRules rules, LocalDateTime now) {
        return BatchRenamePlan.build(items, rules, now);
    }

    private static RenameRules prefix(String value) {
        RenameRules rules = new RenameRules();
        rules.prefix = value;
        return rules;
    }

    private static BatchRenamePlan.Entry entryOf(BatchRenamePlan plan, String originalName) {
        return plan.entries().stream()
                .filter(e -> e.item().name().equals(originalName))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ 基本

    @Test
    @DisplayName("正常规则下所有行都可执行")
    void normalPlanIsActionable(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        Path b = Files.writeString(dir.resolve("b.txt"), "B");

        BatchRenamePlan plan = plan(itemsOf(a, b), prefix("2026-"), FIXED);

        assertEquals(2, plan.size());
        assertEquals(2, plan.actionableCount());
        assertEquals(0, plan.problemCount());
        assertFalse(plan.hasProblems());
        assertEquals("2026-a.txt", entryOf(plan, "a.txt").newName());
        assertTrue(plan.summary().contains("将重命名 2 项"), plan.summary());
    }

    @Test
    @DisplayName("新名与原名完全相同时标为「无变化」，不计入执行")
    void identicalIsUnchanged(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");

        BatchRenamePlan plan = plan(itemsOf(a), new RenameRules(), FIXED);

        assertEquals(BatchRenamePlan.Status.UNCHANGED, entryOf(plan, "a.txt").status());
        assertEquals(0, plan.actionableCount());
        assertFalse(plan.hasProblems(), "无变化不是错误");
        assertTrue(plan.summary().contains("1 项无变化"), plan.summary());
    }

    @Test
    @DisplayName("【关键】仅大小写变化必须算「将重命名」，不能算「无变化」")
    void caseOnlyChangeIsActionable(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("report.docx"), "A");

        // 路径归一化在 Windows 上忽略大小写，若用它判断"有没有变化"，
        // 这个操作会被静默判成"无变化"——用户点了执行却什么都没发生
        RenameRules rules = new RenameRules();
        rules.extensionMode = RenameRules.ExtensionMode.UPPER;

        BatchRenamePlan plan = plan(itemsOf(a), rules, FIXED);

        assertEquals(BatchRenamePlan.Status.OK, entryOf(plan, "report.docx").status(),
                "仅扩展名大小写变化是一次真实的改动");
        assertEquals("report.DOCX", entryOf(plan, "report.docx").newName());
        assertTrue(entryOf(plan, "report.docx").caseOnlyChange());
    }

    // ------------------------------------------------------------------ 冲突

    @Test
    @DisplayName("【关键】目标名恰好是批次内另一个源文件时，不算冲突")
    void targetInsideBatchIsNotConflict(@TempDir Path dir) throws IOException {
        // a.txt 想改成 x-a.txt，而 x-a.txt 已存在 —— 看起来像冲突，
        // 但 x-a.txt 本身也在这批里、会被改成 x-x-a.txt 先腾出位置。
        // 两阶段执行先全部改成临时名，所以这是安全的。
        // 这正是 A.txt ⇄ B.txt 互换能成立的原因。
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        Path existing = Files.writeString(dir.resolve("x-a.txt"), "X");

        BatchRenamePlan plan = plan(itemsOf(a, existing), prefix("x-"), FIXED);

        assertEquals(0, plan.problemCount(),
                "目标在批次内不应判冲突，实际：" + plan.entries());
        assertEquals(2, plan.actionableCount());
        assertEquals("x-a.txt", entryOf(plan, "a.txt").newName());
        assertEquals("x-x-a.txt", entryOf(plan, "x-a.txt").newName());
    }

    @Test
    @DisplayName("对照组：目标存在且不在批次内时，才判为冲突")
    void sameSetupButTargetOutsideBatchIsConflict(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        Files.writeString(dir.resolve("x-a.txt"), "X");

        // 只选中 a.txt：此时 x-a.txt 不在批次里，没人会腾出位置
        BatchRenamePlan plan = plan(itemsOf(a), prefix("x-"), FIXED);

        assertEquals(1, plan.problemCount());
        assertEquals(BatchRenamePlan.Status.CONFLICT, entryOf(plan, "a.txt").status());
    }

    @Test
    @DisplayName("目标与批次外的现有文件重名 → 冲突")
    void conflictWithExistingFileOutsideBatch(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        Files.writeString(dir.resolve("2026-a.txt"), "已存在");

        BatchRenamePlan plan = plan(itemsOf(a), prefix("2026-"), FIXED);

        BatchRenamePlan.Entry entry = entryOf(plan, "a.txt");
        assertEquals(BatchRenamePlan.Status.CONFLICT, entry.status());
        assertTrue(entry.message().contains("已存在"), entry.message());
        assertEquals(0, plan.actionableCount());
        assertTrue(plan.hasProblems());
    }

    @Test
    @DisplayName("多项撞到同一个名字 → 全部标为冲突")
    void duplicateTargetsAreConflicts(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        Path b = Files.writeString(dir.resolve("b.txt"), "B");

        // 前缀 + 去掉原名：两条都变成 "same.txt"
        RenameRules rules = new RenameRules();
        rules.useRegex = true;
        rules.find = ".*";
        rules.replace = "same";

        BatchRenamePlan plan = plan(itemsOf(a, b), rules, FIXED);

        assertEquals(2, plan.problemCount());
        assertEquals(0, plan.actionableCount());
        assertTrue(entryOf(plan, "a.txt").message().contains("同一个名字"),
                entryOf(plan, "a.txt").message());
        assertTrue(entryOf(plan, "b.txt").message().contains("同一个名字"));
    }

    @Test
    @DisplayName("新名含非法字符 → 标为不合法，并给出原因")
    void illegalNameIsFlagged(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");

        RenameRules rules = new RenameRules();
        rules.prefix = "bad:name";

        BatchRenamePlan plan = plan(itemsOf(a), rules, FIXED);

        BatchRenamePlan.Entry entry = entryOf(plan, "a.txt");
        assertEquals(BatchRenamePlan.Status.ILLEGAL, entry.status());
        assertTrue(entry.message().contains("非法"), entry.message());
        assertTrue(plan.hasProblems());
    }

    @Test
    @DisplayName("Windows 保留设备名在预览阶段就被拦下")
    void reservedNameIsFlagged(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");

        RenameRules rules = new RenameRules();
        rules.template = "CON";

        BatchRenamePlan plan = plan(itemsOf(a), rules, FIXED);
        assertEquals(BatchRenamePlan.Status.ILLEGAL, entryOf(plan, "a.txt").status());
    }

    @Test
    @DisplayName("空输入与 null 规则返回空方案，不抛异常")
    void handlesEmptyInput() {
        assertTrue(BatchRenamePlan.build(List.of(), new RenameRules(), FIXED).isEmpty());
        assertTrue(BatchRenamePlan.build(null, new RenameRules(), FIXED).isEmpty());

        BatchRenamePlan noRules = BatchRenamePlan.build(
                List.of(item(Path.of("C:/demo/a.txt"))), null, FIXED);
        assertTrue(noRules.isEmpty());
        assertTrue(noRules.summary().contains("没有可重命名"));
    }

    @Test
    @DisplayName("actionable 只返回可执行的行，供「跳过有问题项」使用")
    void actionableFiltersOutProblems(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        Path b = Files.writeString(dir.resolve("b.txt"), "B");
        Files.writeString(dir.resolve("2026-b.txt"), "占用");

        // a 能改，b 会冲突
        RenameRules rules = new RenameRules();
        rules.template = "2026-{name}";

        BatchRenamePlan plan = BatchRenamePlan.build(itemsOf(a, b), rules, FIXED);

        assertEquals(1, plan.actionableCount());
        assertEquals(List.of("a.txt"), plan.actionable().stream()
                .map(e -> e.item().name()).toList());
        assertTrue(plan.hasProblems());
        assertTrue(plan.summary().contains("1 项有问题"), plan.summary());
    }
}
