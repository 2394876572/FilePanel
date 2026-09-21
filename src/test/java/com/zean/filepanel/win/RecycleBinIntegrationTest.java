package com.zean.filepanel.win;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收站的集成验证。
 *
 * <h2>为什么这个测试默认不跑</h2>
 * 它会<b>真实地向用户系统的回收站里塞一个文件</b>——这是不可忽略的副作用：
 * 回收站是用户的私人空间，测试不应该在里面留东西。
 * 但"文件到底进没进回收站"恰恰是 M3 最关键、也最不能靠推断的结论：
 * 只看"原路径已不存在"无法区分"进了回收站（可还原）"和"被永久删除（不可恢复）"。
 *
 * <p>因此做成<b>显式开启</b>：默认跳过，需要时用
 * {@code scripts\mvn.cmd test -Dfilepanel.recycle.test=true} 手动验证一次。
 *
 * <h2>判定方法</h2>
 * 用 {@code SHQueryRecycleBin} 取操作前后的回收站条目数，
 * 条目数增加才说明文件确实被放进了回收站，而不是被永久删除。
 * 放进去的那个临时文件会留在回收站里，用户可自行清空。
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "filepanel.recycle.test", matches = "true")
class RecycleBinIntegrationTest {

    @Test
    @DisplayName("删除的文件确实进入回收站（回收站条目数增加），而不是被永久删除")
    void fileReallyGoesToRecycleBin(@TempDir Path dir) throws IOException {
        assertTrue(Shell32.available(), "shell32 应可加载");

        // 用一个可预测名字的临时文件，便于用户在回收站里辨认
        Path file = Files.writeString(dir.resolve("文件面板-回收站验证-可删除.txt"), "临时内容");
        assertTrue(Files.exists(file));

        long before = Shell32.recycleBinItemCount(null);
        assertTrue(before >= 0, "应能查询到回收站条目数");

        Shell32.Result result = Shell32.moveToRecycleBin(List.of(file));
        assertTrue(result.success(), "送入回收站应成功，实际：" + result.message() + " code=" + result.code());

        assertFalse(Files.exists(file), "原路径上的文件应已消失");

        long after = Shell32.recycleBinItemCount(null);
        assertTrue(after >= 0, "应能查询到回收站条目数");
        assertEquals(before + 1, after,
                "回收站条目数应恰好增加 1，这才证明文件是“进了回收站”而不是“被永久删除”。"
                        + "若本断言失败，说明当前环境无法把文件送入回收站"
                        + "（例如回收站被关闭、配额不足，或运行环境限制了写入 C:\\$Recycle.Bin）。"
                        + "这正是 DeleteService 必须做“回收站条目数验证”而不能只信 Shell 返回码的原因。");
    }

    @Test
    @DisplayName("不存在的路径会失败并给出可读原因，而不是假成功")
    void missingPathFails(@TempDir Path dir) {
        Shell32.Result result = Shell32.moveToRecycleBin(List.of(dir.resolve("never-existed.txt")));

        assertFalse(result.success());
        assertFalse(result.message().isBlank(), "失败必须带原因");
    }
}
