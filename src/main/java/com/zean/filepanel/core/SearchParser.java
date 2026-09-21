package com.zean.filepanel.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索语法解析器。
 *
 * <h2>支持的语法</h2>
 * <table>
 *   <tr><td>{@code 灰度发布}</td><td>文件名或相对路径包含（忽略大小写，中文直接匹配）</td></tr>
 *   <tr><td>{@code "会议 记录"}</td><td>带空格的短语，用双引号包起来</td></tr>
 *   <tr><td>{@code *.pdf}、{@code ?.log}</td><td>文件名通配符</td></tr>
 *   <tr><td>{@code .docx}、{@code ext:docx,xlsx}</td><td>扩展名（可多个，逗号分隔）</td></tr>
 *   <tr><td>{@code type:image}、{@code type:图片,表格}</td><td>类型分类，英文 key 与中文名都认</td></tr>
 *   <tr><td>{@code size:>10MB}、{@code size:<=100KB}</td><td>大小比较，支持 B/KB/MB/GB/TB</td></tr>
 *   <tr><td>{@code modified:>2026-01-01}、{@code modified:>7d}</td><td>修改时间，绝对日期或相对 N[天周月年]</td></tr>
 *   <tr><td>{@code tag:重要}、{@code fav:true}</td><td>标签与收藏</td></tr>
 *   <tr><td>{@code content:关键词}</td><td>搜索文件正文（渐进索引；未索引的文件不算命中）</td></tr>
 * </table>
 * 多个条件以空格分隔，含义是 <b>AND</b>。
 *
 * <h2>降级原则</h2>
 * 任何解析不出的片段（写错的单位、打了一半的 {@code size:>}、未识别的 {@code foo:bar}）
 * <b>一律退化为普通子串匹配</b>，绝不抛异常、绝不清空列表。
 * 理由是搜索框是逐字符触发的：用户在输入过程中必然会经过大量“语法上不合法”的中间状态，
 * 如果那时报错或闪空，搜索功能就没法用了。
 */
public final class SearchParser {

    private SearchParser() {
    }

    /** 大小：运算符（可选）+ 数值（可带小数）+ 单位。单位留空按字节。 */
    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "^(>=|<=|>|<|=)?\\s*(\\d+(?:\\.\\d+)?)\\s*([a-zA-Z\\u4e00-\\u9fa5]*)$");

    /** 时间：运算符（可选）+ 绝对日期或相对量。 */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "^(>=|<=|>|<|=)?\\s*(\\d{4}-\\d{1,2}-\\d{1,2}|\\d+[dwmy])$");

    /** 裸写的单扩展名，如 {@code .docx}。限制长度以免把 {@code .gitignore} 这类文件名误判成扩展名。 */
    private static final Pattern BARE_EXTENSION = Pattern.compile("^\\.[a-zA-Z0-9]{1,5}$");

    /**
     * 带比较运算符的键：{@code size:} / {@code modified:}。
     *
     * <p>用于消除运算符周围的空格——否则分词器会把 {@code size:> 10MB} 拆成
     * {@code size:>} 与 {@code 10MB} 两个词，前一个解析失败降级成文本，整条查询静默失效。
     * 用户写不写这个空格纯属习惯，不该成为功能是否生效的分界线。
     */
    private static final Pattern OPERATOR_KEY = Pattern.compile(
            "(?i)(size|modified|mtime|大小|修改时间)\\s*:\\s*(>=|<=|>|<|=)\\s*");

    /**
     * 允许运算符前后出现空格：{@code size:> 10MB}、{@code size: >10MB} 与 {@code size:>10MB} 等价。
     *
     * <p>注意<b>不</b>处理数值与单位之间的空格：{@code size:>10 MB} 仍然无效，
     * 因为那会与“多个条件用空格分隔”的规则冲突（无法区分 {@code 10 MB} 是值还是两个条件）。
     * 需要在值里放空格时用双引号，例如 {@code "size:> 10 MB"}。
     */
    static String normalizeOperatorSpacing(String raw) {
        return OPERATOR_KEY.matcher(raw).replaceAll("$1:$2");
    }

    public static SearchQuery parse(String raw) {
        return parse(raw, SearchScope.ALL, SearchQuery.Op.GT);
    }

    /**
     * 按指定范围解析。
     *
     * @param scope       裸关键词应该去哪几个字段里找
     * @param sizeDefault 范围选"大小"时的默认比较方式（用户没写 {@code >} 的时候用）
     */
    public static SearchQuery parse(String raw, SearchScope scope, SearchQuery.Op sizeDefault) {
        return parseWithNotes(raw, scope, sizeDefault).query();
    }

    /**
     * 解析结果 + 需要告诉用户的话。
     *
     * <p>重点是 {@code notes}：范围选"类型"却打了一个不认识的词时，我们仍然会把它当普通文字搜
     * （沿用"绝不因解析失败而清空列表"的原则），但<b>必须说出来</b>——
     * 否则用户看到的是"选了类型却搜出一堆名字里带这俩字的东西"，只会觉得功能坏了。
     */
    public record ParseResult(SearchQuery query, List<String> notes) {
    }

    public static ParseResult parseWithNotes(String raw, SearchScope scope,
                                             SearchQuery.Op sizeDefault) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult(SearchQuery.EMPTY, List.of());
        }
        SearchScope effectiveScope = scope == null ? SearchScope.ALL : scope;
        SearchQuery.Op effectiveSizeOp = sizeDefault == null ? SearchQuery.Op.GT : sizeDefault;

        List<SearchQuery.Term> terms = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String token : tokenize(normalizeOperatorSpacing(raw))) {
            SearchQuery.Term parsed = parseToken(token);
            if (parsed instanceof SearchQuery.TextTerm text) {
                // 走到这里说明这个词没有任何显式语法（普通关键词、引号短语、或写错的 foo:bar）。
                // 只有这种词才受"范围"影响——显式语法的优先级永远更高。
                applyScope(text.value(), effectiveScope, effectiveSizeOp, terms, notes);
            } else {
                terms.add(parsed);
            }
        }
        if (terms.isEmpty()) {
            return new ParseResult(SearchQuery.EMPTY, List.copyOf(notes));
        }
        return new ParseResult(new SearchQuery(terms, raw), List.copyOf(notes));
    }

    /** 把裸关键词按范围转成对应的条件；范围解释不了就退回普通文字并记一条说明。 */
    private static void applyScope(String value, SearchScope scope, SearchQuery.Op sizeDefault,
                                   List<SearchQuery.Term> terms, List<String> notes) {
        switch (scope) {
            case ALL -> terms.add(new SearchQuery.TextTerm(value));
            case NAME -> terms.add(new SearchQuery.NameTerm(value));
            case KIND -> {
                SearchQuery.Term kinds = parseKinds(value);
                if (kinds != null) {
                    terms.add(kinds);
                } else {
                    terms.add(new SearchQuery.TextTerm(value));
                    notes.add("「" + value + "」不是已知类型，已按普通文字搜索");
                }
            }
            case SIZE -> {
                SearchQuery.Term size = parseSize(value, sizeDefault);
                if (size != null) {
                    terms.add(size);
                } else {
                    terms.add(new SearchQuery.TextTerm(value));
                    notes.add("「" + value + "」不是有效的大小（例如写 10MB），已按普通文字搜索");
                }
            }
            default -> terms.add(new SearchQuery.TextTerm(value));
        }
    }

    /**
     * 分词：按空白切分，但双引号内的空白不作为分隔符。
     *
     * <p>不接受单引号：中文输入法下用户很难察觉自己打的是全角引号还是半角，
     * 只支持双引号并把 {@code “”} 也归一化处理，减少“明明加了引号却不生效”的困惑。
     */
    static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        String normalized = raw.replace('“', '"').replace('”', '"');
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '"') {
                if (inQuotes) {
                    // 闭合引号：即使内容为空也要产出一个空词，由 parseToken 决定含义
                    tokens.add(current.toString());
                    current.setLength(0);
                    inQuotes = false;
                } else {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    inQuotes = true;
                }
                continue;
            }
            if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        // 未闭合的引号：把已积累的内容当作普通词，避免用户打了一半就整条查询失效
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** 解析单个词；无法识别时返回子串匹配词。 */
    static SearchQuery.Term parseToken(String token) {
        if (token == null) {
            return new SearchQuery.TextTerm("");
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return new SearchQuery.TextTerm("");
        }

        int colon = trimmed.indexOf(':');
        // colon > 0：排除 ":foo" 这种以冒号开头的词
        if (colon > 0) {
            String key = Glob.lower(trimmed.substring(0, colon));
            String value = trimmed.substring(colon + 1);
            SearchQuery.Term term = switch (key) {
                case "ext", "extname", "扩展名" -> parseExtensions(value);
                case "type", "kind", "类型" -> parseKinds(value);
                case "size", "大小" -> parseSize(value);
                case "modified", "mtime", "修改时间" -> parseModified(value);
                case "tag", "标签" -> value.isBlank() ? null : new SearchQuery.TagTerm(value.trim());
                // 内容搜索：只在显式写了 content: 时才触发。
                // 不做成"每次搜索都顺便搜正文"——抽取正文是秒级开销，不该让普通搜索背上它。
                case "content", "内容", "全文" ->
                        value.isBlank() ? null : new SearchQuery.ContentTerm(value.trim());
                case "fav", "favorite", "收藏" -> parseFavorite(value);
                default -> null;   // 未识别的键 -> 整体降级为文本
            };
            if (term != null) {
                return term;
            }
            // 落到文本匹配（例如用户搜的就是含冒号的文件名）
            return new SearchQuery.TextTerm(trimmed);
        }

        if (Glob.looksLikeGlob(trimmed)) {
            return SearchQuery.GlobTerm.of(trimmed);
        }
        if (BARE_EXTENSION.matcher(trimmed).matches()) {
            return new SearchQuery.ExtensionTerm(Set.of(Glob.lower(trimmed.substring(1))));
        }
        return new SearchQuery.TextTerm(trimmed);
    }

    private static SearchQuery.Term parseExtensions(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Set<String> exts = new LinkedHashSet<>();
        for (String part : value.split("[,，;；\\s]+")) {
            String e = Glob.lower(part.trim());
            if (e.startsWith(".")) {
                e = e.substring(1);
            }
            if (!e.isEmpty()) {
                exts.add(e);
            }
        }
        return exts.isEmpty() ? null : new SearchQuery.ExtensionTerm(Set.copyOf(exts));
    }

    private static SearchQuery.Term parseKinds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        EnumSet<FileKind> kinds = EnumSet.noneOf(FileKind.class);
        for (String part : value.split("[,，;；\\s]+")) {
            FileKind kind = FileKind.ofKey(part);
            if (kind != null) {
                kinds.add(kind);
            }
        }
        return kinds.isEmpty() ? null : new SearchQuery.KindTerm(kinds);
    }

    private static SearchQuery.Term parseSize(String value) {
        return parseSize(value, SearchQuery.Op.GE);
    }

    /**
     * @param defaultOp 没写比较运算符时用哪个。搜索范围选"大小"时由界面传 {@code >}，
     *                  因为那时用户的意图通常是"大于这个大小"；写在语法里时沿用历史默认 {@code >=}
     */
    private static SearchQuery.Term parseSize(String value, SearchQuery.Op defaultOp) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher m = SIZE_PATTERN.matcher(value.trim());
        if (!m.matches()) {
            return null;
        }
        String symbol = m.group(1);
        SearchQuery.Op op = (symbol == null || symbol.isBlank()) ? defaultOp : toOp(symbol);
        double number;
        try {
            number = Double.parseDouble(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        long multiplier = unitMultiplier(m.group(3));
        if (multiplier <= 0) {
            return null;
        }
        double bytes = number * multiplier;
        if (bytes < 0 || bytes > Long.MAX_VALUE) {
            return null;
        }
        return new SearchQuery.SizeTerm(op, (long) bytes);
    }

    /**
     * 单位换算。返回 0 表示单位无法识别（调用方据此降级为文本匹配）。
     *
     * <p>接受常见写法与中文单位；{@code K}/{@code KB}/{@code 千字节} 等价。
     * 注意 {@code b} 既可能是 byte 也可能是 KB 的误写，这里按“字节”处理并只在明确无单位时省略。
     */
    static long unitMultiplier(String unit) {
        if (unit == null || unit.isBlank()) {
            return 1L;
        }
        String u = Glob.lower(unit.trim());
        return switch (u) {
            case "b", "byte", "bytes", "字节" -> 1L;
            case "k", "kb", "kib", "千字节" -> 1024L;
            case "m", "mb", "mib", "兆", "兆字节" -> 1024L * 1024;
            case "g", "gb", "gib", "吉", "吉字节" -> 1024L * 1024 * 1024;
            case "t", "tb", "tib", "太", "太字节" -> 1024L * 1024 * 1024 * 1024;
            default -> 0L;
        };
    }

    private static SearchQuery.Term parseModified(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher m = DATE_PATTERN.matcher(value.trim());
        if (!m.matches()) {
            return null;
        }
        SearchQuery.Op op = toOp(m.group(1));
        String body = m.group(2);
        LocalDate date;
        try {
            if (body.indexOf('-') > 0) {
                date = LocalDate.parse(normalizeDate(body));
            } else {
                date = relativeDate(body);
            }
        } catch (RuntimeException e) {
            return null;
        }
        return date == null ? null : new SearchQuery.ModifiedTerm(op, date);
    }

    /** 把 {@code 2026-1-5} 补零成 {@code 2026-01-05}，让 {@link LocalDate#parse} 能接受宽松写法。 */
    static String normalizeDate(String body) {
        String[] parts = body.split("-");
        if (parts.length != 3) {
            return body;
        }
        return parts[0] + "-" + pad2(parts[1]) + "-" + pad2(parts[2]);
    }

    private static String pad2(String s) {
        return s.length() >= 2 ? s : "0" + s;
    }

    /** 相对时间：{@code 7d} = 七天前，{@code 2w} 周、{@code 3m} 月、{@code 1y} 年。 */
    static LocalDate relativeDate(String body) {
        char unitChar = body.charAt(body.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(body.substring(0, body.length() - 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount < 0) {
            return null;
        }
        LocalDate today = LocalDate.now();
        return switch (Character.toLowerCase(unitChar)) {
            case 'd' -> today.minusDays(amount);
            case 'w' -> today.minusWeeks(amount);
            case 'm' -> today.minusMonths(amount);
            case 'y' -> today.minusYears(amount);
            default -> null;
        };
    }

    private static SearchQuery.Term parseFavorite(String value) {
        String v = Glob.lower(value == null ? "" : value.trim());
        return switch (v) {
            case "true", "1", "yes", "y", "是", "收藏" -> new SearchQuery.FavoriteTerm(true);
            case "false", "0", "no", "n", "否" -> new SearchQuery.FavoriteTerm(false);
            default -> null;
        };
    }

    /** 缺省运算符的含义取决于语境：大小/时间用“大于等于”比“等于”更符合直觉（{@code size:10MB} ≈ 10MB 以上）。 */
    private static SearchQuery.Op toOp(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return SearchQuery.Op.GE;
        }
        return switch (symbol) {
            case ">" -> SearchQuery.Op.GT;
            case ">=" -> SearchQuery.Op.GE;
            case "<" -> SearchQuery.Op.LT;
            case "<=" -> SearchQuery.Op.LE;
            case "=" -> SearchQuery.Op.EQ;
            default -> SearchQuery.Op.GE;
        };
    }

    /**
     * 一条语法说明。
     *
     * @param label    这一行在讲什么（大白话，不出现"语法""谓词"这类词）
     * @param examples 可以点的例子，点一下就把原文填进搜索框
     * @param tip      补充说明，一句话
     */
    public record SyntaxItem(String label, List<String> examples, String tip) {
    }

    /**
     * 全部支持的语法，<b>唯一的一份</b>。
     *
     * <p>存在的理由：这份内容同时要出现在两处——鼠标悬停的浮层（{@link #syntaxHelp()}）
     * 和搜索框下面那个可点击的语法面板。如果各写一份，迟早会出现"浮层里有的语法面板里没有"
     * 或者两处措辞不一致，而用户正是靠这两处学语法的。所以面板与浮层都由它生成。
     */
    public static List<SyntaxItem> syntaxItems() {
        return List.of(
                new SyntaxItem("文件名包含", List.of("灰度发布", "\"会议 记录\""),
                        "含空格的词用双引号包起来"),
                new SyntaxItem("文件名通配符", List.of("*.pdf", "?.log"),
                        "* 代表任意多个字符，? 代表一个字符，整体匹配文件名"),
                new SyntaxItem("扩展名", List.of(".docx", "ext:docx,xlsx"),
                        ".docx 是简写；要一次筛多种就用 ext:"),
                new SyntaxItem("类型", List.of("type:图片", "type:表格,文档"),
                        "中英文都认（图片/文档/表格/演示/压缩包/代码/可执行/其他）"),
                new SyntaxItem("大小", List.of("size:>10MB", "size:<=100KB"),
                        "支持 B / KB / MB / GB / TB"),
                new SyntaxItem("修改时间", List.of("modified:>7d", "modified:>2026-01-01"),
                        "7d=最近七天，也支持 w 周 / m 月 / y 年，或直接写日期"),
                new SyntaxItem("标签 / 收藏", List.of("tag:重要", "fav:true"),
                        "标签精确匹配；fav:true 只看已收藏"),
                new SyntaxItem("搜索正文", List.of("content:关键词"),
                        "搜 docx/xlsx/pptx/pdf/文本里的文字，第一次要先建立索引"));
    }

    /** 供界面展示的语法帮助（鼠标悬停的浮层）。由 {@link #syntaxItems()} 生成，避免两处内容不一致。 */
    public static String syntaxHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索语法（多个条件用空格分隔，含义是「同时满足」）：\n");
        for (SyntaxItem item : syntaxItems()) {
            String left = item.examples().isEmpty()
                    ? item.label()
                    : item.label() + "  " + String.join("  ", item.examples());
            sb.append("  ").append(left);
            if (!item.tip().isEmpty()) {
                sb.append("　—— ").append(item.tip());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
