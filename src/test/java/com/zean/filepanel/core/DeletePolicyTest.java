package com.zean.filepanel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除策略测试（设计决策 D4）。
 *
 * <p>这是整个 M3 里<b>后果最严重</b>的一段逻辑：判错的代价是用户文件被永久删除且无法恢复。
 * 好在它是纯函数——不碰磁盘、不碰 JNA——所以可以用穷举式的组合断言覆盖，
 * 而不是只能靠在真实磁盘上试删来"感觉一下"。
 */
class DeletePolicyTest {

    private static final long MIB = 1024L * 1024;

    private static FileItem file(long size, String name) {
        return new FileItem(name, Path.of("C:/work/" + name), name, "",
                false, FileItem.extensionOf(name), FileKind.ofExtension(FileItem.extensionOf(name)),
                size, null, null, null, 0, false, false, false);
    }

    private static FileItem directory(String name) {
        return new FileItem(name, Path.of("C:/work/" + name), name, "",
                true, "", FileKind.DIRECTORY, 0L, null, null, null, 0, false, false, false);
    }

    @Test
    @DisplayName("阈值边界：正好等于阈值算永久删除，差一点算回收站")
    void thresholdBoundary() {
        DeletePolicy policy = new DeletePolicy(DeletePolicy.GIB, false);

        assertEquals(DeletePolicy.Action.RECYCLE, policy.decide(file(DeletePolicy.GIB - 1, "a.bin")));
        assertEquals(DeletePolicy.Action.PERMANENT, policy.decide(file(DeletePolicy.GIB, "b.bin")),
                "正好等于阈值应算永久删除（>= 而非 >）");
        assertEquals(DeletePolicy.Action.PERMANENT, policy.decide(file(DeletePolicy.GIB + 1, "c.bin")));
    }

    @Test
    @DisplayName("小文件默认走回收站，这是对误删的保护")
    void smallFilesAreRecycledByDefault() {
        DeletePolicy policy = new DeletePolicy();

        assertEquals(DeletePolicy.Action.RECYCLE, policy.decide(file(0, "empty.txt")));
        assertEquals(DeletePolicy.Action.RECYCLE, policy.decide(file(1, "one.txt")));
        assertEquals(DeletePolicy.Action.RECYCLE, policy.decide(file(500 * MIB, "big.rar")));
    }

    @Test
    @DisplayName("“一律走回收站”开关会忽略阈值（给保守用户的退路）")
    void alwaysRecycleOverridesThreshold() {
        DeletePolicy policy = new DeletePolicy(DeletePolicy.GIB, true);

        assertEquals(DeletePolicy.Action.RECYCLE, policy.decide(file(10 * DeletePolicy.GIB, "huge.iso")));
        assertTrue(policy.alwaysRecycle());
        assertTrue(policy.describe().contains("一律"));
    }

    @Test
    @DisplayName("网络路径按永久删除处理（UNC 通常没有回收站）")
    void networkPathsArePermanent() {
        DeletePolicy policy = new DeletePolicy();

        FileItem unc = new FileItem("a.txt", Path.of("\\\\server\\share\\a.txt"), "a.txt", "",
                false, "txt", FileKind.DOCUMENT, 100, null, null, null, 0, false, false, false);
        assertEquals(DeletePolicy.Action.PERMANENT, policy.decide(unc));

        assertTrue(DeletePolicy.isNetworkPath(Path.of("\\\\server\\share\\a.txt")));
        assertTrue(DeletePolicy.isNetworkPath(Path.of("//server/share/a.txt")));
        assertFalse(DeletePolicy.isNetworkPath(Path.of("C:/work/a.txt")));
        assertFalse(DeletePolicy.isNetworkPath(null));
    }

    @Test
    @DisplayName("目录先尝试回收站（目录大小未知，不按阈值判断）")
    void directoriesGoToRecycleBinFirst() {
        DeletePolicy policy = new DeletePolicy();

        assertEquals(DeletePolicy.Action.RECYCLE, policy.decide(directory("大文件夹")),
                "目录 size 恒为 0，若按阈值判断会永远走回收站；这里显式表达该取舍");
    }

    @Test
    @DisplayName("混合选择必须能被识别出来（界面据此弹三选一，不能静默执行）")
    void mixedSelectionIsDetected() {
        DeletePolicy policy = new DeletePolicy(DeletePolicy.GIB, false);
        List<FileItem> items = List.of(
                file(1 * MIB, "small1.txt"),
                file(2 * DeletePolicy.GIB, "huge1.iso"),
                file(3 * MIB, "small2.txt"),
                file(5 * DeletePolicy.GIB, "huge2.iso"));

        DeletePlan plan = policy.plan(items);

        assertTrue(plan.isMixed(), "同时含可回收与永久删除项时必须能被识别");
        assertEquals(2, plan.recyclable().size());
        assertEquals(2, plan.permanent().size());
        assertEquals(4, plan.total());
        assertEquals(7 * DeletePolicy.GIB, plan.permanentBytes());
        assertEquals(4, plan.items().size(), "items() 应包含全部条目，供“全部删除”使用");
        assertFalse(plan.isAllPermanent());
        assertFalse(plan.isAllRecyclable());
    }

    @Test
    @DisplayName("纯可回收与纯永久删除分别能被识别")
    void pureSelectionsAreDetected() {
        DeletePolicy policy = new DeletePolicy(DeletePolicy.GIB, false);

        DeletePlan allSmall = policy.plan(List.of(file(1, "a"), file(2, "b")));
        assertTrue(allSmall.isAllRecyclable());
        assertFalse(allSmall.isMixed());
        assertEquals(0, allSmall.permanentBytes());

        DeletePlan allBig = policy.plan(List.of(file(2 * DeletePolicy.GIB, "a"), file(3 * DeletePolicy.GIB, "b")));
        assertTrue(allBig.isAllPermanent());
        assertFalse(allBig.isMixed());
    }

    @Test
    @DisplayName("空输入与 null 不抛异常")
    void handlesEmptyInput() {
        DeletePolicy policy = new DeletePolicy();

        assertTrue(policy.plan(List.of()).isEmpty());
        assertTrue(policy.plan(null).isEmpty());
        assertEquals(DeletePolicy.Action.RECYCLE, policy.decide(null));
    }

    @Test
    @DisplayName("阈值不允许小于 1MB，避免配置损坏时所有文件都被判为永久删除")
    void thresholdIsClamped() {
        DeletePolicy policy = new DeletePolicy(0, false);
        assertEquals(DeletePolicy.MIN_THRESHOLD_BYTES, policy.thresholdBytes());

        DeletePolicy negative = new DeletePolicy(-1, false);
        assertEquals(DeletePolicy.MIN_THRESHOLD_BYTES, negative.thresholdBytes());
    }

    @Test
    @DisplayName("默认阈值就是 1 GiB，且类型是 long（2 GiB 的预设值不会被 int 截断成负数）")
    void defaultThresholdIsOneGibAsLong() {
        assertEquals(1024L * 1024 * 1024, DeletePolicy.DEFAULT_THRESHOLD_BYTES);
        assertEquals(DeletePolicy.GIB, new DeletePolicy().thresholdBytes());

        // 2 GiB 超出 int 范围（2147483647）。若阈值被当成 int，这里会变成负数，
        // 于是"不小于阈值"变成恒真——所有文件都会被永久删除。
        long twoGib = 2 * DeletePolicy.GIB;
        assertTrue(twoGib > Integer.MAX_VALUE, "2 GiB 必须超出 int 范围，这条断言才有意义");
        assertEquals(twoGib, new DeletePolicy(twoGib, false).thresholdBytes());
        assertEquals(DeletePolicy.Action.PERMANENT,
                new DeletePolicy(twoGib, false).decide(file(3L * 1024 * 1024 * 1024, "x")),
                "3 GiB 不小于 2 GiB 阈值，应永久删除（这一条同时确认阈值没有被 int 截断成负数）");
    }

    @Test
    @DisplayName("clampThreshold / isBelowMinimum 是「显示值 = 生效值」的唯一来源")
    void clampHelpers() {
        assertEquals(DeletePolicy.MIN_THRESHOLD_BYTES, DeletePolicy.clampThreshold(0));
        assertEquals(DeletePolicy.MIN_THRESHOLD_BYTES, DeletePolicy.clampThreshold(-100));
        assertEquals(DeletePolicy.MIN_THRESHOLD_BYTES,
                DeletePolicy.clampThreshold(DeletePolicy.MIN_THRESHOLD_BYTES));
        assertEquals(2 * DeletePolicy.GIB, DeletePolicy.clampThreshold(2 * DeletePolicy.GIB));

        assertTrue(DeletePolicy.isBelowMinimum(0));
        assertTrue(DeletePolicy.isBelowMinimum(DeletePolicy.MIN_THRESHOLD_BYTES - 1));
        assertFalse(DeletePolicy.isBelowMinimum(DeletePolicy.MIN_THRESHOLD_BYTES));
        assertFalse(DeletePolicy.isBelowMinimum(DeletePolicy.DEFAULT_THRESHOLD_BYTES));
    }

    @Test
    @DisplayName("阈值可调，且描述文本随阈值变化")
    void thresholdIsConfigurable() {
        DeletePolicy policy = new DeletePolicy().withThreshold(512L * 1024 * 1024);

        assertEquals(512L * 1024 * 1024, policy.thresholdBytes());
        assertTrue(policy.describeThreshold().contains("MB"), policy.describeThreshold());
        assertTrue(policy.describe().contains("512"), policy.describe());
        assertEquals(DeletePolicy.Action.PERMANENT, policy.decide(file(512L * 1024 * 1024, "x")));

        DeletePolicy twoGb = policy.withThreshold(2 * DeletePolicy.GIB);
        assertEquals(DeletePolicy.Action.RECYCLE, twoGb.decide(file(DeletePolicy.GIB, "x")),
                "阈值调高后原本会被永久删除的文件应改为进回收站");
    }

    @Test
    @DisplayName("withXxx 返回新实例，不修改原对象（界面改设置时不能悄悄影响正在使用的策略）")
    void buildersAreImmutable() {
        DeletePolicy original = new DeletePolicy(DeletePolicy.GIB, false);

        DeletePolicy other = original.withThreshold(2 * DeletePolicy.GIB);
        DeletePolicy recycled = original.withAlwaysRecycle(true);

        assertEquals(DeletePolicy.GIB, original.thresholdBytes());
        assertFalse(original.alwaysRecycle());
        assertEquals(2 * DeletePolicy.GIB, other.thresholdBytes());
        assertTrue(recycled.alwaysRecycle());
        assertFalse(original.alwaysRecycle());
    }
}
