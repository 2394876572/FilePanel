package com.zean.filepanel.ui;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.core.FileKind;

import java.time.LocalDate;
import java.util.function.Predicate;

/**
 * 侧栏里的一个分类项。
 *
 * <p>分为三类：全部、最近七天、以及按 {@link FileKind} 的类型分类。
 * 收藏与标签分类留到 M4（需要先有持久化存储）。
 *
 * <p>谓词是<b>即时求值</b>的：{@code recent(7)} 内部每次判定都重新取当天日期，
 * 而不是在构造时固定一个截止时间。否则程序跨夜运行后“最近七天”就会算错一天。
 */
public final class SidebarCategory {

    public static final String ALL = "all";
    public static final String RECENT = "recent";
    public static final String FAVORITES = "favorites";

    /** “最近七天”的天数。计数与筛选都必须引用它，否则会出现“侧栏说 12 项、点进去 11 行”。 */
    public static final int RECENT_DAYS = 7;

    private final String id;
    private final String label;
    private final Predicate<FileItem> predicate;

    private SidebarCategory(String id, String label, Predicate<FileItem> predicate) {
        this.id = id;
        this.label = label;
        this.predicate = predicate;
    }

    public static SidebarCategory all() {
        return new SidebarCategory(ALL, "全部", item -> true);
    }

    /** 最近 N 天内修改过的条目。 */
    public static SidebarCategory modifiedWithin(int days) {
        return new SidebarCategory(RECENT, "最近" + days + "天", item -> isWithinDays(item, days));
    }

    public static SidebarCategory ofKind(FileKind kind) {
        return new SidebarCategory(kindId(kind), kind.displayName(), item -> item.kind() == kind);
    }

    public static String kindId(FileKind kind) {
        return "kind:" + kind.key();
    }

    /**
     * 收藏分类。
     *
     * <p>谓词由调用方注入，而不是让这里直接依赖 store：分类本身只关心"这个条目算不算数"，
     * 收藏数据存在哪里、怎么归一化路径都是 store 的事。
     */
    public static SidebarCategory favorites(Predicate<FileItem> isFavorite) {
        return new SidebarCategory(FAVORITES, "收藏", isFavorite);
    }

    public static String tagId(String tag) {
        return "tag:" + tag;
    }

    /** 按标签分类。 */
    public static SidebarCategory ofTag(String tag, Predicate<FileItem> hasTag) {
        return new SidebarCategory(tagId(tag), "# " + tag, hasTag);
    }

    /**
     * “最近 N 天”的判定。
     *
     * <p>抽成静态方法是为了让<b>计数与筛选共用同一段判断</b>：
     * 若侧栏计数另写一遍“最近七天”，两边对边界（今天、跨夜）的处理稍有差别，
     * 就会出现“侧栏显示 12 项、点进去却只有 11 行”这种没法解释的现象。
     *
     * <p>每次调用都取当天日期，不做缓存，因此程序跨夜运行后结果依然正确。
     */
    public static boolean isWithinDays(FileItem item, int days) {
        LocalDate modified = SearchSupport.toLocalDate(item);
        return modified != null && !modified.isBefore(LocalDate.now().minusDays(days));
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public boolean test(FileItem item) {
        return predicate.test(item);
    }

    @Override
    public String toString() {
        return label;
    }
}
