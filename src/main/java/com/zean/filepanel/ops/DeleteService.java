package com.zean.filepanel.ops;

import com.zean.filepanel.core.DeletePlan;
import com.zean.filepanel.core.DeletePolicy;
import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.store.DeleteJournal;
import com.zean.filepanel.win.Shell32;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 删除执行器。
 *
 * <p>分工：<b>策略在 {@link DeletePolicy}（纯逻辑、可单测）</b>，
 * <b>本类只负责把决定落实到磁盘并如实汇报结果</b>。
 *
 * <h2>三条不允许妥协的规则</h2>
 * <ol>
 *   <li><b>绝不静默永久删除</b>：只有调用方明确选了 {@link Mode#ALL_PERMANENT}，
 *       或策略判定该条目为永久删除时才会永久删除。回收站调用失败时只<b>报告失败</b>，
 *       由界面去询问用户是否改为永久删除，而不是自作主张。</li>
 *   <li><b>失败必须逐项归因</b>：批量调用失败时会退化为逐项重试，
 *       这样用户能看到"哪一个文件删不掉、为什么"，而不是笼统一句"删除失败"。</li>
 *   <li><b>删除要留痕</b>：写入 {@link DeleteJournal}，永久删除尤其如此。</li>
 * </ol>
 */
public final class DeleteService {

    /** 用户在确认框里的选择。 */
    public enum Mode {
        /** 按 {@link DeletePolicy} 逐项决定（默认）。 */
        POLICY,
        /** 全部放入回收站。 */
        ALL_RECYCLE,
        /** 全部永久删除（界面必须已做过红色二次确认）。 */
        ALL_PERMANENT
    }

    /** 单个条目的失败原因。 */
    public record Failure(FileItem item, String message) {
        public String describe() {
            return item.path() + " — " + message;
        }
    }

    /**
     * 回收站调用接缝。
     *
     * <p>抽成接口有两个理由：<b>可测</b>（测试可以注入一个不会真的往用户回收站里塞东西的假实现），
     * 以及把"调用 Shell"与"判断是否真的进了回收站"这两件事分开。
     */
    @FunctionalInterface
    public interface RecycleAction {
        Shell32.Result recycle(List<Path> paths);
    }

    /**
     * 回收站条目数探针。
     *
     * <p>用来回答一个 Shell 自己不会回答的问题：<b>文件到底是进了回收站，还是被永久删除了</b>。
     * 实测发现 {@code SHFileOperationW} 在某些受限环境下会返回 0（成功）却把文件永久删除——
     * 此时只看返回码就会告诉用户"已放入回收站、可以还原"，而这是错的。
     */
    @FunctionalInterface
    public interface RecycleBinProbe {
        /** 当前回收站条目数；无法查询时返回负数。 */
        long itemCount();
    }

    /** 一次删除操作的结果。 */
    public record Outcome(List<FileItem> recycled, List<FileItem> deleted,
                          List<Failure> failures, List<FileItem> recycleUnverified) {

        public int succeeded() {
            return recycled.size() + deleted.size();
        }

        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        /** 是否存在被永久删除的条目——界面据此提示"不可恢复"。 */
        public boolean hasPermanent() {
            return !deleted.isEmpty();
        }

        /**
         * 是否存在"报告成功送入回收站、但无法确认真的进了回收站"的条目。
         *
         * <p>界面必须把这种情况单独说出来。宁可让用户虚惊一场去回收站确认，
         * 也不能让他以为文件还能找回、结果早已永久消失。
         */
        public boolean hasUnverifiedRecycle() {
            return !recycleUnverified.isEmpty();
        }

        public static Outcome empty() {
            return new Outcome(List.of(), List.of(), List.of(), List.of());
        }
    }

    private DeletePolicy policy;
    private final DeleteJournal journal;
    private final RecycleAction recycleAction;
    private final RecycleBinProbe recycleBinProbe;

    public DeleteService(DeletePolicy policy, DeleteJournal journal) {
        this(policy, journal, Shell32::moveToRecycleBin, () -> Shell32.recycleBinItemCount(null));
    }

    public DeleteService(DeletePolicy policy, DeleteJournal journal,
                         RecycleAction recycleAction, RecycleBinProbe recycleBinProbe) {
        this.policy = policy == null ? new DeletePolicy() : policy;
        this.journal = journal;
        this.recycleAction = recycleAction == null ? Shell32::moveToRecycleBin : recycleAction;
        this.recycleBinProbe = recycleBinProbe == null
                ? () -> Shell32.recycleBinItemCount(null) : recycleBinProbe;
    }

    public DeletePolicy policy() {
        return policy;
    }

    /**
     * 更新删除策略（用户在设置里改了阈值或"一律走回收站"之后即刻生效）。
     *
     * <p>刻意<b>不</b>重建 {@code DeleteService}：这个对象持有可注入的
     * {@link RecycleAction} 与 {@link RecycleBinProbe} 接缝，重建会把注入的假实现丢掉，
     * 于是"改了设置之后回收站校验就失效了"这种 bug 会悄悄出现。
     */
    public void setPolicy(DeletePolicy next) {
        this.policy = next == null ? new DeletePolicy() : next;
    }

    /**
     * 执行删除。
     *
     * @param items 待删除条目
     * @param mode  用户选择的模式
     */
    public Outcome execute(List<FileItem> items, Mode mode) {
        if (items == null || items.isEmpty()) {
            return Outcome.empty();
        }
        Mode effective = mode == null ? Mode.POLICY : mode;

        List<FileItem> toRecycle = new ArrayList<>();
        List<FileItem> toDelete = new ArrayList<>();
        for (FileItem item : items) {
            if (item == null) {
                continue;
            }
            DeletePolicy.Action action = switch (effective) {
                case ALL_RECYCLE -> DeletePolicy.Action.RECYCLE;
                case ALL_PERMANENT -> DeletePolicy.Action.PERMANENT;
                case POLICY -> policy.decide(item);
            };
            if (action == DeletePolicy.Action.RECYCLE) {
                toRecycle.add(item);
            } else {
                toDelete.add(item);
            }
        }

        List<FileItem> recycled = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<FileItem> unverified = new ArrayList<>();
        recycle(toRecycle, recycled, failures, unverified);

        List<FileItem> deleted = new ArrayList<>();
        for (FileItem item : toDelete) {
            // 显式判断存在性：Files.deleteIfExists 对不存在的路径会返回 false 而不报错，
            // 若照此计入"成功"，界面就会在什么都没删的情况下告诉用户"已永久删除 1 项"。
            if (!Files.exists(item.path(), LinkOption.NOFOLLOW_LINKS)) {
                failures.add(new Failure(item, "文件或文件夹不存在（可能已被移动或删除）"));
                continue;
            }
            try {
                deleteRecursively(item.path());
                deleted.add(item);
            } catch (IOException | RuntimeException e) {
                failures.add(new Failure(item, describe(e)));
            }
        }

        writeJournal(recycled, deleted);
        return new Outcome(List.copyOf(recycled), List.copyOf(deleted),
                List.copyOf(failures), List.copyOf(unverified));
    }

    /**
     * 送入回收站，并<b>验证结果</b>。
     *
     * <p>先整批调用（Shell 一次处理更高效），失败再逐项重试：
     * Shell 的返回值是整个批次的，无法指出是哪一个文件出的问题，
     * 所以必须逐项重试才能给出"哪个文件、什么原因"。
     *
     * <p>最后用回收站条目数的前后差值做验证。这一步不是多余的谨慎：
     * 实测中 {@code SHFileOperationW} 返回 0、文件确实消失了，但回收站条目数没有增加，
     * 也就是说文件其实被永久删除了。没有这一步，界面就会骗用户说文件还能从回收站找回。
     */
    private void recycle(List<FileItem> items, List<FileItem> recycled,
                         List<Failure> failures, List<FileItem> unverified) {
        if (items.isEmpty()) {
            return;
        }
        long countBefore = recycleBinProbe.itemCount();

        List<Path> paths = items.stream().map(FileItem::path).toList();
        Shell32.Result batch = recycleAction.recycle(paths);
        if (batch.success()) {
            recycled.addAll(items);
        } else if (!Shell32.available() && !batch.message().isEmpty()) {
            items.forEach(i -> failures.add(new Failure(i, batch.message())));
        } else {
            // 逐项重试，拿到精确的失败原因
            for (FileItem item : items) {
                Shell32.Result one = recycleAction.recycle(List.of(item.path()));
                if (one.success()) {
                    recycled.add(item);
                } else {
                    failures.add(new Failure(item, one.message()));
                }
            }
        }

        verifyRecycle(recycled, unverified, countBefore);
    }

    /**
     * 用回收站条目数验证"确实进了回收站"。
     *
     * <p>判定刻意<b>宽松</b>：只在条目数<b>完全没有增加</b>时才判为可疑。
     * 严格比较"增加量 == 条目数"会把"同时有别的程序清空回收站"这类并发情况误报成删除失败，
     * 而误报会让用户对提示失去信任。反过来漏报的代价是用户以为文件还在回收站——
     * 这个方向由 {@code FOF_WANTNUKEWARNING} 兜底。
     */
    private void verifyRecycle(List<FileItem> recycled, List<FileItem> unverified, long countBefore) {
        if (recycled.isEmpty() || countBefore < 0) {
            return;
        }
        long countAfter = recycleBinProbe.itemCount();
        if (countAfter < 0) {
            return;
        }
        if (countAfter - countBefore <= 0) {
            unverified.addAll(recycled);
        }
    }

    /** 写入删除日志。 */
    private void writeJournal(List<FileItem> recycled, List<FileItem> deleted) {
        if (journal == null) {
            return;
        }
        List<DeleteJournal.Entry> entries = new ArrayList<>(recycled.size() + deleted.size());
        for (FileItem item : recycled) {
            entries.add(DeleteJournal.Entry.of(item.path(), item.size(), item.directory(), "RECYCLE"));
        }
        for (FileItem item : deleted) {
            entries.add(DeleteJournal.Entry.of(item.path(), item.size(), item.directory(), "PERMANENT"));
        }
        journal.append(entries);
    }

    /** 递归删除（永久）。不跟随符号链接，避免删到链接指向的外部内容。 */
    static void deleteRecursively(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : "：" + message);
    }

    /** 供界面提示用：把方案翻译成一句人话。 */
    public static String describePlan(DeletePlan plan, DeletePolicy policy) {
        if (plan.isEmpty()) {
            return "没有可删除的条目";
        }
        if (plan.isAllRecyclable()) {
            return plan.total() + " 项将放入回收站，可从回收站还原";
        }
        if (plan.isAllPermanent()) {
            return plan.total() + " 项超过 " + policy.describeThreshold()
                    + "，将被【永久删除】且无法从回收站恢复";
        }
        return "共 " + plan.total() + " 项：其中 " + plan.recyclable().size()
                + " 项放入回收站，" + plan.permanent().size()
                + " 项超过 " + policy.describeThreshold() + " 将被【永久删除】";
    }
}
