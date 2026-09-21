package com.zean.filepanel.core;

import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 一条已解析的搜索查询。
 *
 * <p>设计目标：
 * <ol>
 *   <li><b>绝不因输入出错而不工作</b>：任何无法识别的片段都降级为普通子串匹配，
 *       用户打到一半的 {@code size:>} 不会让列表突然清空或报错。</li>
 *   <li><b>可解释</b>：{@link #describe()} 用自然语言说明这条查询到底在筛什么，
 *       既方便用户在界面上确认自己的意图，也方便测试断言。</li>
 *   <li><b>与标签/收藏解耦</b>：M2 阶段标签与收藏还不存在，
 *       但语法（{@code tag:} / {@code fav:}）先解析好；调用方传空集合即自然匹配为空，
 *       M4 只需把真实数据传进来，无需改动解析器。</li>
 * </ol>
 *
 * <p>多个条件之间是 <b>AND</b> 关系。
 */
public final class SearchQuery {

    /** 空查询：匹配一切。 */
    public static final SearchQuery EMPTY = new SearchQuery(List.of(), "");

    /** 比较运算符。统一作用于 {@code Integer.compare(a, b)} 的结果，因此数值与日期共用一套实现。 */
    public enum Op {
        GT, GE, LT, LE, EQ;

        public boolean test(int cmp) {
            return switch (this) {
                case GT -> cmp > 0;
                case GE -> cmp >= 0;
                case LT -> cmp < 0;
                case LE -> cmp <= 0;
                case EQ -> cmp == 0;
            };
        }

        public String symbol() {
            return switch (this) {
                case GT -> ">";
                case GE -> "≥";
                case LT -> "<";
                case LE -> "≤";
                case EQ -> "=";
            };
        }

        /**
         * 中文说法，供界面下拉显示。
         *
         * <p>刻意不覆盖 {@code toString()}：枚举名被日志、测试与提示文本大量引用，
         * 改掉它会让"GT"这种稳定标识在排查时消失。界面需要中文就单独取 {@code label()}。
         */
        public String label() {
            return switch (this) {
                case GT -> "大于";
                case GE -> "不小于";
                case LT -> "小于";
                case LE -> "不大于";
                case EQ -> "等于";
            };
        }
    }

    /** 单个条件。每个条件自带判定与自述，避免中心化的 switch 分支随着语法增长而失控。 */
    public sealed interface Term {
        boolean test(FileItem item, Collection<String> tags, boolean favorite, String content);

        /** 自然语言描述，用于界面提示与测试断言。 */
        String describe();
    }

    /** 子串匹配：文件名或相对路径包含该文本。 */
    public record TextTerm(String value) implements Term {        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            String needle = Glob.lower(value);
            if (needle.isEmpty()) {
                return true;
            }
            return Glob.lower(item.name()).contains(needle)
                    || Glob.lower(item.relPath()).contains(needle);
        }

        @Override
        public String describe() {
            return "包含「" + value + "」";
        }
    }

    /**
     * 只匹配文件名（搜索范围选"文件名"时的裸关键词）。
     *
     * <p>与 {@link TextTerm} 的区别只有一处：<b>不看所在路径</b>。
     * 单独做一个 record 而不是给 TextTerm 加个布尔开关，是为了让
     * {@code describe()} 能如实说出"文件名包含"还是"包含"——
     * 用户必须能从提示里看出自己这个范围到底生效了没有。
     */
    public record NameTerm(String value) implements Term {
        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            String needle = Glob.lower(value);
            if (needle.isEmpty()) {
                return true;
            }
            return Glob.lower(item.name()).contains(needle);
        }

        @Override
        public String describe() {
            return "文件名包含「" + value + "」";
        }
    }

    /** 通配符匹配：只针对文件名整体匹配。 */
    public record GlobTerm(String glob, Pattern pattern) implements Term {        public static GlobTerm of(String glob) {
            return new GlobTerm(glob, Glob.toPattern(glob));
        }

        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            return pattern.matcher(item.name()).matches();
        }

        @Override
        public String describe() {
            return "文件名匹配 " + glob;
        }
    }

    /** 扩展名匹配（不含点、小写）。 */
    public record ExtensionTerm(Set<String> extensions) implements Term {
        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            return !item.directory() && extensions.contains(item.ext());
        }

        @Override
        public String describe() {
            return "扩展名 " + extensions.stream().sorted().collect(Collectors.joining("/"));
        }
    }

    /** 类型分类匹配。 */
    public record KindTerm(EnumSet<FileKind> kinds) implements Term {
        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            return kinds.contains(item.kind());
        }

        @Override
        public String describe() {
            return "类型 " + kinds.stream().map(FileKind::displayName).collect(Collectors.joining("/"));
        }
    }

    /** 大小比较。目录不参与（目录没有“大小”语义）。 */
    public record SizeTerm(Op op, long bytes) implements Term {
        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            return !item.directory() && op.test(Long.compare(item.size(), bytes));
        }

        @Override
        public String describe() {
            return "大小 " + op.symbol() + " " + humanSize(bytes);
        }
    }

    /** 修改时间比较，支持绝对日期与相对天数。 */
    public record ModifiedTerm(Op op, LocalDate date) implements Term {
        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            LocalDate actual = toLocalDate(item.modified());
            return actual != null && op.test(actual.compareTo(date));
        }

        @Override
        public String describe() {
            return "修改时间 " + op.symbol() + " " + date;
        }
    }

    /**
     * 文件内容匹配。
     *
     * <p>{@code content == null} 表示这个文件还没有被索引——此时<b>返回 false 而不是报错</b>：
     * 索引是渐进的，报错会让用户在索引过程中看到莫名其妙的失败。
     * 界面需要另外说明"内容尚未索引"，否则用户会把"还没索引完"误读成"确实没有"。
     */
    public record ContentTerm(String value) implements Term {
        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            if (content == null || value.isEmpty()) {
                return false;
            }
            return Glob.lower(content).contains(Glob.lower(value));
        }

        /**
         * 取出命中位置附近的一小段文字，供界面显示"在哪里命中的"。
         *
         * @return 摘要；未命中时返回空串
         */
        public String snippet(String content) {
            if (content == null || value.isEmpty()) {
                return "";
            }
            int at = Glob.lower(content).indexOf(Glob.lower(value));
            if (at < 0) {
                return "";
            }
            int start = Math.max(0, at - 24);
            int end = Math.min(content.length(), at + value.length() + 48);
            // 注意是 "\\s+"（一个反斜杠）：写成 "\\\\s+" 会变成"字面反斜杠 + s"，
            // 换行根本不会被替换，摘要在单元格里会显示成多行
            String window = content.substring(start, end).replaceAll("\\s+", " ").trim();
            return (start > 0 ? "…" : "") + window + (end < content.length() ? "…" : "");
        }

        @Override
        public String describe() {
            return "内容包含「" + value + "」";
        }
    }

    /** 标签匹配（精确、忽略大小写）。 */
    public record TagTerm(String value) implements Term {
        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            if (tags == null || tags.isEmpty()) {
                return false;
            }
            String needle = Glob.lower(value);
            for (String t : tags) {
                if (Glob.lower(t).equals(needle)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String describe() {
            return "标签「" + value + "」";
        }
    }

    /** 收藏状态匹配。 */
    public record FavoriteTerm(boolean wanted) implements Term {
        @Override
        public boolean test(FileItem item, Collection<String> tags, boolean favorite, String content) {
            return favorite == wanted;
        }

        @Override
        public String describe() {
            return wanted ? "只看收藏" : "排除收藏";
        }
    }

    private final List<Term> terms;
    private final String raw;

    SearchQuery(List<Term> terms, String raw) {
        this.terms = List.copyOf(terms);
        this.raw = raw == null ? "" : raw;
    }

    public List<Term> terms() {
        return terms;
    }

    /** 用户输入的原文，便于界面回显与调试。 */
    public String raw() {
        return raw;
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /**
     * 是否命中。
     *
     * @param tags     该文件的标签
     * @param favorite 该文件是否已收藏
     * @param content  该文件的正文；<b>未索引时为 null</b>
     */
    public boolean matches(FileItem item, Collection<String> tags, boolean favorite, String content) {
        if (item == null) {
            return false;
        }
        for (Term t : terms) {
            if (!t.test(item, tags, favorite, content)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 便捷判定：不涉及内容搜索。
     *
     * <p><b>注意</b>：正文按 null 处理，因此含 {@code content:} 条件的查询在这里一律不命中——
     * 这是刻意的："没有正文"和"正文里没有这个词"在结果上无法区分，
     * 而把未知当成命中会造成假阳性。需要内容搜索时必须用四参数版本。
     */
    public boolean matches(FileItem item, Collection<String> tags, boolean favorite) {
        return matches(item, tags, favorite, null);
    }

    /** 便捷判定：不带标签、收藏与正文。 */
    public boolean matches(FileItem item) {
        return matches(item, Set.of(), false, null);
    }

    /**
     * 这条查询是否需要文件正文。
     *
     * <p>界面据此决定要不要为每个条目去查内容索引——没有内容条件时直接跳过查找，
     * 让普通搜索保持零额外开销。
     */
    public boolean needsContent() {
        for (Term t : terms) {
            if (t instanceof ContentTerm) {
                return true;
            }
        }
        return false;
    }

    /** 所有内容条件的检索词，供界面生成命中摘要。 */
    public List<String> contentTerms() {
        List<String> out = new java.util.ArrayList<>();
        for (Term t : terms) {
            if (t instanceof ContentTerm term) {
                out.add(term.value());
            }
        }
        return out;
    }

    /** 自然语言描述，例如 {@code 类型 图片 · 大小 > 1.00 MB}；空查询返回空串。 */
    public String describe() {
        return terms.stream().map(Term::describe).collect(Collectors.joining(" · "));
    }

    static LocalDate toLocalDate(FileTime time) {
        if (time == null) {
            return null;
        }
        Instant instant = time.toInstant();
        return instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }

    static String humanSize(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        if (u == 0) {
            return bytes + " B";
        }
        return String.format(Locale.ROOT, "%.2f %s", v, units[u]);
    }
}
