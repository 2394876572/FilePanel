package com.zean.filepanel.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件类型分类。
 *
 * <p>分类只由扩展名决定，不读文件内容——这是有意的：读内容判断类型要付 IO 代价，
 * 而分类的唯一用途是侧栏筛选与图标配色，扩展名足够。
 *
 * <p>每个分类同时有英文 {@link #key()} 与中文 {@link #displayName()}：
 * 前者供搜索语法（{@code type:image}）与持久化使用，后者供界面展示。
 * 搜索时两者都能输入，中文用户不必记英文。
 *
 * <p>枚举顺序即侧栏展示顺序（{@link #ordinal()}），DIRECTORY 排最前便于“显示文件夹”时置顶。
 */
public enum FileKind {

    DIRECTORY("directory", "文件夹"),
    DOCUMENT("document", "文档"),
    SPREADSHEET("spreadsheet", "表格"),
    PRESENTATION("presentation", "演示"),
    IMAGE("image", "图片"),
    AUDIO("audio", "音频"),
    VIDEO("video", "视频"),
    ARCHIVE("archive", "压缩包"),
    CODE("code", "代码"),
    EXECUTABLE("executable", "可执行"),
    OTHER("other", "其他");

    private final String key;
    private final String displayName;

    FileKind(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    /** 英文标识，用于搜索语法与持久化。 */
    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    /** 英文 key 与中文名到枚举的查找表。 */
    private static final Map<String, FileKind> BY_KEY = buildKeyIndex();

    private static Map<String, FileKind> buildKeyIndex() {
        Map<String, FileKind> m = new HashMap<>();
        for (FileKind k : values()) {
            m.put(k.key, k);
            m.put(k.displayName, k);
        }
        return Map.copyOf(m);
    }

    /**
     * 按英文 key 或中文名解析（忽略大小写与首尾空白）。
     *
     * @return 无法识别时返回 null，由调用方决定如何降级——不抛异常，因为输入来自用户的实时输入框
     */
    public static FileKind ofKey(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        FileKind direct = BY_KEY.get(t);
        if (direct != null) {
            return direct;
        }
        return BY_KEY.get(t.toLowerCase(java.util.Locale.ROOT));
    }

    /** 扩展名 -> 分类。key 一律为小写、不含点。 */
    private static final Map<String, FileKind> BY_EXTENSION = buildIndex();

    private static Map<String, FileKind> buildIndex() {
        Map<String, FileKind> m = new HashMap<>(512);
        put(m, DOCUMENT, "doc", "docx", "dot", "dotx", "odt", "rtf", "pdf", "txt", "md", "markdown",
                "log", "tex", "wps", "pages", "epub", "mobi", "chm", "xps");
        put(m, SPREADSHEET, "xls", "xlsx", "xlsm", "xlsb", "csv", "tsv", "ods", "et", "numbers");
        put(m, PRESENTATION, "ppt", "pptx", "pptm", "odp", "dps", "key");
        put(m, IMAGE, "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tif", "tiff",
                "psd", "ai", "eps", "raw", "heic", "heif", "avif", "jfif", "wmf", "emf");
        put(m, AUDIO, "mp3", "wav", "flac", "aac", "ogg", "oga", "m4a", "wma", "ape", "aiff",
                "mid", "midi", "opus");
        put(m, VIDEO, "mp4", "avi", "mkv", "mov", "wmv", "flv", "rmvb", "rm", "m4v", "webm",
                "mpg", "mpeg", "3gp", "vob", "swf");
        put(m, ARCHIVE, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz", "lzma",
                "jar", "war", "ear", "iso", "cab", "arj");
        put(m, CODE, "java", "kt", "kts", "scala", "groovy", "py", "pyw", "js", "mjs", "cjs",
                "ts", "tsx", "jsx", "vue", "svelte", "c", "h", "cc", "cpp", "cxx", "hpp", "hh",
                "cs", "go", "rs", "rb", "php", "pl", "lua", "swift", "dart", "sql",
                "sh", "bash", "zsh", "bat", "cmd", "ps1", "psm1", "vbs",
                "html", "htm", "xhtml", "css", "scss", "sass", "less",
                "json", "json5", "xml", "xsd", "yml", "yaml", "toml", "properties", "conf", "cfg",
                "ini", "env", "gradle", "pom", "jsp", "asp", "aspx", "typed", "proto", "graphql", "gql");
        put(m, EXECUTABLE, "exe", "msi", "dll", "sys", "ocx", "com", "scr", "cpl", "drv",
                "so", "dylib", "apk", "appx", "msix", "deb", "rpm", "bin");
        return Map.copyOf(m);
    }

    private static void put(Map<String, FileKind> m, FileKind kind, String... extensions) {
        for (String e : extensions) {
            FileKind previous = m.put(e, kind);
            if (previous != null) {
                // 归类表写重了要立刻炸，而不是运行时静默取到不确定的分类
                throw new IllegalStateException("扩展名 " + e + " 被重复归类：" + previous + " / " + kind);
            }
        }
    }

    /**
     * 按扩展名归类。扩展名为空或不认识时返回 {@link #OTHER}，永不返回 null。
     *
     * @param extension 小写、不含点的扩展名；允许为 null
     */
    public static FileKind ofExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return OTHER;
        }
        return BY_EXTENSION.getOrDefault(extension, OTHER);
    }

    /** 已知扩展名总数，供自检与测试断言使用。 */
    public static int knownExtensionCount() {
        return BY_EXTENSION.size();
    }

    /** 某个分类下已登记的全部扩展名（已排序），供后续设置界面展示。 */
    public static List<String> knownExtensionsOf(FileKind kind) {
        return BY_EXTENSION.entrySet().stream()
                .filter(e -> e.getValue() == kind)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
