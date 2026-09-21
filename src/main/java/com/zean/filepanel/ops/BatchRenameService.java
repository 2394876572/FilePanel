package com.zean.filepanel.ops;

import com.zean.filepanel.store.RenameJournal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 批量重命名的执行器与撤销。
 *
 * <h2>为什么必须两阶段</h2>
 * 用户想把 {@code A.txt} 与 {@code B.txt} 互换名字。
 * 若逐个直接改：先把 A 改成 B —— 目标已存在，失败或覆盖；无论哪种都是坏结果。
 * 所以先把<b>所有</b>源文件改成一个唯一的临时名（此时原名全部腾空），
 * 再把临时名逐个改成最终名。这样 {@code A→B}、{@code B→A} 这类环状依赖天然成立。
 *
 * <h2>失败怎么办</h2>
 * 任何一步失败都<b>尽力回滚</b>：临时名也好、已经就位的目标名也好，都往原始名字搬回去。
 * 回滚本身也可能失败（磁盘满、文件被占用），此时<b>如实报出文件当前叫什么名字</b>，
 * 绝不假装"已还原"。用户至少知道去哪儿找剩下的文件。
 *
 * <h2>撤销同样要两阶段</h2>
 * 撤销是把 {@code (原→新)} 反过来做一遍。互换场景下它本身又是一个环状依赖，
 * 所以撤销复用同一套两阶段逻辑，而不是简单地逐个改回。
 */
public final class BatchRenameService {

    /** 一次移动。 */
    public record Move(Path from, Path to) {
    }

    /** 单次移动的结果。 */
    public record MoveResult(Path from, Path to, boolean ok, String message) {
    }

    /** 一次批量操作的结果。 */
    public record Outcome(boolean success, List<MoveResult> results, List<String> problems) {

        public boolean hasProblems() {
            return !problems.isEmpty();
        }

        public int succeededCount() {
            return (int) results.stream().filter(MoveResult::ok).count();
        }

        public static Outcome empty() {
            return new Outcome(true, List.of(), List.of());
        }
    }

    private static final int MAX_NAME_LENGTH = 255;

    private final RenameJournal journal;

    public BatchRenameService(RenameJournal journal) {
        this.journal = journal;
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 执行一次批量重命名，成功后写入撤销日志。
     *
     * @param entries     方案中可执行的行
     * @param description 写进日志的描述，例如"把 12 个文件加前缀 2026-"
     */
    public Outcome rename(List<BatchRenamePlan.Entry> entries, String description) {
        if (entries == null || entries.isEmpty()) {
            return Outcome.empty();
        }
        List<Move> moves = new ArrayList<>(entries.size());
        for (BatchRenamePlan.Entry entry : entries) {
            moves.add(new Move(entry.item().path(), entry.target()));
        }

        Outcome outcome = executeTwoPhase(moves);
        if (outcome.success() && journal != null) {
            List<RenameJournal.Pair> pairs = new ArrayList<>(moves.size());
            for (Move move : moves) {
                pairs.add(RenameJournal.Pair.of(move.from(), move.to()));
            }
            journal.record(description == null ? ("重命名 " + moves.size() + " 项") : description, pairs);
        }
        return outcome;
    }

    /**
     * 撤销最近一次批量重命名。
     *
     * @return 撤销结果；没有可撤销的记录时返回空
     */
    public Optional<Outcome> undoLast() {
        if (journal == null) {
            return Optional.empty();
        }
        Optional<RenameJournal.Batch> last = journal.last();
        if (last.isEmpty()) {
            return Optional.empty();
        }
        RenameJournal.Batch batch = last.get();

        // 反向：新名 -> 原名
        List<Move> moves = new ArrayList<>(batch.size());
        for (RenameJournal.Pair pair : batch.pairs) {
            moves.add(new Move(pair.toPath(), pair.fromPath()));
        }

        Outcome outcome = executeTwoPhase(moves);
        if (outcome.success()) {
            journal.removeBatch(batch.id);
        }
        return Optional.of(outcome);
    }

    /** 供界面展示：最近一次可撤销的操作描述。 */
    public String describeUndoable() {
        return journal == null ? "没有可撤销的重命名" : journal.describeLast();
    }

    public boolean hasUndoable() {
        return journal != null && journal.last().isPresent();
    }

    // -------------------------------------------------------------- 两阶段实现

    /** 一条已进入暂存状态的重命名。 */
    private record Staged(Path original, Path temp, Path target) {
    }

    /**
     * 两阶段执行一组改名。
     *
     * <p>包内可见，供测试直接调用——测试需要构造"故意让第二阶段失败"的场景，
     * 而通过公开 API 很难制造。
     */
    static Outcome executeTwoPhase(List<Move> moves) {
        if (moves == null || moves.isEmpty()) {
            return Outcome.empty();
        }

        List<MoveResult> results = new ArrayList<>(moves.size());
        List<String> problems = new ArrayList<>();
        String token = ".filepanel-tmp-" + Long.toHexString(System.nanoTime()) + "-";

        // ---- 阶段一：全部改成唯一临时名，先把所有原名腾空 ----
        List<Staged> staged = new ArrayList<>(moves.size());
        for (int i = 0; i < moves.size(); i++) {
            Move move = moves.get(i);
            Path temp = move.from().resolveSibling(tempName(move.from(), token, i));
            try {
                Files.move(move.from(), temp);
                staged.add(new Staged(move.from(), temp, move.to()));
            } catch (IOException | RuntimeException e) {
                rollbackFromTemp(staged, problems);
                problems.add(0, describeMove(move)
                        + " 在准备阶段失败，已取消整批操作：" + message(e));
                return new Outcome(false, results, problems);
            }
        }

        // ---- 阶段二：临时名 -> 最终名 ----
        List<Staged> completed = new ArrayList<>(staged.size());
        for (Staged item : staged) {
            try {
                moveNoOverwrite(item.temp(), item.target());
                completed.add(item);
                results.add(new MoveResult(item.original(), item.target(), true, ""));
            } catch (IOException | RuntimeException e) {
                problems.add(describeMove(new Move(item.original(), item.target()))
                        + " 在改名阶段失败：" + message(e));
                // 尽力还原：已就位的与还停在临时名的都搬回原名
                rollbackFromTarget(completed, problems);
                rollbackFromTemp(remaining(staged, completed), problems);
                return new Outcome(false, results, problems);
            }
        }
        return new Outcome(true, results, problems);
    }

    private static List<Staged> remaining(List<Staged> all, List<Staged> completed) {
        List<Staged> rest = new ArrayList<>(all);
        rest.removeAll(completed);
        return rest;
    }

    /**
     * 移动文件，<b>绝不覆盖已存在的目标</b>。
     *
     * <h2>为什么不用 ATOMIC_MOVE（重要）</h2>
     * 直觉上 {@code StandardCopyOption.ATOMIC_MOVE} 更"安全"，实际相反：
     * 它的规范明确写着"其他选项被忽略，若目标已存在，是否替换由实现决定"。
     * 在 Windows 上实测的结果是<b>直接替换</b>——
     * 也就是说，如果计划生成之后有别的程序在目标位置创建了文件，这个 move 会把它悄悄删掉。
     * 这是重命名操作最不能犯的错：用户只想改个名字，不该有任何文件消失。
     *
     * <p>因此这里用不带选项的 {@code Files.move}：它在目标已存在时抛
     * {@code FileAlreadyExistsException}，配合上面显式的存在性检查，
     * 给出的是"失败"而不是"毁掉一个文件"。
     */
    static void moveNoOverwrite(Path from, Path to) throws IOException {
        if (Files.exists(to)) {
            throw new java.nio.file.FileAlreadyExistsException(to.toString());
        }
        Files.move(from, to);
    }

    /**
     * 从"已就位"状态回滚：把最终名搬回原名。
     *
     * <p>只处理<b>阶段二确认成功</b>的条目——也就是说，我们确切知道自己的文件就在 {@code target} 上。
     *
     * <p><b>绝不能靠 "Files.exists(target)" 来猜文件在哪</b>。早期版本就是那么写的，
     * 结果在"目标被别的程序占用导致阶段二失败"的场景下，
     * 回滚把<b>别人的文件</b>从 target 搬到了 original，而自己的文件反而留在临时名上——
     * 一次回滚把两个文件都搞乱了。这类错误不会抛异常，只会让文件内容悄悄错位。
     */
    private static void rollbackFromTarget(List<Staged> items, List<String> problems) {
        for (Staged item : items) {
            if (!Files.exists(item.target())) {
                continue;
            }
            try {
                moveNoOverwrite(item.target(), item.original());
            } catch (IOException | RuntimeException e) {
                problems.add("回滚失败：「" + item.original().getFileName()
                        + "」当前名为「" + item.target().getFileName() + "」（" + message(e) + "）");
            }
        }
    }

    /**
     * 从"暂存"状态回滚：把临时名搬回原名。
     *
     * <p>只处理<b>仍停在临时名上</b>的条目（阶段二尚未处理、或阶段二失败的）。
     */
    private static void rollbackFromTemp(List<Staged> items, List<String> problems) {
        for (Staged item : items) {
            if (!Files.exists(item.temp())) {
                continue;
            }
            try {
                moveNoOverwrite(item.temp(), item.original());
            } catch (IOException | RuntimeException e) {
                problems.add("回滚失败：「" + item.original().getFileName()
                        + "」当前名为「" + item.temp().getFileName() + "」（" + message(e) + "）");
            }
        }
    }

    /**
     * 构造临时名。
     *
     * <p>必须截断：原文件名最长可达 255 字符，直接拼接后缀会超过文件系统上限而失败——
     * 那就成了一个"名字长的文件永远无法批量重命名"的隐藏限制。
     */
    static String tempName(Path path, String token, int index) {
        String name = path.getFileName() == null ? "x" : path.getFileName().toString();
        String suffix = token + index;
        int keep = Math.max(1, MAX_NAME_LENGTH - suffix.length());
        if (name.length() > keep) {
            name = name.substring(0, keep);
        }
        return name + suffix;
    }

    private static String describeMove(Move move) {
        return "「" + move.from().getFileName() + "」→「" + move.to().getFileName() + "」";
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : "：" + m);
    }
}
