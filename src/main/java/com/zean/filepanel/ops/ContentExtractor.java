package com.zean.filepanel.ops;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从文件里抽取纯文本，供全文内容搜索使用。
 *
 * <h2>支持范围与零额外依赖的实现</h2>
 * <ul>
 *   <li><b>文本与代码</b>（txt/md/log/json/xml/csv/各类源码）：直接读。</li>
 *   <li><b>docx / xlsx / pptx</b>：它们本质上是 ZIP + XML，
 *       用 JDK 自带的 {@code java.util.zip} 就能解开——<b>不需要引入 POI</b>。</li>
 *   <li><b>pdf</b>：用 PDFBox（已确认引入的 2.5MB 依赖）。</li>
 *   <li><b>旧格式 doc/xls</b>：不支持，需要 POI-HSFC，成本远高于收益。</li>
 * </ul>
 *
 * <h2>几个必须处理好的细节</h2>
 * <ol>
 *   <li><b>编码</b>：中文 .txt 有相当比例是 GBK。先按 UTF-8 严格解码，
 *       失败再退 GBK——比"猜编码"可靠，也比"统一按 UTF-8 读"不丢内容。</li>
 *   <li><b>二进制探测</b>：按扩展名白名单之外不抽取；白名单内也要检查前若干字节里有没有
 *       NUL，避免把一个改了扩展名的压缩包当文本读出一堆乱码。</li>
 *   <li><b>体积上限</b>：单文件超过 {@value #MAX_FILE_BYTES} 字节直接跳过。
 *       这既是性能护栏，也让"单文件解析超时"这件事在绝大多数情况下不会发生。</li>
 * </ol>
 */
public final class ContentExtractor {

    /** 单文件体积上限：5 MB。超过就不抽取。 */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    /** 单文件抽取的字符数上限，超出截断（标记 truncated）。 */
    public static final int MAX_CHARS_PER_FILE = 200_000;

    /** 判断二进制时只看开头这么多字节。 */
    private static final int SNIFF_BYTES = 8192;

    /** 直接按文本读的扩展名。 */
    private static final Set<String> PLAIN_TEXT = Set.of(
            "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yml", "yaml",
            "properties", "ini", "conf", "cfg", "env", "toml", "sql", "html", "htm", "xhtml",
            "css", "scss", "less", "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue", "svelte",
            "java", "kt", "kts", "scala", "groovy", "py", "pyw", "c", "h", "cc", "cpp", "cxx",
            "hpp", "hh", "cs", "go", "rs", "rb", "php", "pl", "lua", "swift", "dart",
            "sh", "bash", "zsh", "bat", "cmd", "ps1", "vbs", "gradle", "pom", "proto", "graphql");

    /** ZIP + XML 的 Office 格式。 */
    private static final Set<String> ZIP_OFFICE = Set.of("docx", "xlsx", "pptx");

    /** 跳过原因。 */
    public enum Skip {
        NONE("已抽取"),
        UNSUPPORTED("不支持的类型"),
        TOO_LARGE("文件过大"),
        BINARY("看起来是二进制文件"),
        OLE2("已加密或旧版 Office 格式"),
        EMPTY("没有可索引的文字"),
        ERROR("读取失败");

        private final String label;

        Skip(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 抽取结果。
     *
     * @param text      纯文本；被跳过时为 null
     * @param skip      跳过原因；成功时为 {@link Skip#NONE}
     * @param truncated 是否因为超过字符上限而被截断
     * @param detail    错误详情，便于排查
     */
    public record Extraction(String text, Skip skip, boolean truncated, String detail) {

        public static Extraction ok(String text, boolean truncated) {
            return new Extraction(text, Skip.NONE, truncated, "");
        }

        public static Extraction skipped(Skip skip, String detail) {
            return new Extraction(null, skip, false, detail);
        }

        public boolean succeeded() {
            return skip == Skip.NONE && text != null && !text.isBlank();
        }
    }

    private ContentExtractor() {
    }

    /** 这个条目是否值得尝试抽取。 */
    public static boolean isSupported(FileItem item) {
        if (item == null || item.directory()) {
            return false;
        }
        return isSupportedExtension(item.ext());
    }

    /** 这个扩展名是否受支持。 */
    public static boolean isSupportedExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        return PLAIN_TEXT.contains(ext) || ZIP_OFFICE.contains(ext) || "pdf".equals(ext);
    }

    /** 抽取文本。任何异常都转成对应的 {@link Skip}，不向上抛。 */
    public static Extraction extract(Path path) {
        if (path == null) {
            return Extraction.skipped(Skip.ERROR, "路径为空");
        }
        try {
            if (!Files.isRegularFile(path)) {
                return Extraction.skipped(Skip.ERROR, "不是普通文件");
            }
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) {
                return Extraction.skipped(Skip.TOO_LARGE,
                        "大小 " + (size / 1024 / 1024) + " MB，超过上限 "
                                + (MAX_FILE_BYTES / 1024 / 1024) + " MB");
            }
            if (size == 0) {
                return Extraction.skipped(Skip.EMPTY, "空文件");
            }

            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            String ext = FileItem.extensionOf(name);

            String text;
            if (PLAIN_TEXT.contains(ext)) {
                // 二进制探测必须在读文本之前做，否则"是二进制"这个结论会在后面
                // 被统一归成"没有文字"，用户就看不到真正的原因
                byte[] bytes = Files.readAllBytes(path);
                if (looksBinary(bytes)) {
                    return Extraction.skipped(Skip.BINARY,
                            "开头 " + SNIFF_BYTES + " 字节内包含 NUL 字节");
                }
                text = decode(bytes);
            } else if (ZIP_OFFICE.contains(ext)) {
                if (hasOle2Header(path)) {
                    // 密码保护的 docx/xlsx 会被 Office 整个包成 OLE2 复合文档，
                    // 它<b>不是</b> ZIP，所以直接去解压只会得到 "zip END header not found"。
                    // 那句报错会让人以为文件坏了，其实文件好好的、只是加密了。
                    return Extraction.skipped(Skip.OLE2,
                            "文件头是 OLE2 复合文档：可能是设置了打开密码的 "
                                    + ext + "，或扩展名被改成 ." + ext + " 的旧版 .doc/.xls");
                }
                text = readOfficeXml(path, ext);
            } else if ("pdf".equals(ext)) {
                text = readPdf(path);
            } else {
                return Extraction.skipped(Skip.UNSUPPORTED, "扩展名 ." + ext);
            }

            if (text == null || text.isBlank()) {
                return Extraction.skipped(Skip.EMPTY, "没有可索引的文字");
            }
            if (text.length() > MAX_CHARS_PER_FILE) {
                return Extraction.ok(text.substring(0, MAX_CHARS_PER_FILE), true);
            }
            return Extraction.ok(text, false);
        } catch (IOException | RuntimeException e) {
            return Extraction.skipped(Skip.ERROR,
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 纯文本


    /**
     * 文件是否是 OLE2 复合文档（{@code D0 CF 11 E0 A1 B1 1A E1}）。
     *
     * <p>这个魔数覆盖两种情况：<b>加密的 OOXML</b>（Office 用 OLE2 把加密后的 ZIP 包起来）
     * 与<b>旧版二进制 Office</b>（.doc/.xls/.ppt）。两者都拿不到正文，但都必须与
     * "文件真的坏了"区分开——用户看到"文件已加密"会去输密码，看到"读取失败"只会以为文件损坏。
     *
     * <p>读不出来（文件不存在、无权限）时返回 false，交给后续分支报出真实原因。
     */
    static boolean hasOle2Header(Path path) {
        byte[] head = new byte[8];
        try (InputStream in = Files.newInputStream(path)) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < head.length) {
                return false;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF
                && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0
                && (head[4] & 0xFF) == 0xA1 && (head[5] & 0xFF) == 0xB1
                && (head[6] & 0xFF) == 0x1A && (head[7] & 0xFF) == 0xE1;
    }

    /**
     * 开头出现 NUL 基本可以断定不是文本。
     *
     * <p>只适用于纯文本类扩展名：docx 之类的 ZIP 容器里当然有 NUL，
     * 但那些走的是另一条分支，不会经过这里。
     */
    static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 UTF-8 严格解码，失败退回 GBK。
     *
     * <p>用严格模式而不是 {@code new String(bytes, UTF_8)}：后者的非法字节会被静默替换成
     * {@code U+FFFD}，于是 GBK 的中文变成一串问号，搜索就永远搜不到——而且还看不出错。
     */
    static String decode(byte[] bytes) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // 中文 Windows 上最常见的另一种编码
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    // ------------------------------------------------------------- Office XML

    /** 从 docx/xlsx/pptx 里取出文字。 */
    static String readOfficeXml(Path path, String extension) throws IOException {
        StringBuilder out = new StringBuilder();
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (!isTextBearingEntry(entryName, extension)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    appendXmlText(out, new String(in.readAllBytes(), StandardCharsets.UTF_8), extension);
                }
                if (out.length() > MAX_CHARS_PER_FILE) {
                    break;
                }
            }
        } catch (IOException e) {
            // 有些"docx"其实是别的东西；当成读取失败并保留原因
            throw new IOException("不是有效的 " + extension + " 文件：" + e.getMessage(), e);
        }
        return out.toString();
    }

    /** 哪些 ZIP 内部条目真的装着文字。刻意排除图片、主题、关系表等噪声。 */
    static boolean isTextBearingEntry(String entryName, String extension) {
        // 注意：entryName 已经转成小写，所以这里的字面量也必须是小写。
        // 早期版本写成了 "xl/sharedStrings.xml"（大写 S），导致 xlsx 的单元格文字
        // 永远抽不到——只能搜到工作表里的数字，而且完全不报错。
        String n = entryName.toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "docx" -> n.equals("word/document.xml");
            case "xlsx" -> n.equals("xl/sharedstrings.xml")
                    || (n.startsWith("xl/worksheets/") && n.endsWith(".xml"));
            case "pptx" -> n.startsWith("ppt/slides/slide") && n.endsWith(".xml");
            default -> false;
        };
    }

    /**
     * 把一段 Office XML 转成可搜索的文本。
     *
     * <p>做法是"按段落切开 → 去掉标签 → 解实体"。
     * 段落边界要保留成换行，否则摘要会变成一大坨连在一起的文字，看不出上下文。
     */
    static void appendXmlText(StringBuilder out, String xml, String extension) {
        String withBreaks = xml
                .replace("</w:p>", "\n")      // docx：段落
                .replace("</a:p>", "\n")      // pptx：段落
                .replace("</si>", "\n")       // xlsx：共享字符串一条
                .replace("</row>", "\n");     // xlsx：行
        String stripped = withBreaks.replaceAll("<[^>]*>", "");
        out.append(decodeEntities(stripped));
        out.append('\n');
    }

    /** 解开 XML 里最常见的几个实体。 */
    static String decodeEntities(String text) {
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    // --------------------------------------------------------------------- PDF

    /** 用 PDFBox 抽取 PDF 文本。 */
    static String readPdf(Path path) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     org.apache.pdfbox.Loader.loadPDF(path.toFile())) {
            org.apache.pdfbox.text.PDFTextStripper stripper =
                    new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(false);
            return stripper.getText(document);
        }
    }
}
