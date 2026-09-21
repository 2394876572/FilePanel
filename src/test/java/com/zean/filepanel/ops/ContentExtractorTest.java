package com.zean.filepanel.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容抽取测试。
 *
 * <p>这一层最容易在两类地方出错，所以重点都压在这两处：
 * <b>中文编码</b>（GBK 的 txt 按 UTF-8 读会变成一串问号，搜索永远搜不到却不报错）
 * 与 <b>Office 格式</b>（它们只是 ZIP+XML，但里面的条目名挑错就会抽到一堆噪声甚至空文本）。
 */
class ContentExtractorTest {

    private static Path writeText(Path path, String content, Charset charset) throws IOException {
        Files.write(path, content.getBytes(charset));
        return path;
    }

    /** 造一个最小的 ZIP（用来模拟 docx/xlsx/pptx）。 */
    private static Path writeZip(Path path, Map<String, String> entries) throws IOException {
        try (OutputStream raw = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    // ------------------------------------------------------------------ 文本

    @Test
    @DisplayName("UTF-8 纯文本正常抽取")
    void readsUtf8Text(@TempDir Path dir) throws IOException {
        Path file = writeText(dir.resolve("a.txt"), "灰度发布管理系统 技术说明书", StandardCharsets.UTF_8);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertTrue(result.succeeded(), String.valueOf(result.detail()));
        assertTrue(result.text().contains("灰度发布管理系统"), result.text());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("GBK 编码的中文文本也能正确抽取（中文 Windows 上很常见）")
    void readsGbkText(@TempDir Path dir) throws IOException {
        Path file = writeText(dir.resolve("gbk.txt"), "压力变送器 量程 0-10MPa",
                Charset.forName("GBK"));

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertTrue(result.succeeded(), String.valueOf(result.detail()));
        assertTrue(result.text().contains("压力变送器"),
                "GBK 中文必须能正确解码，否则搜索永远搜不到却不报错：" + result.text());
    }

    @Test
    @DisplayName("按扩展名白名单之外的类型不抽取")
    void skipsUnsupportedExtension(@TempDir Path dir) throws IOException {
        Path file = writeText(dir.resolve("a.png"), "not really an image", StandardCharsets.UTF_8);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertEquals(ContentExtractor.Skip.UNSUPPORTED, result.skip());
        assertFalse(result.succeeded());
    }

    @Test
    @DisplayName("含 NUL 字节的文件按二进制跳过（防止改了扩展名的压缩包被读成乱码）")
    void skipsBinaryContent(@TempDir Path dir) throws IOException {
        byte[] bytes = new byte[64];
        bytes[10] = 0;
        Path file = Files.write(dir.resolve("fake.txt"), bytes);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertEquals(ContentExtractor.Skip.BINARY, result.skip(), result.detail());
    }

    @Test
    @DisplayName("超过体积上限的文件被跳过，并说明原因")
    void skipsTooLarge(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("big.txt"),
                new byte[(int) ContentExtractor.MAX_FILE_BYTES + 1024]);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertEquals(ContentExtractor.Skip.TOO_LARGE, result.skip());
        assertTrue(result.detail().contains("超过上限"), result.detail());
    }

    @Test
    @DisplayName("空文件被跳过而不是当成空文本")
    void skipsEmptyFile(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("empty.txt"), new byte[0]);
        assertEquals(ContentExtractor.Skip.EMPTY, ContentExtractor.extract(file).skip());
    }

    @Test
    @DisplayName("抽取结果超过字符上限时截断并标记")
    void truncatesLongText(@TempDir Path dir) throws IOException {
        Path file = writeText(dir.resolve("long.txt"),
                "字".repeat(ContentExtractor.MAX_CHARS_PER_FILE + 500), StandardCharsets.UTF_8);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertTrue(result.succeeded());
        assertEquals(ContentExtractor.MAX_CHARS_PER_FILE, result.text().length());
        assertTrue(result.truncated());
    }

    // ------------------------------------------------------------------ Office

    @Test
    @DisplayName("docx：从 word/document.xml 里取出正文，段落保留换行")
    void readsDocx(@TempDir Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("word/document.xml", """
                <?xml version="1.0"?>
                <w:document><w:body>
                  <w:p><w:r><w:t>灰度发布管理系统</w:t></w:r></w:p>
                  <w:p><w:r><w:t>技术说明书 V2.0</w:t></w:r></w:p>
                  <w:p><w:r><w:t>整定压力 0.8MPa</w:t></w:r></w:p>
                </w:body></w:document>
                """);
        // 顺带放一个应当被忽略的条目
        entries.put("word/media/image1.png", "BINARYISH");
        Path file = writeZip(dir.resolve("报告.docx"), entries);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertTrue(result.succeeded(), String.valueOf(result.detail()));
        assertTrue(result.text().contains("灰度发布管理系统"), result.text());
        assertTrue(result.text().contains("整定压力"), result.text());
        assertFalse(result.text().contains("BINARYISH"), "图片条目不该被当成文字抽出来");
        assertTrue(result.text().contains("\n"), "段落之间应保留换行，否则摘要看不出上下文");
    }

    @Test
    @DisplayName("xlsx：抽取共享字符串与工作表里的文本")
    void readsXlsx(@TempDir Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("xl/sharedStrings.xml", """
                <sst><si><t>项目</t></si><si><t>报价</t></si><si><t>灰度发布</t></si></sst>
                """);
        entries.put("xl/worksheets/sheet1.xml", """
                <worksheet><sheetData><row><c><v>12345</v></c></row></sheetData></worksheet>
                """);
        Path file = writeZip(dir.resolve("报价单.xlsx"), entries);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertTrue(result.succeeded(), String.valueOf(result.detail()));
        assertTrue(result.text().contains("项目"), result.text());
        assertTrue(result.text().contains("灰度发布"), result.text());
        assertTrue(result.text().contains("12345"), "工作表里的数值也应可搜");
    }

    @Test
    @DisplayName("pptx：抽取幻灯片文本")
    void readsPptx(@TempDir Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("ppt/slides/slide1.xml", """
                <p:sld><p:cSld><a:p><a:r><a:t>灰度发布系统介绍</a:t></a:r></a:p></p:cSld></p:sld>
                """);
        entries.put("ppt/slides/slide2.xml", """
                <p:sld><p:cSld><a:p><a:r><a:t>系统架构</a:t></a:r></a:p></p:cSld></p:sld>
                """);
        Path file = writeZip(dir.resolve("介绍.pptx"), entries);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertTrue(result.succeeded(), String.valueOf(result.detail()));
        assertTrue(result.text().contains("灰度发布系统介绍"), result.text());
        assertTrue(result.text().contains("系统架构"), result.text());
    }

    @Test
    @DisplayName("XML 实体被正确解开（否则搜 &amp; 之类的原文搜不到）")
    void decodesXmlEntities() {
        StringBuilder out = new StringBuilder();
        ContentExtractor.appendXmlText(out, "<w:p><w:t>R&amp;D &lt;流程&gt;</w:t></w:p>", "docx");

        assertTrue(out.toString().contains("R&D <流程>"), out.toString());
    }

    @Test
    @DisplayName("伪装成 docx 的非 ZIP 文件报读取失败，而不是抛异常")
    void handlesFakeDocx(@TempDir Path dir) throws IOException {
        Path file = writeText(dir.resolve("fake.docx"), "我其实是个文本文件", StandardCharsets.UTF_8);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertFalse(result.succeeded());
        assertNotNull(result.skip());
        assertEquals(ContentExtractor.Skip.ERROR, result.skip());
    }

    @Test
    @DisplayName("docx 里没有正文时判为「没有可索引的文字」")
    void docxWithoutText(@TempDir Path dir) throws IOException {
        Path file = writeZip(dir.resolve("empty.docx"),
                Map.of("word/media/image1.png", "X"));

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        assertEquals(ContentExtractor.Skip.EMPTY, result.skip());
    }

    @Test
    @DisplayName("加密的 docx（Office 会包成 OLE2）报「已加密」，而不是「读取失败」")
    void detectsEncryptedDocx(@TempDir Path dir) throws IOException {
        // 真实的加密 docx 就是 OLE2 头 + 一堆二进制；只需要头部正确即可验证判定逻辑
        byte[] bytes = new byte[512];
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(ole2, 0, bytes, 0, ole2.length);
        Path file = Files.write(dir.resolve("已加密.docx"), bytes);

        ContentExtractor.Extraction result = ContentExtractor.extract(file);

        // 关键断言：不能是 ERROR。否则用户看到"读取失败"会以为文件损坏，
        // 而实际上只要输密码就能打开。
        assertEquals(ContentExtractor.Skip.OLE2, result.skip(), result.detail());
        assertTrue(result.detail().contains("打开密码"), result.detail());
    }

    @Test
    @DisplayName("OLE2 头判定：真实的 docx 是 ZIP，绝不能被误判；读取失败与过短文件必须安全返回 false")
    void ole2HeaderDetection(@TempDir Path dir) throws IOException {
        Path zip = writeZip(dir.resolve("real.docx"),
                Map.of("word/document.xml", "<w:p><w:t>真</w:t></w:p>"));
        assertFalse(ContentExtractor.hasOle2Header(zip), "ZIP 头（PK）不能被当成 OLE2");
        assertFalse(ContentExtractor.hasOle2Header(
                writeText(dir.resolve("plain.docx"), "PK-not-really", StandardCharsets.UTF_8)));
        assertFalse(ContentExtractor.hasOle2Header(Files.write(dir.resolve("tiny.docx"), new byte[4])),
                "少于 8 字节时必须安全返回 false");
        assertFalse(ContentExtractor.hasOle2Header(dir.resolve("missing.docx")),
                "路径不存在时必须安全返回 false，交给后续分支报真实原因");
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        assertTrue(ContentExtractor.hasOle2Header(Files.write(dir.resolve("legacy.xls"), ole2)));
    }

    // ------------------------------------------------------------------ 杂项

    @Test
    @DisplayName("支持类型判断与扩展名白名单一致")
    void supportedExtensions() {
        assertTrue(ContentExtractor.isSupportedExtension("txt"));
        assertTrue(ContentExtractor.isSupportedExtension("DOCX"), "扩展名大小写不敏感");
        assertTrue(ContentExtractor.isSupportedExtension("pdf"));
        assertTrue(ContentExtractor.isSupportedExtension("java"));
        assertFalse(ContentExtractor.isSupportedExtension("png"));
        assertFalse(ContentExtractor.isSupportedExtension("doc"), "旧格式 doc 不支持");
        assertFalse(ContentExtractor.isSupportedExtension(""));
        assertFalse(ContentExtractor.isSupportedExtension(null));
    }

    @Test
    @DisplayName("不存在的路径返回读取失败，不抛异常")
    void missingFileIsSafe(@TempDir Path dir) {
        ContentExtractor.Extraction result = ContentExtractor.extract(dir.resolve("nope.txt"));
        assertEquals(ContentExtractor.Skip.ERROR, result.skip());
        assertFalse(result.succeeded());
    }

    @Test
    @DisplayName("字节级二进制探测")
    void binarySniffing() {
        assertFalse(ContentExtractor.looksBinary("普通文本".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ContentExtractor.looksBinary(new byte[]{1, 2, 0, 3}));
    }
}
