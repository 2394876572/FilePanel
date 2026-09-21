package com.zean.filepanel.ops;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.store.PathKey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量重命名的预览方案。
 *
 * <p>这是"先看清楚再动手"的载体：把每一行的原名、新名、以及<b>为什么不能改</b>都算出来，
 * 让用户在按下执行之前就能看到全部后果。
 *
 * <h2>两个容易判错的地方</h2>
 * <ol>
 *   <li><b>"目标已存在"不一定是冲突</b>：如果那个已存在的文件本身也在这批里、并且会被改掉名字，
 *       两阶段执行会先把它挪走，最终并不冲突。这正是 {@code A.txt ⇄ B.txt} 互换名字能成立的原因。
 *       判定必须把"批次内的源文件"排除在外，否则互换永远被误报为冲突。</li>
 *   <li><b>"无变化"必须按原始大小写比较，不能用路径归一化的结果</b>：
 *       Windows 上 {@code PathKey} 忽略大小写，若拿它判断"有没有变化"，
 *       那么"把扩展名统一改成大写"这类意图会被静默判成"无变化"——用户点了执行却什么都没发生。
 *       归一化只用于"是不是同一个文件"，不用于"名字有没有变"。</li>
 * </ol>
 */
public final class BatchRenamePlan {

    /** 单行的状态。 */
    public enum Status {
        /** 可以重命名（包含仅大小写变化的情况）。 */
        OK("将重命名"),
        /** 新名与原名逐字符相同，无需改动。 */
        UNCHANGED("无变化"),
        /** 新名不合法（非法字符、保留名、过长等）。 */
        ILLEGAL("名称不合法"),
        /** 与现有文件冲突，或多项撞到同一个名字。 */
        CONFLICT("冲突");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isProblem() {
            return this == ILLEGAL || this == CONFLICT;
        }
    }

    /** 预览中的一行。 */
    public record Entry(FileItem item, String newName, Path target, Status status, String message) {

        /** 是否仅大小写不同——执行时需要两步走，界面也应提示用户。 */
        public boolean caseOnlyChange() {
            return !item.name().equals(newName) && item.name().equalsIgnoreCase(newName);
        }
    }

    private final List<Entry> entries;

    private BatchRenamePlan(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /** 可以真正执行的行数。 */
    public int actionableCount() {
        return (int) entries.stream().filter(e -> e.status() == Status.OK).count();
    }

    public int problemCount() {
        return (int) entries.stream().filter(e -> e.status().isProblem()).count();
    }

    public boolean hasProblems() {
        return problemCount() > 0;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 只取可执行的行，用于"跳过有问题的项后继续执行"。 */
    public List<Entry> actionable() {
        return entries.stream().filter(e -> e.status() == Status.OK).toList();
    }

    /** 供界面展示的一句话摘要。 */
    public String summary() {
        if (entries.isEmpty()) {
            return "没有可重命名的项";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("将重命名 ").append(actionableCount()).append(" 项");
        long unchanged = entries.stream().filter(e -> e.status() == Status.UNCHANGED).count();
        if (unchanged > 0) {
            sb.append("，").append(unchanged).append(" 项无变化");
        }
        if (hasProblems()) {
            sb.append("，").append(problemCount()).append(" 项有问题");
        }
        return sb.toString();
    }

    /**
     * 构建方案。
     *
     * @param items 选中的条目（顺序即序号顺序）
     * @param rules 规则
     * @param now   当前时间，供模板里的日期变量使用。显式传入以便测试构造确定的时间点
     */
    public static BatchRenamePlan build(List<FileItem> items, RenameRules rules, LocalDateTime now) {
        if (items == null || items.isEmpty() || rules == null) {
            return new BatchRenamePlan(List.of());
        }

        // 批次内所有源路径（归一化）。两阶段执行会先把它们全部挪开，
        // 因此"目标与某个源重名"不构成冲突。
        Set<String> sourceKeys = new HashSet<>();
        for (FileItem item : items) {
            sourceKeys.add(PathKey.of(item.path()));
        }

        List<Entry> entries = new ArrayList<>(items.size());
        Map<String, Integer> targetCounts = new HashMap<>();

        for (int i = 0; i < items.size(); i++) {
            FileItem item = items.get(i);
            String newName = rules.generate(item, i, now);

            Path target;
            try {
                target = item.path().resolveSibling(newName);
            } catch (RuntimeException e) {
                entries.add(new Entry(item, newName, item.path(), Status.ILLEGAL, "名称含非法字符"));
                continue;
            }

            RenameService.Validation nameCheck = RenameService.validateName(newName);
            if (!nameCheck.ok()) {
                entries.add(new Entry(item, newName, target, Status.ILLEGAL, nameCheck.message()));
                continue;
            }

            // 注意这里按原始大小写比较：仅大小写变化是一次真实的改动，不能被当成"无变化"
            if (item.name().equals(newName)) {
                entries.add(new Entry(item, newName, target, Status.UNCHANGED, ""));
                continue;
            }

            String sourceKey = PathKey.of(item.path());
            String targetKey = PathKey.of(target);
            targetCounts.merge(targetKey, 1, Integer::sum);

            // 目标就是源文件自己（仅大小写变化），或者目标本身也在这批里会被挪走 —— 都不算冲突
            boolean targetIsSelf = sourceKey.equals(targetKey);
            boolean targetIsAlsoSource = sourceKeys.contains(targetKey);
            if (!targetIsSelf && !targetIsAlsoSource && Files.exists(target)) {
                entries.add(new Entry(item, newName, target, Status.CONFLICT, "已存在同名文件或文件夹"));
                continue;
            }

            entries.add(new Entry(item, newName, target, Status.OK, ""));
        }

        // 第二遍：多项撞到同一个名字
        List<Entry> resolved = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.status() == Status.OK && targetCounts.getOrDefault(PathKey.of(e.target()), 0) > 1) {
                resolved.add(new Entry(e.item(), e.newName(), e.target(), Status.CONFLICT,
                        "多项将重命名为同一个名字"));
            } else {
                resolved.add(e);
            }
        }
        return new BatchRenamePlan(resolved);
    }
}
