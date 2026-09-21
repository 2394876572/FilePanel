package com.zean.filepanel.ui.dialog;

import com.zean.filepanel.core.DeletePlan;
import com.zean.filepanel.core.DeletePolicy;
import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除确认框的文案测试。
 *
 * <p>测的是 {@link DeleteConfirmDialog#headlineFor} —— 它被刻意做成纯函数，
 * 所以三种分支的文案能被逐条钉住，而不需要真的弹出对话框（{@code showAndWait()} 会阻塞
 * JavaFX 线程，单测里没法用它）。
 *
 * <p>为什么值得为几句文案写测试：这个框是"删除"这条路上唯一一次向用户解释后果的机会，
 * 而"预览截图"和"真正删的时候"共用同一份文案。两处各写一份的话，迟早会出现
 * "截图里说进回收站、真正删的时候说永久删除"——那是本项目里后果最严重的一类偏差。
 */
class DeleteConfirmDialogTest {

    private static final long MIB = 1024L * 1024;

    private static FileItem file(long size, String name) {
        return new FileItem(name, Path.of("C:/work/" + name), name, "",
                false, FileItem.extensionOf(name), FileKind.ofExtension(FileItem.extensionOf(name)),
                size, null, null, null, 0, false, false, false);
    }

    @Test
    @DisplayName("全部可回收：文案说明可以从回收站还原")
    void recyclableHeadline() {
        DeletePolicy policy = new DeletePolicy();
        DeletePlan plan = policy.plan(List.of(file(1024, "临时文件.txt")));

        String headline = DeleteConfirmDialog.headlineFor(plan, policy);

        assertTrue(headline.contains("将 1 项放入回收站"), headline);
        assertTrue(headline.contains("可以从回收站还原"), headline);
        assertTrue(!headline.contains("永久删除"),
                "可回收这一条不该出现「永久删除」四个字，否则用户会以为自己要丢文件：" + headline);
    }

    @Test
    @DisplayName("全部永久删除：文案必须写明无法从回收站恢复")
    void permanentHeadline() {
        DeletePolicy policy = new DeletePolicy(DeletePolicy.GIB, false);
        DeletePlan plan = policy.plan(List.of(file(2 * DeletePolicy.GIB, "安装包.exe")));

        String headline = DeleteConfirmDialog.headlineFor(plan, policy);

        assertTrue(headline.contains("【永久删除】"), headline);
        assertTrue(headline.contains("无法从回收站恢复"), headline);
        assertTrue(headline.contains(policy.describeThreshold()),
                "阈值必须出现在文案里，用户才知道是哪条规则触发的：" + headline);
    }

    @Test
    @DisplayName("混合选择：文案必须点出其中多少项会被永久删除")
    void mixedHeadline() {
        DeletePolicy policy = new DeletePolicy(DeletePolicy.GIB, false);
        DeletePlan plan = policy.plan(List.of(
                file(1024, "小文件.txt"),
                file(2 * DeletePolicy.GIB, "大文件.zip"),
                file(3 * DeletePolicy.GIB, "更大文件.iso")));

        String headline = DeleteConfirmDialog.headlineFor(plan, policy);

        assertTrue(plan.isMixed(), "一多一小必须是混合场景");
        assertTrue(headline.contains("3 项中，有 2 项超过"), headline);
        assertTrue(headline.contains("请选择处理方式"), headline);
    }

    @Test
    @DisplayName("展示名字最多列 10 条，其余概括（避免对话框被长列表撑爆）")
    void previewNamesAreCapped() {
        List<FileItem> many = java.util.stream.IntStream.rangeClosed(1, 13)
                .mapToObj(i -> file(MIB, "示例文件" + i + ".txt"))
                .toList();

        String text = DeleteConfirmDialog.previewNames(many);

        assertTrue(text.contains("示例文件1.txt"), text);
        assertTrue(text.contains("示例文件10.txt"), text);
        assertTrue(!text.contains("示例文件11.txt"), "超过 10 条的不应逐个列出：" + text);
        assertTrue(text.contains("… 等共 13 项"), text);
    }
}
