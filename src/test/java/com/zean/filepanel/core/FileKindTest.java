package com.zean.filepanel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileKindTest {

    @Test
    @DisplayName("常见扩展名归类正确")
    void classifiesCommonExtensions() {
        assertEquals(FileKind.DOCUMENT, FileKind.ofExtension("docx"));
        assertEquals(FileKind.DOCUMENT, FileKind.ofExtension("pdf"));
        assertEquals(FileKind.SPREADSHEET, FileKind.ofExtension("xlsx"));
        assertEquals(FileKind.PRESENTATION, FileKind.ofExtension("pptx"));
        assertEquals(FileKind.IMAGE, FileKind.ofExtension("png"));
        assertEquals(FileKind.ARCHIVE, FileKind.ofExtension("rar"));
        assertEquals(FileKind.CODE, FileKind.ofExtension("java"));
        assertEquals(FileKind.EXECUTABLE, FileKind.ofExtension("exe"));
    }

    @Test
    @DisplayName("空扩展名与未知扩展名归入“其他”，且永不返回 null")
    void unknownFallsBackToOther() {
        assertEquals(FileKind.OTHER, FileKind.ofExtension(null));
        assertEquals(FileKind.OTHER, FileKind.ofExtension(""));
        assertEquals(FileKind.OTHER, FileKind.ofExtension("zzz"));
        assertNotEquals(null, FileKind.ofExtension("zzz"));
    }

    @Test
    @DisplayName("归类表规模合理且没有把扩展名漏归到“其他”")
    void indexIsWellFormed() {
        assertTrue(FileKind.knownExtensionCount() > 150,
                "已知扩展名应超过 150 个，实际 " + FileKind.knownExtensionCount());
        // OTHER 只能由“查不到”产生，不能由归类表显式写入
        assertTrue(FileKind.knownExtensionsOf(FileKind.OTHER).isEmpty(),
                "归类表不应显式登记 OTHER");
        assertTrue(FileKind.knownExtensionsOf(FileKind.DOCUMENT).contains("docx"));
    }

    @Test
    @DisplayName("扩展名提取：点开头的文件视为无扩展名")
    void extensionExtraction() {
        assertEquals("docx", FileItem.extensionOf("报告.docx"));
        assertEquals("DOCX".toLowerCase(), FileItem.extensionOf("报告.DOCX"));
        assertEquals("", FileItem.extensionOf(".gitignore"), "点开头的文件应视为无扩展名");
        assertEquals("", FileItem.extensionOf("backup."), "点在末尾应视为无扩展名");
        assertEquals("", FileItem.extensionOf("README"));
        assertEquals("", FileItem.extensionOf(null));
        // 多点文件名取最后一段，与资源管理器一致（archive.tar.gz 的类型是 GZ）
        assertEquals("gz", FileItem.extensionOf("archive.tar.gz"));
    }

    @Test
    @DisplayName("展示文本符合预期")
    void typeTextFormat() {
        FileItem doc = new FileItem("报告.docx", java.nio.file.Path.of("C:/w/报告.docx"),
                "报告.docx", "", false, "docx", FileKind.DOCUMENT, 10L,
                null, null, null, 0, false, false, false);
        assertEquals("文档 · DOCX", doc.typeText());
        assertEquals("（根目录）", doc.locationText());

        FileItem dir = new FileItem("子目录", java.nio.file.Path.of("C:/w/子目录"),
                "子目录", "", true, "", FileKind.DIRECTORY, 0L,
                null, null, null, 0, false, false, false);
        assertEquals("文件夹", dir.typeText());
    }
}
