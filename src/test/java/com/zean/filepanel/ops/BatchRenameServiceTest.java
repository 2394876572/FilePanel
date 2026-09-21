package com.zean.filepanel.ops;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import com.zean.filepanel.store.RenameJournal;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两阶段执行、回滚与撤销。
 *
 * <p>本类是整个 M5 里最关键的测试：批量重命名是"一次操作改很多文件"的场景，
 * 一旦执行顺序错了或回滚不干净，用户会一次性丢掉一批文件。
 * 所以这里既验证正常路径，也<b>刻意制造失败</b>来验证回滚。
 */
class BatchRenameServiceTest {

    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 9, 20, 12, 0);

    private static FileItem item(Path path) {
        String name = path.getFileName().toString();
        String ext = FileItem.extensionOf(name);
        long size = 0;
        try {
            size = Files.size(path);
        } catch (IOException ignored) {
            // 大小取不到不影响改名
        }
        return new FileItem(name, path, name, "", false, ext, FileKind.ofExtension(ext),
                size, null, null, null, 0, false, false, false);
    }

    private static List<FileItem> itemsOf(Path... paths) {
        List<FileItem> list = new ArrayList<>(paths.length);
        for (Path p : paths) {
            list.add(item(p));
        }
        return list;
    }

    private static RenameRules numbering() {
        RenameRules rules = new RenameRules();
        rules.numbering = true;
        rules.numberDigits = 2;
        return rules;
    }

    private static RenameRules prefix(String value) {
        RenameRules rules = new RenameRules();
        rules.prefix = value;
        return rules;
    }

    /** 目录里不允许留下任何临时文件。 */
    private static void assertNoTempLeftovers(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            List<String> leftovers = stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.contains("filepanel-tmp")).toList();
            assertTrue(leftovers.isEmpty(), "不应残留临时文件，实际：" + leftovers);
        }
    }

    // ------------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("普通批量重命名：内容不变，只剩新名字")
    void renamesAll(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "AAA");
        Path b = Files.writeString(dir.resolve("b.txt"), "BBB");

        BatchRenamePlan plan = BatchRenamePlan.build(itemsOf(a, b), prefix("2026-"), FIXED);
        assertEquals(2, plan.actionableCount());

        BatchRenameService service = new BatchRenameService(new RenameJournal(dataDir));
        BatchRenameService.Outcome outcome = service.rename(plan.actionable(), "加前缀");

        assertTrue(outcome.success(), String.valueOf(outcome.problems()));
        assertEquals(2, outcome.succeededCount());
        assertEquals("AAA", Files.readString(dir.resolve("2026-a.txt")));
        assertEquals("BBB", Files.readString(dir.resolve("2026-b.txt")));
        assertFalse(Files.exists(a));
        assertNoTempLeftovers(dir);
    }

    @Test
    @DisplayName("【核心】A.txt 与 B.txt 互换名字，两边内容都不能丢")
    void swapsTwoFiles(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.txt"), "内容A");
        Path b = Files.writeString(dir.resolve("B.txt"), "内容B");

        // 用规则造出"互换"：借助临时模板无法直接表达，所以直接构造两条移动
        BatchRenameService service = new BatchRenameService(new RenameJournal(dataDir));
        BatchRenameService.Outcome outcome = service.rename(List.of(
                new BatchRenamePlan.Entry(item(a), "B.txt", dir.resolve("B.txt"),
                        BatchRenamePlan.Status.OK, ""),
                new BatchRenamePlan.Entry(item(b), "A.txt", dir.resolve("A.txt"),
                        BatchRenamePlan.Status.OK, "")), "互换 A/B");

        assertTrue(outcome.success(), String.valueOf(outcome.problems()));
        assertEquals("内容A", Files.readString(dir.resolve("B.txt")), "A 的内容应到 B");
        assertEquals("内容B", Files.readString(dir.resolve("A.txt")), "B 的内容应到 A");
        assertNoTempLeftovers(dir);
    }

    @Test
    @DisplayName("三个文件成环改名（A→B→C→A）也不丢内容")
    void swapsThreeFilesInCycle(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.txt"), "A");
        Path b = Files.writeString(dir.resolve("B.txt"), "B");
        Path c = Files.writeString(dir.resolve("C.txt"), "C");

        BatchRenameService.Outcome outcome = BatchRenameService.executeTwoPhase(List.of(
                new BatchRenameService.Move(a, dir.resolve("B.txt")),
                new BatchRenameService.Move(b, dir.resolve("C.txt")),
                new BatchRenameService.Move(c, dir.resolve("A.txt"))));

        assertTrue(outcome.success(), String.valueOf(outcome.problems()));
        assertEquals("C", Files.readString(dir.resolve("A.txt")));
        assertEquals("A", Files.readString(dir.resolve("B.txt")));
        assertEquals("B", Files.readString(dir.resolve("C.txt")));
        assertNoTempLeftovers(dir);
    }

    @Test
    @DisplayName("仅大小写改名也能成功（Windows 上目标「已存在」，必须靠两阶段）")
    void caseOnlyRenameWorks(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path lower = Files.writeString(dir.resolve("readme.md"), "内容");

        BatchRenameService.Outcome outcome = BatchRenameService.executeTwoPhase(
                List.of(new BatchRenameService.Move(lower, dir.resolve("README.md"))));

        assertTrue(outcome.success(), String.valueOf(outcome.problems()));
        try (var stream = Files.list(dir)) {
            List<String> names = stream.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("README.md"), names);
        }
        assertEquals("内容", Files.readString(dir.resolve("README.md")));
    }

    // ------------------------------------------------------------------ 失败与回滚

    @Test
    @DisplayName("【核心】第二阶段被目标占用而失败时，全部文件都回滚到原名，一个都不丢")
    void rollsBackWhenTargetBecomesOccupied(@TempDir Path dir, @TempDir Path dataDir)
            throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "内容A");
        Path b = Files.writeString(dir.resolve("b.txt"), "内容B");

        BatchRenamePlan plan = BatchRenamePlan.build(itemsOf(a, b), prefix("x-"), FIXED);
        assertEquals(2, plan.actionableCount());

        // 计划已生成、执行尚未开始：此时偷偷占住其中一个目标名，
        // 让第二阶段必然失败。这是模拟"计划与实际之间发生了变化"的真实场景。
        Files.writeString(dir.resolve("x-b.txt"), "占用者");

        BatchRenameService.Outcome outcome =
                new BatchRenameService(new RenameJournal(dataDir)).rename(plan.actionable(), "测试回滚");

        assertFalse(outcome.success(), "第二阶段必然失败");
        assertTrue(outcome.hasProblems());

        // 关键断言：两个源文件必须都回到原名且内容不变
        assertTrue(Files.exists(a), "a.txt 必须被回滚");
        assertTrue(Files.exists(b), "b.txt 必须被回滚");
        assertEquals("内容A", Files.readString(a));
        assertEquals("内容B", Files.readString(b));
        // 成功过的那个也不该留在新名字上
        assertFalse(Files.exists(dir.resolve("x-a.txt")), "已就位的文件也要搬回原名");
        // 占用者本身不能被牵连
        assertEquals("占用者", Files.readString(dir.resolve("x-b.txt")));
        assertNoTempLeftovers(dir);
    }

    @Test
    @DisplayName("源文件不存在时在准备阶段失败并整批取消，不产生半成品")
    void abortsWhenSourceMissing(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        Path missing = dir.resolve("missing.txt");

        BatchRenameService.Outcome outcome = BatchRenameService.executeTwoPhase(List.of(
                new BatchRenameService.Move(a, dir.resolve("a2.txt")),
                new BatchRenameService.Move(missing, dir.resolve("m2.txt"))));

        assertFalse(outcome.success());
        assertTrue(Files.exists(a), "已改名成功的那个也应被回滚");
        assertFalse(Files.exists(dir.resolve("a2.txt")));
        assertNoTempLeftovers(dir);
    }

    @Test
    @DisplayName("临时名会截断过长的文件名，避免超过文件系统上限导致必然失败")
    void tempNameIsTruncated() {
        String longName = "x".repeat(250) + ".txt";
        String temp = BatchRenameService.tempName(Path.of("C:/d/" + longName), "-tmp-abcdef-", 3);

        assertTrue(temp.length() <= 255, "临时名长度 " + temp.length() + " 超过上限");
        assertTrue(temp.endsWith("-tmp-abcdef-3"), temp);
    }

    // ------------------------------------------------------------------ 撤销

    @Test
    @DisplayName("撤销把一次批量改名完全还原")
    void undoRestoresEverything(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        Path b = Files.writeString(dir.resolve("b.txt"), "B");

        RenameJournal journal = new RenameJournal(dataDir);
        BatchRenameService service = new BatchRenameService(journal);

        BatchRenamePlan plan = BatchRenamePlan.build(itemsOf(a, b), prefix("2026-"), FIXED);
        assertTrue(service.rename(plan.actionable(), "加前缀").success());
        assertTrue(service.hasUndoable(), "成功后应可撤销");
        assertTrue(journal.describeLast().contains("加前缀"), journal.describeLast());

        BatchRenameService.Outcome undo = service.undoLast().orElseThrow();
        assertTrue(undo.success(), String.valueOf(undo.problems()));

        assertEquals("A", Files.readString(a), "原名与内容都应还原");
        assertEquals("B", Files.readString(b));
        assertFalse(Files.exists(dir.resolve("2026-a.txt")));
        assertFalse(service.hasUndoable(), "撤销后不应还能再撤销一次");
        assertNoTempLeftovers(dir);
    }

    @Test
    @DisplayName("【核心】撤销一次「互换名字」的操作也能还原（撤销本身也成环）")
    void undoSwap(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.txt"), "内容A");
        Path b = Files.writeString(dir.resolve("B.txt"), "内容B");

        RenameJournal journal = new RenameJournal(dataDir);
        BatchRenameService service = new BatchRenameService(journal);

        assertTrue(service.rename(List.of(
                new BatchRenamePlan.Entry(item(a), "B.txt", dir.resolve("B.txt"),
                        BatchRenamePlan.Status.OK, ""),
                new BatchRenamePlan.Entry(item(b), "A.txt", dir.resolve("A.txt"),
                        BatchRenamePlan.Status.OK, "")), "互换 A/B").success());

        assertEquals("内容A", Files.readString(dir.resolve("B.txt")));

        BatchRenameService.Outcome undo = service.undoLast().orElseThrow();
        assertTrue(undo.success(), String.valueOf(undo.problems()));
        assertEquals("内容A", Files.readString(a), "互换后撤销应回到最初的状态");
        assertEquals("内容B", Files.readString(b));
        assertNoTempLeftovers(dir);
    }

    @Test
    @DisplayName("没有可撤销的记录时返回空，不抛异常")
    void undoWithoutRecord(@TempDir Path dataDir) {
        BatchRenameService service = new BatchRenameService(new RenameJournal(dataDir));
        assertFalse(service.hasUndoable());
        assertTrue(service.undoLast().isEmpty());
        assertNotNull(service.describeUndoable());
    }

    @Test
    @DisplayName("失败的批量操作不写入撤销日志（否则会撤销到更早的操作上）")
    void failedBatchIsNotRecorded(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        Path b = Files.writeString(dir.resolve("b.txt"), "B");

        RenameJournal journal = new RenameJournal(dataDir);
        BatchRenameService service = new BatchRenameService(journal);
        BatchRenamePlan plan = BatchRenamePlan.build(itemsOf(a, b), prefix("x-"), FIXED);
        Files.writeString(dir.resolve("x-b.txt"), "占用者");

        assertFalse(service.rename(plan.actionable(), "会失败的操作").success());
        assertFalse(service.hasUndoable(), "失败的批次不应留下撤销记录");
    }

    @Test
    @DisplayName("撤销日志有容量上限，只保留最近若干次")
    void journalIsCapped(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        RenameJournal journal = new RenameJournal(dataDir);
        BatchRenameService service = new BatchRenameService(journal);

        for (int i = 0; i < RenameJournal.MAX_BATCHES + 5; i++) {
            Path file = Files.writeString(dir.resolve("f" + i + ".txt"), "x");
            BatchRenameService.Outcome outcome = service.rename(List.of(
                    new BatchRenamePlan.Entry(item(file), "r" + i + ".txt",
                            dir.resolve("r" + i + ".txt"), BatchRenamePlan.Status.OK, "")),
                    "第 " + i + " 次");
            assertTrue(outcome.success(), String.valueOf(outcome.problems()));
        }
        assertEquals(RenameJournal.MAX_BATCHES, journal.all().size());
    }

    @Test
    @DisplayName("撤销日志文件缺失时也能正常工作")
    void journalToleratesMissingFile(@TempDir Path dir, @TempDir Path dataDir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.txt"), "A");
        // 不预先创建任何文件，直接构造服务
        BatchRenameService service = new BatchRenameService(new RenameJournal(dataDir));

        assertTrue(service.rename(List.of(new BatchRenamePlan.Entry(item(a), "a2.txt",
                dir.resolve("a2.txt"), BatchRenamePlan.Status.OK, "")), "单次").success());
        assertTrue(Files.exists(dir.resolve("a2.txt")));
        assertTrue(service.undoLast().orElseThrow().success());
        assertTrue(Files.exists(a));
    }

    @Test
    @DisplayName("空输入返回空结果，不抛异常")
    void handlesEmptyInput(@TempDir Path dataDir) {
        BatchRenameService service = new BatchRenameService(new RenameJournal(dataDir));
        assertTrue(service.rename(List.of(), "空").success());
        assertTrue(service.rename(null, "空").success());
        assertTrue(BatchRenameService.executeTwoPhase(List.of()).success());
    }
}
