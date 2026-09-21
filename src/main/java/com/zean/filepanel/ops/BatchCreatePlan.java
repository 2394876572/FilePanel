package com.zean.filepanel.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次批量创建的"施工图"：把规则展开成具体名称，并处理撞名。
 *
 * <h2>为什么要先出一份计划，而不是边算边建</h2>
 * 三个理由，每一个都来自"没先算清楚就动手"的代价：
 * <ol>
 *   <li><b>用户要先看到会发生什么</b>：对话框里的预览、预计数量、会跳过几个，
 *       全部来自这份计划。等建完再告诉用户"跳过了 37 个"就太晚了。</li>
 *   <li><b>撞名要在动手前一次性解决</b>：{@code SUFFIX} 策略下第 5 个要叫 {@code x (2)}，
 *       不能依赖"建到第 3 个时才发现 x 占用了"——那样边建边改，名字顺序就乱了。</li>
 *   <li><b>非法名字要在动手前拦住</b>：建到一半才发现某个名字不可用，会留下一半残局。</li>
 * </ol>
 *
 * <p>{@link #build} 会读磁盘（判断是否已存在），因此它<b>不是</b>纯函数；
 * 但它的产出一旦生成就不再变化，执行阶段只照着建，不会重新决策。
 */
public final class BatchCreatePlan {

    /** 计划里一项的结局。 */
    public enum Status {
        /** 会新建。 */
        CREATE,
        /** 已存在，跳过（撞名策略 = 跳过）。 */
        SKIP_EXISTS,
        /** 已存在，改用带序号的新名字（撞名策略 = 自动改名）。 */
        RENAMED,
        /** 名字本身不合法（含非法字符、保留设备名等），跳过并说明原因。 */
        ILLEGAL
    }

    /**
     * 计划里的一项。
     *
     * @param name    最终要用的名字（{@code RENAMED} 时已经是改过的名字）
     * @param path    最终路径
     * @param wanted  规则本来想用的名字，便于界面解释"为什么不是我预想的那个"
     * @param status  结局
     * @param note    补充说明（非法原因等）
     */
    public record Entry(String name, Path path, String wanted, Status status, String note) {
    }

    /** 撞名自动改名时最多尝试多少个候选，超过就放弃这一项（防止极端情况下死循环）。 */
    static final int MAX_SUFFIX_TRIES = 500;

    private final List<Entry> entries;

    private BatchCreatePlan(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * 生成计划。
     *
     * @param targetDir 目标目录；为 null 或不存在时返回空计划（由调用方先校验）
     * @param rules     命名规则
     */
    public static BatchCreatePlan build(Path targetDir, CreateRules rules) {
        if (targetDir == null || rules == null) {
            return new BatchCreatePlan(List.of());
        }
        CreateRules effective = rules;
        effective.normalize();

        List<Entry> entries = new ArrayList<>(effective.count);
        for (int i = 0; i < effective.count; i++) {
            String wanted = effective.nameAt(i);

            // 顺序很重要：<b>先验名字，再拼路径</b>。
            // Path.resolve 遇到 < > | 这类字符会直接抛 InvalidPathException——
            // 那会让"某一项名字非法"升级成"整份计划都生成不出来"，
            // 对话框里表现为点一下按钮就崩。这类名字本来就该被标成"跳过并说明原因"。
            RenameService.Validation check = RenameService.validateName(wanted);
            if (!check.ok()) {
                entries.add(new Entry(wanted, null, wanted, Status.ILLEGAL,
                        "名字不可用：" + check.message()));
                continue;
            }

            Path wantedPath;
            try {
                wantedPath = targetDir.resolve(wanted);
            } catch (RuntimeException e) {
                entries.add(new Entry(wanted, null, wanted, Status.ILLEGAL,
                        "名字不可用：" + e.getClass().getSimpleName()));
                continue;
            }

            if (!Files.exists(wantedPath)) {
                entries.add(new Entry(wanted, wantedPath, wanted, Status.CREATE, ""));
                continue;
            }

            if (effective.conflict == CreateRules.Conflict.SKIP) {
                entries.add(new Entry(wanted, wantedPath, wanted, Status.SKIP_EXISTS,
                        "同名已存在"));
                continue;
            }

            // 自动改名：只对"文件"加后缀，文件夹也照样加（Windows 允许 名称 (2) 这种文件夹名）
            Path alternative = findFreeName(targetDir, wanted, effective.target);
            if (alternative == null) {
                entries.add(new Entry(wanted, wantedPath, wanted, Status.SKIP_EXISTS,
                        "同名已存在，且自动改名尝试 " + MAX_SUFFIX_TRIES + " 次仍未找到可用名字"));
            } else {
                entries.add(new Entry(alternative.getFileName().toString(), alternative, wanted,
                        Status.RENAMED, ""));
            }
        }
        return new BatchCreatePlan(entries);
    }

    /**
     * 找一个还没被占用的名字：{@code 报表.docx} → {@code 报表 (2).docx} → {@code 报表 (3).docx} …
     *
     * <p>后缀插在扩展名<b>之前</b>：{@code 报表 (2).docx} 而不是 {@code 报表.docx (2)}。
     * 后者会让文件失去扩展名，双击直接打不开——这是同类功能最常见的坑。
     */
    static Path findFreeName(Path dir, String name, CreateRules.Target target) {
        String base = name;
        String ext = "";
        if (target == CreateRules.Target.FILE) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                base = name.substring(0, dot);
                ext = name.substring(dot);
            }
        }
        for (int n = 2; n <= MAX_SUFFIX_TRIES + 1; n++) {
            Path candidate = dir.resolve(base + " (" + n + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** 会新建的项（含自动改名后的）。 */
    public List<Entry> toCreate() {
        return entries.stream()
                .filter(e -> e.status() == Status.CREATE || e.status() == Status.RENAMED)
                .toList();
    }

    /** 被跳过的项（已存在或名字非法）。 */
    public List<Entry> skipped() {
        return entries.stream().filter(e -> e.status() != Status.CREATE
                && e.status() != Status.RENAMED).toList();
    }

    public int total() {
        return entries.size();
    }

    public int toCreateCount() {
        return toCreate().size();
    }

    public int skippedCount() {
        return skipped().size();
    }

    public int renamedCount() {
        return (int) entries.stream().filter(e -> e.status() == Status.RENAMED).count();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 供界面显示的一句话小结。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("将新建 ").append(toCreateCount()).append(" 项");
        if (skippedCount() > 0) {
            sb.append("，跳过 ").append(skippedCount()).append(" 项（同名/名字不可用）");
        }
        if (renamedCount() > 0) {
            sb.append("，其中 ").append(renamedCount()).append(" 项因撞名自动改名");
        }
        return sb.toString();
    }

    /**
     * 预览文本：前若干个 + 最后一个。
     *
     * <p>只列前几个是为了让对话框不被撑爆，但<b>必须带上最后一个</b>——
     * 序号位数不够导致最后一个名字变长这类问题，只看开头几个是发现不了的。
     */
    public String preview(int head) {
        List<Entry> create = toCreate();
        if (create.isEmpty()) {
            return "（没有可新建的项）";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(Math.max(1, head), create.size());
        for (int i = 0; i < limit; i++) {
            sb.append("·  ").append(create.get(i).name());
            if (create.get(i).status() == Status.RENAMED) {
                sb.append("　（原本是 ").append(create.get(i).wanted()).append("）");
            }
            sb.append('\n');
        }
        if (create.size() > limit) {
            sb.append("…  共 ").append(create.size()).append(" 项\n");
            Entry last = create.get(create.size() - 1);
            sb.append("·  ").append(last.name()).append("　（最后一项）\n");
        }
        return sb.toString();
    }

    /** 供测试与界面使用：按名字找一项。 */
    public Entry find(String name) {
        if (name == null) {
            return null;
        }
        for (Entry e : entries) {
            if (e.name().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "BatchCreatePlan[" + summary() + "]";
    }
}
