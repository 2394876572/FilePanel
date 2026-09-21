package com.zean.filepanel.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机器级状态测试（"上次管理的文件夹"）。
 *
 * <p>这份数据的特点是：**它坏了只会让用户多点一次选择框，绝不能拦住启动**。
 * 所以除了往返一致，重点测"各种坏输入都返回 null 而不是抛异常"。
 */
class MachineStateTest {

    @Test
    @DisplayName("记住之后能读回来")
    void roundTrip(@TempDir Path dataDir, @TempDir Path managed) {
        MachineState state = new MachineState(dataDir);

        assertTrue(state.rememberRoot(managed, "1.0.0-test"));
        assertEquals(managed.toAbsolutePath().normalize(), state.lastRoot());
        assertEquals("1.0.0-test", state.load().writtenBy);
    }

    @Test
    @DisplayName("没记住过时返回 null（而不是空字符串或抛异常）")
    void emptyByDefault(@TempDir Path dataDir) {
        MachineState state = new MachineState(dataDir);

        assertNull(state.lastRoot());
        assertEquals("", state.load().lastRoot);
    }

    @Test
    @DisplayName("记住的目录已经不存在时返回 null —— 否则安装版会启动到一个不存在的根上")
    void vanishedDirectoryIsForgotten(@TempDir Path dataDir, @TempDir Path managed)
            throws IOException {
        MachineState state = new MachineState(dataDir);
        state.rememberRoot(managed, "1");

        // 模拟"目录被删掉/改名/在拔掉的移动盘上"
        Files.delete(managed);

        assertNull(state.lastRoot());
    }

    @Test
    @DisplayName("文件被写坏时返回 null，不抛异常")
    void corruptFileIsSafe(@TempDir Path dataDir) throws IOException {
        MachineState state = new MachineState(dataDir);
        Files.createDirectories(state.directory());
        Files.writeString(state.file(), "{ 这不是合法 JSON ");

        assertNull(state.lastRoot());
        assertEquals("", state.load().lastRoot, "读坏时应回到默认值");
    }

    @Test
    @DisplayName("路径字段是空的或非法的也不抛异常")
    void blankOrIllegalPathIsSafe(@TempDir Path dataDir) throws IOException {
        MachineState state = new MachineState(dataDir);
        Files.createDirectories(state.directory());

        Files.writeString(state.file(), "{\"lastRoot\":\"\"}");
        assertNull(state.lastRoot());

        Files.writeString(state.file(), "{\"lastRoot\":\"C:\\\\不\\u0000可能的路径\"}");
        assertNull(state.lastRoot(), "非法路径必须当作没记住，而不是抛出去");

        Files.writeString(state.file(), "{}");
        assertNull(state.lastRoot(), "缺字段即默认值");
    }

    @Test
    @DisplayName("rememberRoot(null) 返回 false 且不覆盖已有的记录")
    void rejectsNullRoot(@TempDir Path dataDir, @TempDir Path managed) {
        MachineState state = new MachineState(dataDir);
        state.rememberRoot(managed, "1");

        assertFalse(state.rememberRoot(null, "1"));
        assertEquals(managed.toAbsolutePath().normalize(), state.lastRoot(), "原有记录不能被清掉");
    }

    @Test
    @DisplayName("forget 之后回到「没记住」状态，且可重复调用")
    void forgetWorks(@TempDir Path dataDir, @TempDir Path managed) {
        MachineState state = new MachineState(dataDir);
        state.rememberRoot(managed, "1");
        assertNotNull(state.lastRoot());

        assertTrue(state.forget());
        assertNull(state.lastRoot());
        assertTrue(state.forget(), "重复 forget 也应当返回成功（卸载脚本会调多次）");
    }

    @Test
    @DisplayName("默认位置在 %LOCALAPPDATA%\\FilePanel 下，且与安装位置同源")
    void defaultDirectoryIsUnderLocalAppData() {
        Path dir = MachineState.defaultDirectory();

        assertNotNull(dir);
        assertTrue(dir.isAbsolute(), "必须是绝对路径：" + dir);
        assertEquals(MachineState.DIR_NAME, dir.getFileName().toString());
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            assertTrue(dir.startsWith(localAppData),
                    "应当落在 %LOCALAPPDATA% 下，实际 " + dir);
        } else {
            // 环境变量缺失时的兜底：主目录下的隐藏目录，功能不能因此失效
            assertTrue(dir.getFileName().toString().startsWith("."),
                    "兜底位置应当是隐藏目录：" + dir);
        }
    }

    @Test
    @DisplayName("写到不可写的位置时返回 false，而不是抛异常打断启动")
    void unwritableLocationIsSafe(@TempDir Path root) throws IOException {
        // 用"把目录名占成普通文件"来制造不可写：创建 <root>\.filepanel 这个文件
        Path blocked = root.resolve(".filepanel");
        Files.writeString(blocked, "占位");
        MachineState state = new MachineState(blocked);

        assertFalse(state.rememberRoot(root, "1"), "写不进去应当返回 false");
        assertNull(state.lastRoot());
    }
}
