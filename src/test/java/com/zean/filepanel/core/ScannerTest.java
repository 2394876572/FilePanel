package com.zean.filepanel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScannerTest {

    private static Path write(Path path, int bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[bytes]);
        return path;
    }

    @Test
    @DisplayName("收录文件与目录，并正确计算相对路径与层级")
    void collectsFilesAndDirectories(@TempDir Path root) throws IOException {
        write(root.resolve("a.txt"), 10);
        write(root.resolve("sub").resolve("b.txt"), 20);
        write(root.resolve("sub").resolve("deep").resolve("c.txt"), 30);

        ScanResult result = new Scanner(Exclusions.defaults(), null).scan(root, () -> false);

        assertEquals(3, result.fileCount(), "文件数");
        assertEquals(2, result.dirCount(), "目录数（不含扫描根）");
        assertEquals(60, result.totalBytes(), "总字节数");
        assertFalse(result.hasErrors());
        assertFalse(result.truncated());
        assertFalse(result.cancelled());

        FileItem deep = result.items().stream()
                .filter(i -> i.name().equals("c.txt"))
                .findFirst().orElseThrow();
        assertEquals("sub/deep/c.txt", deep.relPath(), "相对路径统一用 / 分隔");
        assertEquals("sub/deep", deep.parentRel());
        assertEquals(2, deep.depth(), "根下直接子项为 0 层");
        assertEquals(FileKind.DOCUMENT, deep.kind());
        assertEquals("txt", deep.ext());
    }

    @Test
    @DisplayName("扫描根本身不出现在结果里")
    void rootItselfIsNotAnItem(@TempDir Path root) throws IOException {
        write(root.resolve("a.txt"), 1);
        ScanResult result = new Scanner(Exclusions.defaults(), null).scan(root, () -> false);
        assertTrue(result.items().stream().noneMatch(i -> i.path().equals(root.toAbsolutePath().normalize())));
    }

    @Test
    @DisplayName("排除目录时整棵子树被剪枝，内部文件完全不进结果")
    void prunesExcludedSubtree(@TempDir Path root) throws IOException {
        write(root.resolve("keep.txt"), 5);
        write(root.resolve("node_modules").resolve("pkg").resolve("index.js"), 100);
        write(root.resolve("node_modules").resolve("pkg").resolve("deep").resolve("x.js"), 100);
        write(root.resolve(".venv").resolve("lib").resolve("site.py"), 100);

        ScanResult result = new Scanner(Exclusions.defaults(), null).scan(root, () -> false);

        assertEquals(1, result.fileCount(), "只有 keep.txt 应被收录");
        assertEquals(0, result.dirCount(), "被剪枝目录内部的子目录不应被收录");
        assertEquals(2, result.excludedDirs().size(), "node_modules 与 .venv 各计一个");
        assertTrue(result.excludedDirs().stream()
                .anyMatch(p -> p.getFileName().toString().equals("node_modules")));
    }

    @Test
    @DisplayName("被文件名规则排除的文件单独计数并累计体积，不计入收录")
    void countsExcludedFilesSeparately(@TempDir Path root) throws IOException {
        write(root.resolve("报告.docx"), 10);
        write(root.resolve("~$报告.docx"), 40);
        write(root.resolve("cache.tmp"), 50);

        ScanResult result = new Scanner(Exclusions.defaults(), null).scan(root, () -> false);

        assertEquals(1, result.fileCount());
        assertEquals(2, result.excludedFileCount(), "临时文件与锁文件各计一个");
        assertEquals(90, result.excludedFileBytes(), "被排除文件的大小仍要累计给用户看");
    }

    @Test
    @DisplayName("关闭排除规则后所有文件都被收录（对照组）")
    void disabledExclusionsCollectEverything(@TempDir Path root) throws IOException {
        write(root.resolve("keep.txt"), 5);
        write(root.resolve("node_modules").resolve("index.js"), 100);
        write(root.resolve("cache.tmp"), 50);

        ScanResult result = new Scanner(Exclusions.none(), null).scan(root, () -> false);

        assertEquals(3, result.fileCount());
        assertEquals(0, result.excludedDirs().size());
        assertEquals(0, result.excludedFileCount());
    }

    @Test
    @DisplayName("不存在的根目录只记录问题，不抛异常且返回可用结果")
    void missingRootDoesNotThrow(@TempDir Path root) {
        Path missing = root.resolve("definitely-not-here");

        ScanResult result = new Scanner(Exclusions.defaults(), null).scan(missing, () -> false);

        assertNotNull(result);
        assertEquals(0, result.totalCount());
        assertTrue(result.hasErrors(), "应记录一条读取失败");
        assertTrue(result.errorCount() >= 1);
    }

    @Test
    @DisplayName("进度回调至少收到一次最终快照，且计数与结果一致")
    void reportsProgress(@TempDir Path root) throws IOException {
        write(root.resolve("a.txt"), 10);
        write(root.resolve("b.txt"), 20);

        List<ScanProgress> events = new ArrayList<>();
        new Scanner(Exclusions.defaults(), events::add).scan(root, () -> false);

        assertFalse(events.isEmpty(), "必须至少回调一次，否则界面上的计数永远不更新");
        ScanProgress last = events.get(events.size() - 1);
        assertEquals(2, last.files(), "最终进度里的文件数应与结果一致");
        assertEquals(30, last.bytes());
    }

    @Test
    @DisplayName("取消后返回已扫到的部分，并标记为已取消")
    void supportsCancellation(@TempDir Path root) throws IOException {
        for (int i = 0; i < 50; i++) {
            write(root.resolve("d" + i).resolve("f" + i + ".txt"), 1);
        }
        // 前几次询问返回 false，之后返回 true，模拟用户中途点取消
        AtomicInteger calls = new AtomicInteger();
        ScanResult result = new Scanner(Exclusions.defaults(), null)
                .scan(root, () -> calls.incrementAndGet() > 5);

        assertTrue(result.cancelled(), "应标记为已取消");
        assertTrue(result.fileCount() < 50, "不应扫完，实际 " + result.fileCount());
        assertNotNull(result.items(), "取消也必须返回可用结果（展示已扫部分）");
    }

    @Test
    @DisplayName("隐藏内容统计：被剪枝目录内部的规模可被完整还原，且与直接排除的部分正确合并")
    void measuresAndMergesHiddenContent(@TempDir Path root) throws IOException {
        write(root.resolve("keep.txt"), 5);
        write(root.resolve("~$lock.docx"), 7);
        write(root.resolve(".venv").resolve("site.py"), 100);
        write(root.resolve(".venv").resolve("lib").resolve("deep.py"), 200);

        ScanResult result = new Scanner(Exclusions.defaults(), null).scan(root, () -> false);
        HiddenStats measured = Scanner.measureHidden(result.excludedDirs(), () -> false, 10_000);
        HiddenStats total = Scanner.totalHidden(result, measured);

        // 被剪枝目录内部：2 个文件 + 1 个子目录
        assertEquals(2, measured.fileCount(), "被剪枝目录内部文件数");
        assertEquals(1, measured.dirCount(), "被剪枝目录内部子目录数");
        assertEquals(300, measured.bytes());
        assertFalse(measured.truncated());

        // 合并口径：文件 = 直接排除的 1 个 + 内部的 2 个；目录 = 被剪枝的 .venv 自身 1 个 + 内部的 1 个
        assertEquals(3, total.fileCount(), "隐藏文件总数");
        assertEquals(2, total.dirCount(), "隐藏目录总数（含被剪枝目录自身）");
        assertEquals(307, total.bytes(), "隐藏内容总体积，含直接排除的锁文件");
    }

    @Test
    @DisplayName("统计隐藏内容时达到上限会标记为下界，而不是给出错误的上界")
    void hiddenMeasurementRespectsCap(@TempDir Path root) throws IOException {
        for (int i = 0; i < 20; i++) {
            write(root.resolve(".venv").resolve("f" + i + ".py"), 10);
        }
        ScanResult result = new Scanner(Exclusions.defaults(), null).scan(root, () -> false);

        HiddenStats capped = Scanner.measureHidden(result.excludedDirs(), () -> false, 5);
        assertTrue(capped.truncated(), "触达上限必须标记");
        assertTrue(capped.fileCount() <= 5);
    }

    @Test
    @DisplayName("被设置为隐藏属性的文件能被识别（Windows）")
    @EnabledOnOs(OS.WINDOWS)
    void detectsHiddenAttribute(@TempDir Path root) throws IOException {
        Path visible = write(root.resolve("visible.txt"), 1);
        Path hidden = write(root.resolve("hidden.txt"), 1);
        Files.setAttribute(hidden, "dos:hidden", true);

        ScanResult result = new Scanner(Exclusions.defaults(), null).scan(root, () -> false);

        FileItem hiddenItem = result.items().stream()
                .filter(i -> i.name().equals("hidden.txt")).findFirst().orElseThrow();
        FileItem visibleItem = result.items().stream()
                .filter(i -> i.name().equals("visible.txt")).findFirst().orElseThrow();

        assertTrue(hiddenItem.hidden(), "dos:hidden 的文件应被识别为隐藏");
        assertFalse(visibleItem.hidden());
        assertEquals(1, result.hiddenCount(), "隐藏属性计数应为 1");

        // 清理：避免临时目录里的隐藏文件影响后续清理
        Files.setAttribute(hidden, "dos:hidden", false);
        Files.deleteIfExists(visible);
    }
}
