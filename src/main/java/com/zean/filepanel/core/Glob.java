package com.zean.filepanel.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 通配符匹配（支持 {@code *} 与 {@code ?}）。
 *
 * <p>抽成公共工具的原因：排除规则（{@link Exclusions}）与搜索语法（{@link SearchParser}）
 * 都要用同一套通配符语义。两处各写一份，迟早出现“排除规则认 {@code *.tmp}、搜索不认”这种不一致。
 *
 * <p>不使用 {@code FileSystems.getPathMatcher("glob:...")}：它的大小写敏感性随平台变化且不受控，
 * 而这里的需求是明确的「忽略大小写」。自己转正则既可控也可测。
 */
public final class Glob {

    private static final int CACHE_LIMIT = 512;

    /**
     * 已编译模式的缓存。
     *
     * <p>搜索框每敲一个字符都会重新解析整条查询，若每次都 {@code Pattern.compile}
     * 会在快速输入时产生大量短命对象。用有界缓存挡住。
     */
    private static final java.util.Map<String, Pattern> CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Pattern> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });

    private Glob() {
    }

    /** 通配符转正则；特殊字符按字面量处理（例如 {@code ~$*} 里的 {@code $} 不能当行尾）。 */
    public static Pattern toPattern(String glob) {
        if (glob == null) {
            throw new IllegalArgumentException("glob 不能为 null");
        }
        return CACHE.computeIfAbsent(glob, g -> {
            StringBuilder sb = new StringBuilder(g.length() + 8);
            sb.append('^');
            for (int i = 0; i < g.length(); i++) {
                char c = g.charAt(i);
                switch (c) {
                    case '*' -> sb.append(".*");
                    case '?' -> sb.append('.');
                    default -> sb.append(Pattern.quote(String.valueOf(c)));
                }
            }
            sb.append('$');
            return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        });
    }

    /** 名称是否匹配通配符模式（忽略大小写，要求整体匹配）。 */
    public static boolean matches(String glob, String name) {
        return name != null && toPattern(glob).matcher(name).matches();
    }

    /** 名称是否匹配其中任意一个模式。 */
    public static boolean matchesAny(List<String> globs, String name) {
        if (globs == null || name == null) {
            return false;
        }
        for (String g : globs) {
            if (toPattern(g).matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 是否需要按通配符处理（用于判断一个搜索词是“模式”还是“普通文本”）。 */
    public static boolean looksLikeGlob(String token) {
        return token != null && (token.indexOf('*') >= 0 || token.indexOf('?') >= 0);
    }

    /** 小写化，统一用 {@link Locale#ROOT} 避免土耳其语等地区的 i/İ 问题。 */
    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
