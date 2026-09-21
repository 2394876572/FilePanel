package com.zean.filepanel.ops;

import com.zean.filepanel.core.DeletePolicy;
import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import com.zean.filepanel.store.DeleteJournal;
import com.zean.filepanel.win.Shell32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 永久删除路径的测试。
 *
 * <p>刻意<b>不</b>在这里测回收站：把文件送进回收站会改变用户系统的真实状态
 * （回收站里多出条目），不适合每次构建都跑。回收站的行为由
 * {@link RecycleBinIntegrationTest} 在显式开启时才验证。
 *
 * <p>本类全部在 {@code @TempDir} 内操作，永远不会碰到用户的真实文件。
 */
class DeleteServiceTest {

    private static FileItem item(Path path, boolean directory) {
        String name = path.getFileName().toString();
        String ext = directory ? "" : FileItem.extensionOf(name);
        long size = 0;
        try {
            if (!directory && Files.exists(path)) {
                size = Files.size(path);
            }
        } catch (IOException ignored) {
            // 大小取不到就按 0 处理，不影响删除本身
        }
        return new FileItem(name, path, name, "", directory, ext,
                directory ? FileKind.DIRECTORY : FileKind.ofExtension(ext),
                size, null, null, null, 0, false, false, false);
    }

    private static DeleteService service(Path dataDir) {
        return new DeleteService(new DeletePolicy(), new DeleteJournal(dataDir));
    }

    @Test
    @DisplayName("永久删除单个文件")
    void deletesFilePermanently(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        Path file = Files.writeString(root.resolve("a.txt"), "内容");
        FileItem target = item(file, false);

        DeleteService.Outcome outcome = service(dataDir).execute(List.of(target),
                DeleteService.Mode.ALL_PERMANENT);

        assertEquals(1, outcome.succeeded());
        assertEquals(1, outcome.deleted().size());
        assertTrue(outcome.hasPermanent());
        assertFalse(outcome.hasFailures());
        assertFalse(Files.exists(file), "文件应已不存在");
    }

    @Test
    @DisplayName("永久删除整棵目录树")
    void deletesDirectoryTree(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        Path sub = Files.createDirectories(root.resolve("dir/inner"));
        Files.writeString(sub.resolve("deep.txt"), "x");
        Files.writeString(root.resolve("dir/top.txt"), "y");

        DeleteService.Outcome outcome = service(dataDir).execute(
                List.of(item(root.resolve("dir"), true)), DeleteService.Mode.ALL_PERMANENT);

        assertFalse(outcome.hasFailures(), String.valueOf(outcome.failures()));
        assertEquals(1, outcome.deleted().size());
        assertFalse(Files.exists(root.resolve("dir")), "整棵目录树应被删除");
        assertTrue(Files.exists(root), "父目录不能被牵连删除");
    }

    @Test
    @DisplayName("不存在的路径被如实报告为失败，而不是假装删除成功")
    void missingPathIsReportedAsFailure(@TempDir Path root, @TempDir Path dataDir) {
        Path missing = root.resolve("never-existed.txt");

        DeleteService.Outcome outcome = service(dataDir).execute(
                List.of(item(missing, false)), DeleteService.Mode.ALL_PERMANENT);

        assertEquals(0, outcome.succeeded(), "什么都没删就不该报成功");
        assertEquals(1, outcome.failures().size());
        assertTrue(outcome.failures().get(0).message().contains("不存在"),
                outcome.failures().get(0).message());
    }

    @Test
    @DisplayName("永久删除会写入日志，且标记为不可恢复")
    void journalRecordsPermanentDeletes(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        Path file = Files.writeString(root.resolve("重要.txt"), "内容");
        DeleteJournal journal = new DeleteJournal(dataDir);

        new DeleteService(new DeletePolicy(), journal)
                .execute(List.of(item(file, false)), DeleteService.Mode.ALL_PERMANENT);

        List<DeleteJournal.Entry> entries = journal.read();
        assertEquals(1, entries.size());
        DeleteJournal.Entry entry = entries.get(0);
        assertTrue(entry.path().endsWith("重要.txt"), entry.path());
        assertEquals("PERMANENT", entry.mode());
        assertFalse(entry.recoverable(), "永久删除的日志条目应标记为不可恢复");
        assertFalse(entry.time().isBlank(), "应记录时间");
    }

    @Test
    @DisplayName("空输入返回空结果，不抛异常")
    void handlesEmptyInput(@TempDir Path dataDir) {
        DeleteService service = service(dataDir);

        assertTrue(service.execute(List.of(), DeleteService.Mode.POLICY).succeeded() == 0);
        assertTrue(service.execute(null, DeleteService.Mode.POLICY).succeeded() == 0);
    }

    @Test
    @DisplayName("模式为 POLICY 时按策略分流：超过阈值的走永久删除分支")
    void policyModeSplitsBySize(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        // 注意：DeletePolicy 会把阈值下限钳到 1MB（防止配置损坏成 0 导致所有删除都变永久），
        // 所以这里用 1MB 作为阈值，并用一个明确超过它的文件来触发永久删除分支。
        // 这样测试不会触碰回收站，也就不会改动用户系统的真实状态。
        DeletePolicy policy = new DeletePolicy(DeletePolicy.MIN_THRESHOLD_BYTES, false);
        Path small = Files.writeString(root.resolve("small.txt"), "x");
        Path big = Files.write(root.resolve("big.bin"), new byte[(int) DeletePolicy.MIN_THRESHOLD_BYTES + 1024]);

        DeleteService service = new DeleteService(policy, new DeleteJournal(dataDir));
        DeleteService.Outcome outcome = service.execute(
                List.of(item(big, false)), DeleteService.Mode.POLICY);

        assertEquals(1, outcome.deleted().size(), "超过阈值的文件应被永久删除");
        assertTrue(Files.exists(small), "未被选中的文件不能受影响");
        assertFalse(Files.exists(big));
    }

    @Test
    @DisplayName("日志读取在文件缺失或损坏时返回空列表，不影响删除流程")
    void journalIsFaultTolerant(@TempDir Path dataDir) throws IOException {
        DeleteJournal journal = new DeleteJournal(dataDir);
        assertTrue(journal.read().isEmpty(), "文件不存在时应返回空列表");

        Files.createDirectories(dataDir);
        Files.writeString(journal.file(), "{ 坏掉的 JSON");
        assertTrue(journal.read().isEmpty(), "损坏时应返回空列表而不是抛异常");

        // 仍然可以继续追加（会覆盖坏文件）
        journal.append(List.of(DeleteJournal.Entry.of(Path.of("C:/x.txt"), 1, false, "PERMANENT")));
        assertEquals(1, journal.read().size());
    }

    @Test
    @DisplayName("日志有容量上限，避免无限增长")
    void journalIsCapped(@TempDir Path dataDir) {
        DeleteJournal journal = new DeleteJournal(dataDir);
        for (int i = 0; i < DeleteJournal.MAX_ENTRIES + 50; i++) {
            journal.append(List.of(DeleteJournal.Entry.of(
                    Path.of("C:/f" + i + ".txt"), 1, false, "PERMANENT")));
        }

        List<DeleteJournal.Entry> entries = journal.read();
        assertEquals(DeleteJournal.MAX_ENTRIES, entries.size());
        // 保留的应是最近的，最后一条是最后写入的那条
        assertTrue(entries.get(entries.size() - 1).path().endsWith("f"
                + (DeleteJournal.MAX_ENTRIES + 49) + ".txt"));
    }

    @Test
    @DisplayName("回收站调用报告成功、但条目数没有增加时，必须标记为“未能确认进入回收站”")
    void marksUnverifiedRecycleWhenBinCountDoesNotGrow(@TempDir Path root, @TempDir Path dataDir)
            throws IOException {
        Path file = Files.writeString(root.resolve("a.txt"), "内容");
        FileItem target = item(file, false);

        // 这个假实现模拟实测到的真实行为：Shell 返回成功、文件确实消失，
        // 但回收站条目数完全没变（文件其实被永久删除了）。
        DeleteService service = new DeleteService(new DeletePolicy(), new DeleteJournal(dataDir),
                paths -> {
                    paths.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 假实现里忽略
                        }
                    });
                    return new Shell32.Result(true, false, 0, "");
                },
                () -> 7L);

        DeleteService.Outcome outcome = service.execute(List.of(target), DeleteService.Mode.ALL_RECYCLE);

        assertEquals(1, outcome.recycled().size());
        assertTrue(outcome.hasUnverifiedRecycle(),
                "条目数没增加就不能声称“已放入回收站、可以还原”");
        assertEquals(1, outcome.recycleUnverified().size());
        assertFalse(Files.exists(file));
    }

    @Test
    @DisplayName("回收站条目数确实增加时，不误报可疑")
    void acceptsVerifiedRecycle(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        Path file = Files.writeString(root.resolve("a.txt"), "内容");
        ItemHolder holder = new ItemHolder(item(file, false));

        // 条目数在删除动作里 +1，前后各查一次 => +1，判定为“确实进了回收站”
        java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong(3);
        DeleteService service = new DeleteService(new DeletePolicy(), new DeleteJournal(dataDir),
                paths -> {
                    paths.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 假实现里忽略
                        }
                    });
                    count.incrementAndGet();
                    return new Shell32.Result(true, false, 0, "");
                },
                count::get);

        DeleteService.Outcome outcome = service.execute(List.of(holder.item),
                DeleteService.Mode.ALL_RECYCLE);

        assertEquals(1, outcome.recycled().size());
        assertFalse(outcome.hasUnverifiedRecycle(), "条目数已增加，不应报可疑");
        assertFalse(Files.exists(file));
    }

    @Test
    @DisplayName("追查不了回收站条目数（探针返回负数）时保持沉默，不误报")
    void silentWhenProbeUnavailable(@TempDir Path root, @TempDir Path dataDir) throws IOException {
        Path file = Files.writeString(root.resolve("a.txt"), "内容");
        FileItem target = item(file, false);

        DeleteService service = new DeleteService(new DeletePolicy(), new DeleteJournal(dataDir),
                paths -> {
                    paths.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 假实现里忽略
                        }
                    });
                    return new Shell32.Result(true, false, 0, "");
                },
                () -> -1L);

        DeleteService.Outcome outcome = service.execute(List.of(target), DeleteService.Mode.ALL_RECYCLE);

        assertEquals(1, outcome.recycled().size());
        assertFalse(outcome.hasUnverifiedRecycle(),
                "查不到条目数时应保持沉默：无法判定的事情不该当成失败来报警");
    }

    /** 小包装，避免在 lambda 里捕获非 final 变量。 */
    private static final class ItemHolder {
        private final FileItem item;

        private ItemHolder(FileItem item) {
            this.item = item;
        }
    }

    @Test
    @DisplayName("删除方案的中文描述能区分三类情况（界面直接展示给用户）")
    void describesPlan() {
        // 阈值有 1MB 下限，因此构造数据时必须跨过 1MB 这个界，否则三类情况会退化成两类
        DeletePolicy policy = new DeletePolicy(DeletePolicy.MIN_THRESHOLD_BYTES, false);
        FileItem small = new FileItem("s.txt", Path.of("C:/s.txt"), "s.txt", "", false, "txt",
                FileKind.DOCUMENT, 10, null, null, null, 0, false, false, false);
        FileItem big = new FileItem("b.bin", Path.of("C:/b.bin"), "b.bin", "", false, "bin",
                FileKind.EXECUTABLE, DeletePolicy.MIN_THRESHOLD_BYTES * 5, null, null, null, 0, false, false, false);

        String recyclable = DeleteService.describePlan(policy.plan(List.of(small)), policy);
        assertTrue(recyclable.contains("回收站"), recyclable);

        String permanent = DeleteService.describePlan(policy.plan(List.of(big)), policy);
        assertTrue(permanent.contains("永久删除"),
                "超过阈值的项应描述为永久删除，实际：" + permanent);
        assertTrue(permanent.contains("无法"), permanent);

        String mixed = DeleteService.describePlan(policy.plan(List.of(small, big)), policy);
        assertTrue(mixed.contains("共 2 项"), mixed);
        assertTrue(mixed.contains("永久删除"), mixed);
    }

    @Test
    @DisplayName("setPolicy 后真正执行的删除方式随之改变（否则会出现「确认框说进回收站、实际永久删除」）")
    void setPolicyChangesWhatIsActuallyExecuted(@TempDir Path root, @TempDir Path dataDir)
            throws IOException {
        // 两个都远小于 1 GiB，默认策略下都会走回收站分支
        Path a = Files.writeString(root.resolve("a.txt"), "a");
        Path b = Files.writeString(root.resolve("b.txt"), "b");
        FileItem itemA = item(a, false);
        FileItem itemB = item(b, false);

        // 用可注入的接缝：回收站"动作"只记录调用，不真的碰系统回收站
        List<Path> recycledRequests = new ArrayList<>();
        DeleteService service = new DeleteService(
                new DeletePolicy(),
                new DeleteJournal(dataDir),
                paths -> {
                    recycledRequests.addAll(paths);
                    return new Shell32.Result(true, false, 0, "");
                },
                () -> 0L);

        // 阈值调到下限后，同一个条目应改走永久删除分支
        service.setPolicy(new DeletePolicy(DeletePolicy.MIN_THRESHOLD_BYTES, false));
        assertEquals(DeletePolicy.MIN_THRESHOLD_BYTES, service.policy().thresholdBytes());
        // 注意：这里用的 FileItem 大小是真实文件大小（几字节），仍小于下限，
        // 所以要用一个人造的大条目来验证"分支真的换了"
        FileItem big = new FileItem("big.bin", root.resolve("big.bin"), "big.bin", "", false, "bin",
                FileKind.EXECUTABLE, DeletePolicy.MIN_THRESHOLD_BYTES + 1,
                null, null, null, 0, false, false, false);
        assertEquals(DeletePolicy.Action.PERMANENT, service.policy().decide(big),
                "改策略后大条目应判为永久删除");
        assertEquals(DeletePolicy.Action.RECYCLE, service.policy().decide(itemA),
                "小条目仍应走回收站");

        // 反过来：勾上"一律走回收站"后，连大条目也应改走回收站
        service.setPolicy(new DeletePolicy(DeletePolicy.MIN_THRESHOLD_BYTES, true));
        assertEquals(DeletePolicy.Action.RECYCLE, service.policy().decide(big),
                "「一律走回收站」必须覆盖阈值");

        // 再通过 execute 确认策略真的被使用：小条目应交给回收站接缝
        service.setPolicy(new DeletePolicy());
        DeleteService.Outcome outcome = service.execute(List.of(itemA, itemB),
                DeleteService.Mode.POLICY);
        assertEquals(2, recycledRequests.size(),
                "两个小文件都应由回收站接缝处理，实际收到 " + recycledRequests);
        assertEquals(2, outcome.recycled().size());
        assertTrue(outcome.deleted().isEmpty(), "没有任何一项应该走永久删除");
        assertTrue(Files.exists(a) && Files.exists(b), "假接缝不真删，文件应还在");
    }

    @Test
    @DisplayName("setPolicy(null) 回到默认策略而不是抛异常")
    void setPolicyAcceptsNull(@TempDir Path dataDir) {
        DeleteService service = service(dataDir);
        service.setPolicy(null);
        assertEquals(DeletePolicy.DEFAULT_THRESHOLD_BYTES, service.policy().thresholdBytes());
    }
}
