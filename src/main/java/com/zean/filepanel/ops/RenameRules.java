package com.zean.filepanel.ops;

import com.zean.filepanel.core.FileItem;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 批量重命名的规则集合。
 *
 * <p>两种工作方式，二选一：
 * <ul>
 *   <li><b>规则模式</b>（{@link #template} 为空）：依次执行「查找替换 → 前缀后缀 → 序号 → 大小写」。
 *       适合"给一批文件统一加前缀"这类日常操作。</li>
 *   <li><b>模板模式</b>（{@link #template} 非空）：用变量拼出新名字，例如
 *       {@code 灰度发布-{date}-{n}}。适合"按统一格式重排"，比一串规则更容易预期。</li>
 * </ul>
 *
 * <p><b>规则只作用于主名，不碰扩展名。</b>扩展名单独由 {@link #extensionMode} 控制。
 * 这是刻意的：绝大多数批量重命名的意图是改名字而不是改类型，
 * 一旦规则意外吃掉了扩展名，用户会得到一堆打不开的文件。
 */
public class RenameRules {

    /** 主名的大小写转换。 */
    public enum CaseMode {
        KEEP("保持原样"),
        LOWER("全部小写"),
        UPPER("全部大写"),
        TITLE("每词首字母大写");

        private final String label;

        CaseMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 序号放在主名之前还是之后。 */
    public enum NumberPosition {
        BEFORE("前"),
        AFTER("后");

        private final String label;

        NumberPosition(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 扩展名的大小写处理。 */
    public enum ExtensionMode {
        KEEP("保持原样"),
        LOWER("全部小写"),
        UPPER("全部大写");

        private final String label;

        ExtensionMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    // ------------------------------------------------------------ 规则模式

    public String find = "";
    public String replace = "";
    public boolean useRegex = false;
    public boolean caseSensitive = false;

    public String prefix = "";
    public String suffix = "";

    public boolean numbering = false;
    public int numberStart = 1;
    public int numberStep = 1;
    public int numberDigits = 2;
    public NumberPosition numberPosition = NumberPosition.AFTER;

    public CaseMode caseMode = CaseMode.KEEP;
    public ExtensionMode extensionMode = ExtensionMode.KEEP;

    // ------------------------------------------------------------ 模板模式

    /** 模板，例如 {@code 灰度发布-{date}-{n}}；为空表示走规则模式。 */
    public String template = "";

    public boolean isTemplateMode() {
        return template != null && !template.isBlank();
    }

    /** 至少有一条会改变名字的规则；全空时提示用户"还没设置规则"。 */
    public boolean hasAnyRule() {
        if (isTemplateMode()) {
            return true;
        }
        return !find.isEmpty() || !prefix.isEmpty() || !suffix.isEmpty()
                || numbering || caseMode != CaseMode.KEEP || extensionMode != ExtensionMode.KEEP;
    }

    /**
     * 为第 {@code index} 个条目生成新文件名（含扩展名）。
     *
     * @param item  原始条目
     * @param index 在选中集合中的位置（从 0 开始），用于序号
     * @param now   当前时间，用于模板里的日期变量。显式传入而不是内部取，
     *              这样测试可以构造确定的时间点——否则跨零点运行就会随机失败
     */
    public String generate(FileItem item, int index, LocalDateTime now) {
        RenameService.NameParts parts = RenameService.split(item.name());
        String extension = parts.extension();
        String base;

        if (item.directory()) {
            // 目录没有扩展名概念，整名参与规则
            base = applyRules(item.name(), index, now);
            extension = "";
        } else if (isTemplateMode()) {
            base = expandTemplate(template, parts.base(), parts.extension(), index, now);
            // 模板里已经写了 {ext} 时，展开结果就是完整文件名，不能再补一次扩展名，
            // 否则 {name}.{ext} 会得到 "报告.docx.docx"——用户看到的是"多了一段"。
            if (templateReferencesExtension(template)) {
                return base;
            }
        } else {
            base = applyRules(parts.base(), index, now);
        }

        extension = transformExtension(extension);
        if (extension.isEmpty()) {
            return base;
        }
        return base + extension;
    }

    /** 模板里是否引用了 {@code {ext}}（大小写不敏感，容忍空白）。 */
    static boolean templateReferencesExtension(String template) {
        if (template == null) {
            return false;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\s*([^}]*?)\\s*}").matcher(template);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            if ("ext".equals(key) || "扩展名".equals(key)) {
                return true;
            }
        }
        return false;
    }

    private String applyRules(String base, int index, LocalDateTime now) {
        String result = base == null ? "" : base;

        if (!find.isEmpty()) {
            result = find.isEmpty() ? result : replaceAll(result, find, replace, useRegex, caseSensitive);
        }
        if (!prefix.isEmpty()) {
            result = prefix + result;
        }
        if (!suffix.isEmpty()) {
            result = result + suffix;
        }
        if (numbering) {
            String number = formatNumber(numberStart + (long) index * numberStep, numberDigits);
            result = numberPosition == NumberPosition.BEFORE ? number + "-" + result : result + "-" + number;
        }
        result = applyCase(result);
        return result;
    }

    private String applyCase(String value) {
        return switch (caseMode) {
            case KEEP -> value;
            case LOWER -> value.toLowerCase(Locale.ROOT);
            case UPPER -> value.toUpperCase(Locale.ROOT);
            case TITLE -> titleCase(value);
        };
    }

    private String transformExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "";
        }
        // extension 带前导点，大小写转换不影响点本身
        return switch (extensionMode) {
            case KEEP -> extension;
            case LOWER -> extension.toLowerCase(Locale.ROOT);
            case UPPER -> extension.toUpperCase(Locale.ROOT);
        };
    }

    /**
     * 展开模板变量。
     *
     * <p>不认识的 {@code {xxx}} <b>原样保留</b>，而不是替换成空串：
     * 保留下来用户一眼就能看出自己写错了变量名；换成空串则表现为"莫名其妙少了几个字"。
     */
    static String expandTemplate(String template, String originalBase, String extension,
                                 int index, LocalDateTime now) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('}', i + 1);
            if (close < 0) {
                out.append(template.substring(i));
                break;
            }
            String key = template.substring(i + 1, close).trim();
            String value = templateValue(key, originalBase, extension, index, now);
            if (value == null) {
                out.append('{').append(key).append('}');
            } else {
                out.append(value);
            }
            i = close + 1;
        }
        return out.toString();
    }

    /** 返回 null 表示不认识的变量，调用方保留原文。 */
    private static String templateValue(String key, String base, String extension,
                                        int index, LocalDateTime now) {
        String lower = key.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "name", "原名", "主名" -> base;
            case "ext", "扩展名" -> extension.isEmpty() ? "" : extension.substring(1);
            case "n", "序号" -> String.valueOf(index + 1);
            case "nn" -> formatNumber(index + 1, 2);
            case "nnn" -> formatNumber(index + 1, 3);
            case "date", "日期" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            case "time", "时间" -> now.format(DateTimeFormatter.ofPattern("HHmmss"));
            case "yyyy" -> String.valueOf(now.getYear());
            case "mm" -> formatNumber(now.getMonthValue(), 2);
            case "dd" -> formatNumber(now.getDayOfMonth(), 2);
            case "hh" -> formatNumber(now.getHour(), 2);
            case "mi" -> formatNumber(now.getMinute(), 2);
            case "ss" -> formatNumber(now.getSecond(), 2);
            default -> null;
        };
    }

    /**
     * 查找替换。
     *
     * <p>正则模式用 {@code replaceAll}（支持 {@code $1} 捕获组）；
     * 普通模式用 {@code replace} 的字面量语义——若普通模式也走 replaceAll，
     * 文件名里的 {@code $} 与 {@code \} 会被当成特殊字符，表现为"替换结果莫名其妙"。
     */
    static String replaceAll(String input, String find, String replace,
                             boolean useRegex, boolean caseSensitive) {
        String safeReplace = replace == null ? "" : replace;
        if (!useRegex) {
            if (caseSensitive) {
                return input.replace(find, safeReplace);
            }
            // 忽略大小写的字面量替换：手工扫描而不是正则，避免把 find 里的元字符当语法
            return replaceIgnoringCase(input, find, safeReplace);
        }
        int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE
                | java.util.regex.Pattern.UNICODE_CASE;
        try {
            return java.util.regex.Pattern.compile(find, flags).matcher(input)
                    .replaceAll(safeReplace);
        } catch (RuntimeException e) {
            // 正则写了一半（用户正在输入）时不能让预览崩掉，原样返回
            return input;
        }
    }

    /**
     * 忽略大小写的字面量替换。
     *
     * <p>不用 {@code Pattern.quote} + 正则，是因为替换串里的 {@code $} 与 {@code \}
     * 在 {@code replaceAll} 里仍有特殊含义，会得到与"字面量替换"不符的结果。
     * 这里是纯字符串扫描，行为完全可预期。
     */
    static String replaceIgnoringCase(String input, String find, String replace) {
        if (find.isEmpty()) {
            return input;
        }
        String lowerInput = input.toLowerCase(Locale.ROOT);
        String lowerFind = find.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(input.length());
        int from = 0;
        while (true) {
            int at = lowerInput.indexOf(lowerFind, from);
            if (at < 0) {
                out.append(input, from, input.length());
                break;
            }
            out.append(input, from, at).append(replace);
            from = at + find.length();
        }
        return out.toString();
    }

    static String formatNumber(long value, int digits) {
        int width = Math.max(1, Math.min(10, digits));
        return String.format(Locale.ROOT, "%0" + width + "d", value);
    }

    private static String titleCase(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean startOfWord = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '-' || c == '_' || c == '.') {
                startOfWord = true;
                out.append(c);
            } else if (startOfWord) {
                out.append(Character.toUpperCase(c));
                startOfWord = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** 供界面展示的规则摘要。 */
    public String describe() {
        if (!hasAnyRule()) {
            return "（尚未设置任何规则）";
        }
        StringBuilder sb = new StringBuilder();
        if (isTemplateMode()) {
            sb.append("模板「").append(template).append('」');
        } else {
            if (!find.isEmpty()) {
                sb.append(useRegex ? "正则" : "查找").append("「").append(find)
                        .append("」→「").append(replace).append('」');
            }
            if (!prefix.isEmpty()) {
                appendSeparator(sb).append("前缀「").append(prefix).append('」');
            }
            if (!suffix.isEmpty()) {
                appendSeparator(sb).append("后缀「").append(suffix).append('」');
            }
            if (numbering) {
                appendSeparator(sb).append("序号从 ").append(numberStart)
                        .append(" 起、步长 ").append(numberStep)
                        .append("、").append(numberDigits).append(" 位、置于")
                        .append(numberPosition.label());
            }
            if (caseMode != CaseMode.KEEP) {
                appendSeparator(sb).append("主名").append(caseMode.label());
            }
        }
        if (extensionMode != ExtensionMode.KEEP) {
            appendSeparator(sb).append("扩展名").append(extensionMode.label());
        }
        return sb.toString();
    }

    private static StringBuilder appendSeparator(StringBuilder sb) {
        return sb.length() == 0 ? sb : sb.append(" · ");
    }
}
