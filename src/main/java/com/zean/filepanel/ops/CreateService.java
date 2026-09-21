package com.zean.filepanel.ops;

import com.zean.filepanel.store.CreateJournal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量创建的执行层。
 *
 * <h2>三条不可动摇的规则</h2>
 * <ol>
 *   <li><b>绝不覆盖</b>：只新建。哪怕计划里出了什么岔子，也宁可失败一项、报告出来，
 *       绝不去动已经存在的东西。这是与删除/重命名同一条底线。</li>
 *   <li><b>单项失败不中断</b>：第 137 项失败不该让剩下 863 项全都不建。
 *       失败逐条记录下来，最后一起报告。</li>
 *   <li><b>只把成功的记进撤销日志</b>：撤销时去删一个当初根本没建成功的东西，
 *       会把错误信息变成"撤销失败"，让人以为撤销功能坏了。</li>
 * </ol>
 *
 * <h2>为什么它必须能在后台线程跑</h2>
 * 实测每条约 0.5 ms（含创建后重新扫描），5 万条就是几十秒。
 * 放在 JavaFX 线程上就是几十秒的界面假死——这正是删除那边已经暴露过的同类问题，
 * 不能在一个新功能里再犯一次。所以这里<b>不碰任何 UI</b>：它是纯粹的同步执行，
 * 由调用方决定放在哪个线程，并在合适的时候回调进度。
 */
public final class CreateService {

    /** 进度回调；在调用方的线程上被调用。 */
    public interface Progress {
        /**
         * @param done  已完成数量
         * @param total 计划总数
         * @param name  刚处理完的名字
         */
        void onProgress(int done, int total, String name);
    }

    /** 单项失败。 */
    public record Failure(Path path, String message) {
    }

    /**
     * 执行结果。
     *
     * @param created  新建成功的路径（按计划顺序）
     * @param failures 失败的项
     * @param skipped  计划阶段就决定跳过的数量（同名/名字不合法）
     */
    public record Outcome(List<Path> created, List<Failure> failures, int skipped) {

        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        public int createdCount() {
            return created.size();
        }
    }

    /**
     * 按计划执行。
     *
     * @param plan     施工图（已解决撞名与非法名）
     * @param rules    命名规则（用它的类型与初始内容）
     * @param journal  撤销日志；为 null 时不记录（自检与测试可以省略）
     * @param progress 进度回调；可为 null
     */
    public Outcome execute(BatchCreatePlan plan, CreateRules rules, CreateJournal journal,
                           Progress progress) {
        if (plan == null || plan.isEmpty()) {
            return new Outcome(List.of(), List.of(), 0);
        }
        CreateRules effective = rules == null ? new CreateRules() : rules;
        effective.normalize();

        List<BatchCreatePlan.Entry> targets = plan.toCreate();
        List<Path> created = new ArrayList<>(targets.size());
        List<Failure> failures = new ArrayList<>();
        int done = 0;
        for (BatchCreatePlan.Entry entry : targets) {
            try {
                createOne(entry.path(), effective);
                created.add(entry.path());
            } catch (IOException | RuntimeException e) {
                failures.add(new Failure(entry.path(), describe(e)));
            }
            done++;
            if (progress != null) {
                progress.onProgress(done, targets.size(), entry.name());
            }
        }

        if (journal != null && !created.isEmpty()) {
            journal.record(effective.describe(), created);
        }
        return new Outcome(List.copyOf(created), List.copyOf(failures), plan.skippedCount());
    }

    /** 建一个。已存在就报错——调用方应当已经通过计划避开了这种情况。 */
    private void createOne(Path path, CreateRules rules) throws IOException {
        if (rules.target == CreateRules.Target.FOLDER) {
            // 不递归建父目录：目标目录本身必须存在（对话框已经校验过），
            // 递归建会把"目标目录写错了"这种错误悄悄变成一个多出来的目录树
            Files.createDirectory(path);
            return;
        }
        if (!rules.initialContent.isEmpty()) {
            Files.writeString(path, rules.initialContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } else {
            // CREATE_NEW 而不是 CREATE：CREATE 会<b>截断已存在的文件</b>，
            // 那就把"绝不覆盖"这条底线破了
            Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE).close();
        }
    }

    private static String describe(Exception e) {
        if (e instanceof java.nio.file.FileAlreadyExistsException) {
            return "同名已存在（已跳过，未覆盖）";
        }
        return e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : "：" + e.getMessage());
    }
}
