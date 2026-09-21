package com.zean.filepanel.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置读写测试。
 *
 * <p>重点不在“能存能读”，而在<b>坏输入不能拦住启动</b>：
 * 配置文件可能被手工改坏、被同步工具截断、或所在目录只读。
 * 这些都只应导致“回到默认值”，绝不能导致软件打不开。
 */
class ConfigStoreTest {

    @Test
    @DisplayName("文件不存在时返回默认值")
    void missingFileYieldsDefaults(@TempDir Path root) {
        UiState state = new ConfigStore(root).load();

        assertNotNull(state);
        assertEquals(1, state.version);
        assertFalse(state.showFolders);
        assertEquals("name", state.sortColumnId);
        assertTrue(state.sortAscending);
        assertTrue(state.columns.isEmpty());
        assertTrue(state.windowWidth > 0);
    }

    @Test
    @DisplayName("保存后能原样读回，包括各列宽度与可见性")
    void roundTrip(@TempDir Path root) {
        ConfigStore store = new ConfigStore(root);
        UiState state = new UiState();
        state.showFolders = true;
        state.sortColumnId = "size";
        state.sortAscending = false;
        state.columns = List.of(
                new ColumnState("name", 321.5, true),
                new ColumnState("size", 88.0, false));
        state.windowWidth = 1500;
        state.windowHeight = 900;
        state.windowX = 120;
        state.windowY = 80;

        assertTrue(store.save(state), "应写入成功");
        assertTrue(Files.isRegularFile(store.file()));

        UiState loaded = store.load();
        assertTrue(loaded.showFolders);
        assertEquals("size", loaded.sortColumnId);
        assertFalse(loaded.sortAscending);
        assertEquals(2, loaded.columns.size());
        assertEquals("name", loaded.columns.get(0).id);
        assertEquals(321.5, loaded.columns.get(0).width, 0.001);
        assertFalse(loaded.columns.get(1).visible);
        assertEquals(1500, loaded.windowWidth, 0.001);
        assertEquals(120, loaded.windowX, 0.001);
    }

    @Test
    @DisplayName("删除阈值与「一律走回收站」能落盘并原样读回")
    void deleteThresholdRoundTrip(@TempDir Path root) {
        ConfigStore store = new ConfigStore(root);
        UiState state = new UiState();
        state.deleteThresholdBytes = 100L * 1024 * 1024;
        state.alwaysRecycle = true;

        assertTrue(store.save(state), "应写入成功");

        UiState loaded = store.load();
        assertEquals(100L * 1024 * 1024, loaded.deleteThresholdBytes);
        assertTrue(loaded.alwaysRecycle);
    }

    @Test
    @DisplayName("旧的配置文件里没有阈值字段时，安静地用默认值补上（而不是解析失败）")
    void missingThresholdFieldFallsBackToDefault(@TempDir Path root) throws IOException {
        ConfigStore store = new ConfigStore(root);
        Files.createDirectories(store.directory());
        // 这是 M3~M7 时期的真实配置内容：完全没有阈值相关字段
        Files.writeString(store.file(), """
                {
                  "version": 1,
                  "showFolders": true,
                  "theme": "dark",
                  "sortColumnId": "size",
                  "sortAscending": false
                }
                """);

        UiState state = store.load();

        assertTrue(state.showFolders, "老字段仍要正常读回");
        assertEquals("dark", state.theme);
        assertEquals(com.zean.filepanel.core.DeletePolicy.DEFAULT_THRESHOLD_BYTES,
                state.deleteThresholdBytes,
                "缺字段时必须用默认阈值，绝不能变成 0——那会让所有文件都被永久删除");
        assertFalse(state.alwaysRecycle);
    }

    @Test
    @DisplayName("配置文件损坏时返回默认值而不是抛异常")
    void corruptFileYieldsDefaults(@TempDir Path root) throws IOException {
        ConfigStore store = new ConfigStore(root);
        Files.createDirectories(store.directory());
        Files.writeString(store.file(), "{ 这不是合法 JSON ");

        UiState state = store.load();
        assertNotNull(state);
        assertEquals("name", state.sortColumnId, "损坏时应回到默认排序");
    }

    @Test
    @DisplayName("配置文件里出现未知字段不影响读取（便于版本降级与手工编辑）")
    void unknownFieldsAreIgnored(@TempDir Path root) throws IOException {
        ConfigStore store = new ConfigStore(root);
        Files.createDirectories(store.directory());
        Files.writeString(store.file(), """
                {
                  "version": 1,
                  "showFolders": true,
                  "somethingFromTheFuture": {"a": 1}
                }
                """);

        UiState state = store.load();
        assertTrue(state.showFolders);
        assertEquals("name", state.sortColumnId, "缺失字段应保留默认值");
    }

    @Test
    @DisplayName("部分字段缺失时，其余字段仍按默认值补齐")
    void partialFileKeepsDefaults(@TempDir Path root) throws IOException {
        ConfigStore store = new ConfigStore(root);
        Files.createDirectories(store.directory());
        Files.writeString(store.file(), "{\"sortColumnId\":\"modified\"}");

        UiState state = store.load();
        assertEquals("modified", state.sortColumnId);
        assertTrue(state.sortAscending, "未提供的字段应保持默认值");
        assertFalse(state.showFolders);
        assertTrue(state.windowWidth > 0);
    }

    @Test
    @DisplayName("写入失败时返回 false 而不抛异常（便携程序可能落在只读位置）")
    void saveFailureIsReportedNotThrown(@TempDir Path base) throws IOException {
        // 让“根目录”指向一个普通文件：创建子目录必然失败
        Path file = base.resolve("not-a-directory");
        Files.writeString(file, "x");

        ConfigStore store = new ConfigStore(file);
        assertFalse(store.save(new UiState()), "写不进去应返回 false");
    }

    @Test
    @DisplayName("根目录为 null 时读写都不抛异常")
    void nullRootIsSafe() {
        ConfigStore store = new ConfigStore(null);
        assertNotNull(store.load());
        assertFalse(store.save(new UiState()));
    }

    @Test
    @DisplayName("数据目录名与默认排除规则保持一致，否则软件会把自己列进列表")
    void directoryNameMatchesExclusionRule(@TempDir Path root) {
        ConfigStore store = new ConfigStore(root);
        assertEquals(".filepanel", store.directory().getFileName().toString());
        assertEquals(ConfigStore.DIR_NAME, store.directory().getFileName().toString());

        // 这条断言把“配置目录”与“排除规则”绑在一起：
        // 任何一边改名而另一边没改，都会让程序把自己的配置目录显示在文件列表里
        assertTrue(com.zean.filepanel.core.Exclusions.defaults()
                        .excludesDirectory(store.directory()),
                "配置目录必须被默认排除规则覆盖");
    }

    @Test
    @DisplayName("保存会创建数据目录")
    void saveCreatesDirectory(@TempDir Path root) {
        ConfigStore store = new ConfigStore(root);
        assertFalse(Files.exists(store.directory()), "读取阶段不应产生目录");

        store.save(new UiState());
        assertTrue(Files.isDirectory(store.directory()));
    }
}
