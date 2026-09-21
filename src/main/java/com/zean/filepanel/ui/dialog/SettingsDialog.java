package com.zean.filepanel.ui.dialog;

import com.zean.filepanel.core.DeletePolicy;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 设置对话框（目前只有"删除"一节）。
 *
 * <h2>为什么现在才有这个框</h2>
 * D4 写的是"阈值默认 1 GB 且可配置"，但在此之前 {@code withThreshold()}、
 * {@code DeletePolicy(long, boolean)} 与 {@code PRESET_THRESHOLDS} 都<b>只有定义、没有调用者</b>——
 * 阈值实际上被写死在代码里，用户没有任何入口能改。一个"可配置"却没有入口的设置，
 * 等于把定制和验证都锁在源码里。
 *
 * <h2>为什么把"验证通道"顺手做出来</h2>
 * 想验证"不小于阈值 → 永久删除"这条分支，本来需要造一个 1 GB 的文件。但阈值一旦可配，
 * 只要把它调成 {@code 1 MB}，用一个小文件就能走完同一条分支——判定只是一次比较，与量级无关。
 * 所以这个框不只是"给用户定制用"，它同时是<b>让那条分支可被实测</b>的开关。
 *
 * <h2>三处刻意的设计</h2>
 * <ol>
 *   <li><b>下限不静默生效</b>：输入小于 1 MiB 时不是悄悄改成 1 MiB，而是当场提示
 *       "将按 1 MiB 处理"，规则预览里也显示真正生效的值。
 *       显示值与生效值不一致，是设置界面最容易被骂的地方。</li>
 *   <li><b>规则预览实时更新</b>：改任何一处，下面那句"小于 X 放入回收站，不小于则永久删除"
 *       立刻跟着变。用户不必先保存再回主界面去猜自己刚设了什么。</li>
 *   <li><b>勾了"一律走回收站"就禁用阈值输入</b>：此时阈值<b>不再生效</b>，
 *       还让用户编辑一个不起作用的数字是一种误导。</li>
 * </ol>
 *
 * <h2>为什么拆出 {@link Panel}</h2>
 * 对话框被拆成"内容面板"与"弹窗外壳"两层，于是自检和截图可以只构建面板、
 * 不弹窗、不阻塞——{@code showAndWait()} 会卡住 JavaFX 线程，
 * 而没有这层拆分就无法对界面做任何自动断言。
 */
public final class SettingsDialog {

    /** 用户确认后的设置。 */
    public record Result(long thresholdBytes, boolean alwaysRecycle) {
    }

    /** 自定义项的显示文本。 */
    private static final String CUSTOM_LABEL = "自定义…";

    /** 可选单位。刻意只有 MB/GB：阈值下限是 1 MiB，用 KB 表达没有意义。 */
    static final String[] UNITS = {"MB", "GB"};

    private SettingsDialog() {
    }

    /** 预设项：显示文本 -> 字节数。用 {@link LinkedHashMap} 保证顺序稳定。 */
    private static Map<String, Long> presets() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Long bytes : DeletePolicy.PRESET_THRESHOLDS) {
            map.put(DeletePolicy.humanSize(bytes), bytes);
        }
        return map;
    }

    /**
     * 把"数字 + 单位"解成字节数。
     *
     * <p>解析失败一律返回 {@code -1} 而不是抛异常：输入框的内容每敲一下都在变，
     * 中间态（空串、只输了一个小数点）必然是"非法"的——那不是错误，只是还没输完。
     *
     * <p>按 1024 进制换算（与 {@link DeletePolicy#humanSize(long)} 同一套口径），
     * 所以界面上写 "1 MB" 得到的正好是 1 MiB，也就是阈值下限。
     */
    public static long parseThreshold(String number, String unit) {
        if (number == null || number.isBlank()) {
            return -1;
        }
        double value;
        try {
            value = Double.parseDouble(number.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        if (!Double.isFinite(value) || value <= 0) {
            return -1;
        }
        long multiplier = switch (unit == null ? "" : unit.trim().toUpperCase(Locale.ROOT)) {
            case "GB" -> DeletePolicy.GIB;
            case "MB" -> 1024L * 1024;
            default -> -1L;
        };
        if (multiplier < 0) {
            return -1;
        }
        double bytes = value * multiplier;
        // 溢出保护：double 转 long 越界会饱和成 Long.MAX_VALUE，那样阈值变成
        // "永远不会永久删除"，与用户的意图正好相反，所以直接判为非法而不是夹住
        if (bytes > 9.0e18) {
            return -1;
        }
        return (long) bytes;
    }

    /** 规则预览文本。与 {@link DeletePolicy#describe()} 同源，不另写一套措辞。 */
    public static String describeRule(long thresholdBytes, boolean alwaysRecycle) {
        return new DeletePolicy(thresholdBytes, alwaysRecycle).describe();
    }

    /**
     * 供自检使用：设置面板的回显与实时预览断言。
     *
     * <p>刻意<b>不弹窗</b>：{@code showAndWait()} 会阻塞 JavaFX 线程，一旦阻塞，
     * 自检就没法再对界面做任何断言。所以面板与外壳是分开的，这里只驱动面板。
     *
     * <p>这些断言覆盖的是"显示值与生效值必须一致"——设置界面最常见、也最伤信任的错法
     * 就是这两者不一致（显示 1 GB、实际生效 1 MiB）。
     */
    public static String selfCheck(DeletePolicy current, List<String> failures) {
        StringBuilder sb = new StringBuilder("  --- 设置面板回显与预览 ---\n");
        DeletePolicy base = current == null ? new DeletePolicy() : current;
        Panel panel = new Panel(base);

        sb.append("    回显：输入控件可编辑=").append(panel.thresholdInputEnabled())
                .append("　规则=").append(panel.ruleText()).append('\n');
        if (!panel.ruleText().equals("当前规则：" + base.describe())) {
            failures.add("设置面板回显的规则与当前策略不一致：面板「" + panel.ruleText()
                    + "」 vs 策略「" + base.describe() + "」");
        }

        // 调到下限：阈值变化必须立刻反映到预览，而且不能留提示（下限是合法值）
        panel.enterCustomValue("123", "MB");
        String atMinimum = panel.ruleText();
        sb.append("    改为 123 MB：规则=").append(atMinimum)
                .append("　提示=").append(panel.noticeText().isEmpty() ? "（空）" : panel.noticeText())
                .append('\n');
        if (!atMinimum.equals("当前规则：" + describeRule(123L * 1024 * 1024, false))) {
            failures.add("改了阈值但规则预览没有跟着变：「" + atMinimum + "」");
        }
        if (!panel.noticeText().isEmpty()) {
            failures.add("合法输入不应出现提示，实际：" + panel.noticeText());
        }

        // 低于下限：必须提示"将按 1 MiB 处理"，且预览显示真正生效的值（而不是用户输的值）
        panel.enterCustomValue("0.5", "MB");
        sb.append("    输入 0.5 MB（低于下限）：规则=").append(panel.ruleText())
                .append("　提示=").append(panel.noticeText()).append('\n');
        if (!panel.ruleText().equals("当前规则："
                + describeRule(DeletePolicy.MIN_THRESHOLD_BYTES, false))) {
            failures.add("低于下限时预览应显示真正生效的下限值，实际：「" + panel.ruleText() + "」");
        }
        if (panel.noticeText().isEmpty()) {
            failures.add("低于下限时应明确提示被抬高，而不是静默改值");
        }

        // 非法输入：必须判为无效，且预览退回原值而不是显示一个荒唐数字
        panel.enterCustomValue("abc", "MB");
        sb.append("    输入 abc：有效=").append(panel.isValid())
                .append("　提示=").append(panel.noticeText()).append('\n');
        if (panel.isValid()) {
            failures.add("非法输入必须判为无效（否则会写出一个坏阈值）");
        }
        if (panel.noticeText().isEmpty()) {
            failures.add("非法输入应有明确提示");
        }
        panel.enterCustomValue("0", "MB");
        if (panel.isValid()) {
            failures.add("阈值 0 必须判为无效");
        }

        // "一律走回收站"必须禁用阈值输入（此时阈值不生效，可编辑即误导）
        panel.enterCustomValue("1", "GB");
        boolean enabledBefore = panel.thresholdInputEnabled();
        panel.setAlwaysRecycle(true);
        boolean enabledAfter = panel.thresholdInputEnabled();
        sb.append("    勾选“一律走回收站”：阈值输入 ").append(enabledBefore).append(" -> ")
                .append(enabledAfter).append("　规则=").append(panel.ruleText()).append('\n');
        if (enabledAfter) {
            failures.add("勾选“一律走回收站”后阈值输入仍可编辑，会误导用户以为它还有效");
        }
        if (!panel.ruleText().contains("一律放入回收站")) {
            failures.add("勾选“一律走回收站”后规则预览没有说明阈值已被忽略：「" + panel.ruleText() + "」");
        }
        return sb.toString();
    }

    /**
     * 只构建内容面板，不弹窗。
     *
     * <p>供自检与截图使用。返回的面板已经完成了全部事件接线，
     * 因此"改一个控件、预览跟着变"这件事是可以被自动断言的。
     */
    public static Parent buildContentForPreview(DeletePolicy current) {
        return new Panel(current).root;
    }

    /**
     * 弹出设置对话框。
     *
     * @param current 当前生效的策略，用来回显
     * @return 用户确认后的设置；取消或输入非法时为空
     */
    public static Optional<Result> show(Window owner, DeletePolicy current) {
        Panel panel = new Panel(current);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("设置");
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(panel.root);

        ButtonType okType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);
        com.zean.filepanel.ui.Themes.applyToDialog(dialog.getDialogPane(), owner);

        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(okType);
        panel.setOkButton(okButton);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != okType || !panel.isValid()) {
            // 走到这里说明校验被绕过（例如直接回车）。宁可什么都不改，也不要写入一个坏值。
            return Optional.empty();
        }
        return Optional.of(new Result(panel.effectiveThresholdBytes(), panel.isAlwaysRecycle()));
    }

    /** 内容面板：持有控件、接线、并提供自检需要的只读取值。 */
    static final class Panel {
        private final DeletePolicy base;
        private final VBox root;
        private final ComboBox<String> presetBox = new ComboBox<>();
        private final TextField numberField = new TextField();
        private final ComboBox<String> unitBox = new ComboBox<>();
        private final HBox customRow;
        private final CheckBox alwaysRecycleBox = new CheckBox("所有删除一律放入回收站（忽略阈值）");
        private final Label ruleLabel = new Label();
        private final Label noticeLabel = new Label();
        private javafx.scene.Node okButton;

        Panel(DeletePolicy current) {
            this.base = current == null ? new DeletePolicy() : current;

            presetBox.getItems().addAll(presets().keySet());
            presetBox.getItems().add(CUSTOM_LABEL);
            presetBox.setPrefWidth(170);

            numberField.setPrefColumnCount(8);
            unitBox.getItems().addAll(UNITS);
            unitBox.setPrefWidth(80);
            customRow = new HBox(6, numberField, unitBox);
            customRow.setAlignment(Pos.CENTER_LEFT);

            ruleLabel.setWrapText(true);
            ruleLabel.getStyleClass().add("dialog-headline");
            noticeLabel.setWrapText(true);

            echoCurrent(base);

            Label thresholdLabel = new Label("超过这个大小的文件，删除时【永久删除】而不进回收站：");
            thresholdLabel.setWrapText(true);
            Label hint = new Label("下限 " + DeletePolicy.humanSize(DeletePolicy.MIN_THRESHOLD_BYTES)
                    + "（防止配置损坏导致所有删除都变成永久删除）。"
                    + "把它调到 1 MB，就能用一个小文件验证“永久删除”这条分支。");
            hint.setWrapText(true);
            hint.getStyleClass().add("dialog-dim");

            root = new VBox(10, thresholdLabel, presetBox, customRow, alwaysRecycleBox,
                    ruleLabel, noticeLabel, hint);
            root.setPadding(new Insets(6, 4, 0, 4));
            root.setPrefWidth(540);

            presetBox.setOnAction(e -> refresh());
            unitBox.setOnAction(e -> refresh());
            numberField.textProperty().addListener((obs, old, now) -> refresh());
            alwaysRecycleBox.setOnAction(e -> refresh());
            refresh();
        }

        /** 按当前策略回显：能对上预设就选预设，否则进"自定义"。 */
        private void echoCurrent(DeletePolicy policy) {
            // 注意：这一行必须在任何 return 之前。早先它只写在"自定义"分支里，
            // 于是用户上次勾了"一律走回收站"、且阈值恰好是预设值时，重开设置框会看到未勾选状态——
            // 显示与生效不一致，正是这个界面最不能出的错。
            alwaysRecycleBox.setSelected(policy.alwaysRecycle());

            for (Map.Entry<String, Long> e : presets().entrySet()) {
                if (e.getValue() == policy.thresholdBytes()) {
                    presetBox.setValue(e.getKey());
                    unitBox.setValue("MB");
                    return;
                }
            }
            presetBox.setValue(CUSTOM_LABEL);
            long gib = DeletePolicy.GIB;
            if (policy.thresholdBytes() % gib == 0) {
                numberField.setText(String.valueOf(policy.thresholdBytes() / gib));
                unitBox.setValue("GB");
            } else {
                numberField.setText(String.valueOf(policy.thresholdBytes() / (1024 * 1024)));
                unitBox.setValue("MB");
            }
        }

        void setOkButton(javafx.scene.Node okButton) {
            this.okButton = okButton;
            applyValidity();
        }

        /** 界面当前"真正会生效"的阈值；输入非法时回退到原值，避免预览显示一个荒唐数字。 */
        long effectiveThresholdBytes() {
            long raw = rawThresholdBytes();
            return raw > 0 ? DeletePolicy.clampThreshold(raw) : base.thresholdBytes();
        }

        boolean isValid() {
            return rawThresholdBytes() > 0;
        }

        boolean isAlwaysRecycle() {
            return alwaysRecycleBox.isSelected();
        }

        boolean isCustomEntry() {
            return CUSTOM_LABEL.equals(presetBox.getValue());
        }

        /** 供自检使用：规则预览那一行的实际文本。 */
        String ruleText() {
            return ruleLabel.getText();
        }

        /** 供自检使用：提示行的实际文本。 */
        String noticeText() {
            return noticeLabel.getText();
        }

        /** 供自检使用：直接改输入（等价于用户键入）。 */
        void enterCustomValue(String number, String unit) {
            presetBox.setValue(CUSTOM_LABEL);
            unitBox.setValue(unit);
            numberField.setText(number);
            refresh();
        }

        /** 供自检使用：切换"一律走回收站"。 */
        void setAlwaysRecycle(boolean value) {
            alwaysRecycleBox.setSelected(value);
            refresh();
        }

        /** 供自检使用：阈值输入控件此时是否可编辑。 */
        boolean thresholdInputEnabled() {
            return !presetBox.isDisabled() && !numberField.isDisabled();
        }

        private long rawThresholdBytes() {
            if (isCustomEntry()) {
                return parseThreshold(numberField.getText(), unitBox.getValue());
            }
            Long preset = presets().get(presetBox.getValue());
            return preset == null ? -1 : preset;
        }

        /** 统一的重算入口：任何一处变动都走它，杜绝"预览与实际不一致"。 */
        private void refresh() {
            boolean custom = isCustomEntry();
            customRow.setVisible(custom);
            customRow.setManaged(custom);

            long raw = rawThresholdBytes();
            boolean valid = raw > 0;
            boolean forced = isAlwaysRecycle();

            noticeLabel.getStyleClass().removeAll("dialog-warning", "dialog-error");
            if (!valid) {
                noticeLabel.setText("请输入一个大于 0 的数字。");
                noticeLabel.getStyleClass().add("dialog-error");
            } else if (DeletePolicy.isBelowMinimum(raw)) {
                noticeLabel.setText("输入小于下限 "
                        + DeletePolicy.humanSize(DeletePolicy.MIN_THRESHOLD_BYTES) + "，将按 "
                        + DeletePolicy.humanSize(DeletePolicy.MIN_THRESHOLD_BYTES) + " 处理。");
                noticeLabel.getStyleClass().add("dialog-warning");
            } else {
                noticeLabel.setText("");
            }

            ruleLabel.setText("当前规则：" + describeRule(effectiveThresholdBytes(), forced));

            // 勾了"一律走回收站"时阈值不生效，此时再让用户编辑它是误导
            presetBox.setDisable(forced);
            numberField.setDisable(forced);
            unitBox.setDisable(forced);
            applyValidity();
        }

        private void applyValidity() {
            if (okButton != null) {
                okButton.setDisable(!isValid());
            }
        }
    }
}
