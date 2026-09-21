package com.zean.filepanel.ops;

import java.util.Locale;

/**
 * 批量创建的命名规则（纯逻辑，不碰文件系统）。
 *
 * <h2>为什么单独一层</h2>
 * 和 {@link RenameRules} 同样的理由：生成名字、递增、边界（Z 之后进位）这些是最容易写错、
 * 又最容易被"看起来正常"掩盖的部分。做成不依赖磁盘的纯函数，就能用穷举测试覆盖，
 * 而不是靠在真实目录里建一堆东西来试。
 *
 * <h2>名字的构成</h2>
 * <pre>
 *   前缀 + 关键词 + 序号 + 后缀 [+ .扩展名]
 *   灰度发布 + 检测点 + 001    + _压力 + .docx
 * </pre>
 * 序号的位置固定在中段。用户不需要记这个顺序——对话框里会实时列出前几个名字，
 * 看到预览就知道会长什么样，这比用文字描述顺序可靠得多。
 *
 * <h2>只支持同一种类型</h2>
 * 一次要么全是文件夹、要么全是同一种扩展名的文件。混着建的界面复杂度（每一行选类型）
 * 远大于它带来的价值，而"建 20 个 txt"或"建 20 个文件夹"才是真实需求。
 */
public class CreateRules {

    /** 建什么。 */
    public enum Target {
        FOLDER("文件夹"),
        FILE("文件");

        private final String label;

        Target(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /**
         * 界面直接显示 {@code label()}。
         *
         * <p>覆盖 {@code toString()} 是为了从<b>根上</b>杜绝一类错误：
         * 下拉框不设转换器时显示的就是枚举名，于是界面上会冒出 {@code FOLDER} / {@code SKIP}
         * 这种英文——而且只有真的打开对话框才看得见，单测与自检都不会红。
         * 这里覆盖之后，任何新加的下拉框默认就是中文，不需要每处都记得设转换器。
         */
        @Override
        public String toString() {
            return label;
        }
    }

    /** 序号形式。 */
    public enum Sequence {
        NONE("不递增"),
        NUMBER("数字 1,2,3"),
        UPPER("大写字母 A,B,C"),
        LOWER("小写字母 a,b,c");

        private final String label;

        Sequence(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 下拉框直接显示中文；理由见 {@link Target#toString()}。 */
        @Override
        public String toString() {
            return label;
        }
    }

    /** 撞名了怎么办。 */
    public enum Conflict {
        /** 跳过（默认，最保守：什么都不覆盖）。 */
        SKIP("已存在就跳过"),
        /** 自动加序号后缀，尽量把用户要的数量凑齐。 */
        SUFFIX("已存在就自动改名（加 (2)、(3)…）");

        private final String label;

        Conflict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 下拉框直接显示中文；理由见 {@link Target#toString()}。 */
        @Override
        public String toString() {
            return label;
        }
    }

    /** 一次最多建多少。防止一次误操作把目录塞爆（五万个空文件已经很难正常浏览了）。 */
    public static final int MAX_COUNT = 50_000;

    /**
     * 预计耗时超过这个值就提示。
     *
     * <p>阈值按<b>时间</b>定而不是按数量定：按数量定会出现"300 项就警告数量较多、
     * 但预计只有 0.2 秒"这种自相矛盾的提示，用户看一眼就不信了。
     * 按时间定则保证"有警告 = 真的会等一会儿"。
     */
    public static final long WARN_MILLIS = 1_000;

    /** 预计耗时超过这个值就要求二次确认。 */
    public static final long STRONG_WARN_MILLIS = 5_000;

    /**
     * 单条创建的实测耗时（毫秒）。
     *
     * <p>本机实测（SSD，300 条取平均）：新建文件夹 0.336 ms、新建空文件 0.180 ms、
     * 带一小段内容的文件 0.206 ms。这里取 0.5 ms 作为估算基准——往上取整是为了
     * <b>宁可把时间估长一点</b>：估短了用户会以为卡死，估长了最多是白等一会。
     */
    public static final double MILLIS_PER_CREATE = 0.5;

    /**
     * 单条"创建后重新扫描"的实测耗时（毫秒）。
     *
     * <p>实测 113 个文件扫描 12~21 ms，约 0.15 ms/条；取 0.2 ms。
     * 这一项必须算进去：用户感知到的"卡住"其实是创建完那一瞬间的重新扫描。
     */
    public static final double MILLIS_PER_RESCAN = 0.2;

    /**
     * 对应提示阈值的数量（由时间反推，仅供界面文案与测试引用）。
     *
     * <p>必须声明在上面两个耗时常量<b>之后</b>：静态初始化里用简单名引用后面的常量
     * 属于"非法前向引用"，编译直接报错。
     */
    public static final int WARN_COUNT =
            (int) Math.ceil(WARN_MILLIS / (MILLIS_PER_CREATE + MILLIS_PER_RESCAN));

    /** 对应强警告阈值的数量。 */
    public static final int STRONG_WARN_COUNT =
            (int) Math.ceil(STRONG_WARN_MILLIS / (MILLIS_PER_CREATE + MILLIS_PER_RESCAN));

    public Target target = Target.FOLDER;
    /** 文件扩展名（不含点）。仅 {@link Target#FILE} 使用。 */
    public String extension = "txt";
    public String prefix = "";
    /** 名字里要包含的关键词。 */
    public String keyword = "";
    /** 放在序号之后的后缀（例如 {@code _压力}）。 */
    public String suffix = "";
    public Sequence sequence = Sequence.NUMBER;
    /** 序号起始值。数字形式是 1、2、3；字母形式 1=A、26=Z、27=AA。 */
    public int start = 1;
    /** 数字序号的补零位数（3 → 001）。仅 {@link Sequence#NUMBER} 使用。 */
    public int digits = 3;
    public int count = 10;
    public Conflict conflict = Conflict.SKIP;
    /** 初始内容。仅文件、且非空时写入。 */
    public String initialContent = "";

    /** 规范化：把非法值夹到可用范围，避免界面传入 0 或负数导致奇怪的名单。 */
    public void normalize() {
        if (target == null) {
            target = Target.FOLDER;
        }
        if (sequence == null) {
            sequence = Sequence.NUMBER;
        }
        if (conflict == null) {
            conflict = Conflict.SKIP;
        }
        if (extension == null) {
            extension = "";
        }
        prefix = prefix == null ? "" : prefix;
        keyword = keyword == null ? "" : keyword;
        suffix = suffix == null ? "" : suffix;
        initialContent = initialContent == null ? "" : initialContent;
        count = Math.max(1, Math.min(MAX_COUNT, count));
        digits = Math.max(1, Math.min(10, digits));
        // 起始值允许 0 和负数（有时用户就想要 0 打头），但不能小到让字母进位算不出来
        start = Math.max(0, start);
    }

    /** 扩展名（不含点、小写）；文件夹为空串。 */
    public String extensionOrEmpty() {
        if (target != Target.FILE) {
            return "";
        }
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        return ext.startsWith(".") ? ext.substring(1) : ext;
    }

    /**
     * 第 index 项（从 0 开始）的完整名字。
     *
     * <p>注意：<b>不管</b>磁盘上是否已存在。撞名由 {@link BatchCreatePlan} 处理，
     * 这里只负责"按规则应该叫什么"。
     */
    public String nameAt(int index) {
        int ordinal = Math.max(0, start) + Math.max(0, index);
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(keyword).append(sequenceText(ordinal)).append(suffix);
        String ext = extensionOrEmpty();
        if (!ext.isEmpty()) {
            sb.append('.').append(ext);
        }
        return sb.toString();
    }

    /** 序号文本。 */
    public String sequenceText(int ordinal) {
        return switch (sequence == null ? Sequence.NUMBER : sequence) {
            case NONE -> "";
            case NUMBER -> String.format(Locale.ROOT, "%0" + Math.max(1, digits) + "d", ordinal);
            case UPPER -> alpha(ordinal);
            case LOWER -> alpha(ordinal).toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 数字 → 字母序号，1=A、26=Z、27=AA、52=AZ、53=BA……
     *
     * <p>用 Excel 列名那套进位规则，而不是"超过 26 就变成 a1"之类的自定义规则：
     * 用户见到 Z 之后自然会期待 AA，跟直觉不一致的进位会让人以为程序算错了。
     *
     * <p>{@code ordinal <= 0} 时返回空串（对应"从 0 开始"的写法，此时序号不占位）。
     */
    public static String alpha(int ordinal) {
        if (ordinal <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = ordinal;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.append((char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.reverse().toString();
    }

    /** 供界面显示的一句话描述。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("新建 ").append(count).append(" 个").append(target.label());
        if (target == Target.FILE) {
            String ext = extensionOrEmpty();
            sb.append(ext.isEmpty() ? "（无扩展名）" : "（." + ext + "）");
        }
        sb.append("：").append(prefix).append(keyword)
                .append(sequence == Sequence.NUMBER ? String.format(Locale.ROOT,
                        "%0" + Math.max(1, digits) + "d", Math.max(0, start))
                        : sequenceText(Math.max(1, start)))
                .append(suffix);
        if (count > 1) {
            sb.append(" … ").append(prefix).append(keyword)
                    .append(sequenceText(Math.max(1, start) + count - 1)).append(suffix);
        }
        sb.append("　撞名策略：").append(conflict.label());
        return sb.toString();
    }

    /**
     * 数量风险等级。按<b>预计耗时</b>分档，而不是按数量：
     * 只有"真的要等一会儿"才配得上一个警告，否则用户会把警告当噪音。
     *
     * <p>先看<b>原始</b>数量再看夹取后的值：{@link #normalize()} 会把超限数量夹回上限
     * （防止程序化调用时一次生成上亿条计划把内存吃光），
     * 但如果先夹再判断，"超过上限"这个状态就永远观察不到了。
     *
     * @return 0=正常、1=提示预计耗时、2=需要二次确认、3=超过上限直接拒绝
     */
    public int warningLevel() {
        if (count > MAX_COUNT) {
            return 3;
        }
        long millis = estimatedMillis();
        if (millis > STRONG_WARN_MILLIS) {
            return 2;
        }
        if (millis > WARN_MILLIS) {
            return 1;
        }
        return 0;
    }

    /** 预计总耗时（毫秒）：创建 + 创建后的重新扫描。 */
    public long estimatedMillis() {
        return Math.round(count * (MILLIS_PER_CREATE + MILLIS_PER_RESCAN));
    }

    /**
     * 预计耗时的人话描述。
     *
     * <p>刻意把"重新扫描"说出来：用户看到的那一下卡顿发生在创建之后，
     * 如果只说"创建耗时"，他会以为程序在别的地方卡住了。
     */
    public String describeEstimate() {
        long millis = estimatedMillis();
        String time;
        if (millis < 1000) {
            time = "不到 1 秒";
        } else if (millis < 60_000) {
            time = "约 " + (millis / 1000) + " 秒";
        } else {
            time = "约 " + (millis / 60_000) + " 分钟以上";
        }
        return "预计" + time + "（含创建后重新扫描；机械硬盘或网络盘会明显更慢）";
    }

    /**
     * 规则是否可用。
     *
     * <p>故意的严格：名字必须能过一次 {@link RenameService#validateName}。
     * 与其等建到第 300 个才发现名字里有非法字符，不如在对话框里当场说清楚。
     */
    public String validate() {
        // 范围检查必须在 normalize 之前：normalize 会把超限数量夹回上限，
        // 夹完之后再检查就永远看不到"超限"这个状态了
        if (count < 1) {
            return "数量至少为 1";
        }
        if (count > MAX_COUNT) {
            return "一次最多创建 " + MAX_COUNT + " 项，请减少数量或分几次创建";
        }

        normalize();
        // 数量大于 1 又不递增 → 所有项都会叫同一个名字。
        // 这种规则在计划阶段还看不出来（磁盘上都还不存在），但执行时第 2 项必然撞名失败，
        // 于是会留下"建了 1 个，其余 19 个都失败"的残局。所以在对话框里就拦住。
        if (count > 1 && sequence == Sequence.NONE) {
            return "要建 " + count + " 项就必须选一种递增方式（数字或字母），否则所有名字都一样";
        }
        if (target == Target.FILE) {
            String ext = extensionOrEmpty();
            if (ext.isEmpty()) {
                return "请填写文件扩展名（例如 txt）";
            }
            RenameService.Validation extCheck = RenameService.validateName("x." + ext);
            if (!extCheck.ok()) {
                return "扩展名不可用：" + extCheck.message();
            }
        }
        // 首尾各查一个：中间项由同样的拼接方式产生，这两项能覆盖"前缀/后缀本身有问题"的情况
        int[] probes = {0, Math.max(0, count - 1)};
        for (int probe : probes) {
            String name = nameAt(probe);
            if (name.isBlank()) {
                return "名字不能为空：请填写前缀、关键词或选择一种递增方式";
            }
            RenameService.Validation check = RenameService.validateName(name);
            if (!check.ok()) {
                return "生成的名字「" + name + "」不可用：" + check.message();
            }
        }
        return null;
    }
}
