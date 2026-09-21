package com.zean.filepanel.ui.dialog;

import com.zean.filepanel.ops.BatchCreatePlan;
import com.zean.filepanel.ops.CreateRules;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 批量创建对话框。
 *
 * <h2>为什么"实时预览"是这个框的核心</h2>
 * 命名规则有五个输入（前缀/关键词/序号/后缀/扩展名）和一个递增方式，光靠文字描述，
 * 用户没法确定"包含什么关键词"到底会出现在名字的哪个位置。
 * 所以这里不解释顺序，而是<b>把前几个名字直接列出来</b>：
 * 看到预览就知道会长成什么样，顺序问题自然消失。
 *
 * <h2>数量保护分三档，措辞都基于实测</h2>
 * 单条创建实测约 0.5 ms（含创建后的重新扫描），所以：
 * <ul>
 *   <li>超过 {@value com.zean.filepanel.ops.CreateRules#WARN_COUNT} 项：显示预计耗时</li>
 *   <li>超过 {@value com.zean.filepanel.ops.CreateRules#STRONG_WARN_COUNT} 项：橙色警告，
 *       点"创建"还要再确认一次</li>
 *   <li>超过 {@value com.zean.filepanel.ops.CreateRules#MAX_COUNT} 项：直接不让点</li>
 * </ul>
 * 上限的意义不是性能，而是可恢复性：五万个空文件已经没法正常浏览和手工收拾了，
 * 撤销也要跑很久。
 *
 * <h2>默认撞名策略是"跳过"</h2>
 * "自动改名"更方便，但它会让实际生成的名字与用户填的规则不一致。
 * 默认选更保守的那个：先保证规则说的话就是发生的事，需要凑数量的人可以主动切过去。
 */
public final class BatchCreateDialog {

    /** 用户确认后的结果：规则 + 已经算好的施工图。 */
    public record Result(CreateRules rules, BatchCreatePlan plan) {
    }

    /**
     * 供自检使用：在没有对话框（不阻塞 JavaFX 线程）的前提下驱动面板，断言预览与数量保护。
     *
     * <p>必须在<b>临时目录</b>上跑：自检跑在用户真实的文件夹里，
     * 而这里要验的是"撞名了会怎样"，那就必须真的存在同名文件——
     * 绝不能为了验证而往用户的目录里放东西。
     */
    public static String selfCheck(Path scratchDir, List<String> failures) {
        StringBuilder sb = new StringBuilder("  --- 批量创建（M10）---\n");
        Panel panel = new Panel(scratchDir, 10);

        // 1) 命名预览：顺序与首尾项
        panel.enter("灰度发布", "检测点", "_压力");
        panel.setTarget(CreateRules.Target.FILE, "docx");
        panel.setSequence(CreateRules.Sequence.NUMBER, 1, 3);
        panel.setCount(12);
        String preview = panel.previewText();
        String summary = panel.summaryText();
        sb.append("    预览（前缀=灰度发布 关键词=检测点 后缀=_压力 数量=12）：\n");
        for (String line : preview.split("\n")) {
            sb.append("      ").append(line).append('\n');
        }
        sb.append("    小结：").append(summary).append('\n');
        sb.append("    预计：").append(panel.estimateText()).append('\n');
        if (!preview.contains("灰度发布检测点001_压力.docx")) {
            failures.add("预览里没有出现按规则生成的名字：" + preview);
        }
        if (!preview.contains("灰度发布检测点012_压力.docx")) {
            failures.add("预览必须包含最后一项（位数不够这类问题只看开头发现不了）：" + preview);
        }
        if (!summary.contains("将新建 12 项")) {
            failures.add("小结没有说清要建多少：" + summary);
        }
        if (!panel.estimateText().contains("重新扫描")) {
            failures.add("预计耗时应当说明含创建后的重新扫描：" + panel.estimateText());
        }

        // 2) 撞名：默认跳过；切成自动改名后应改在扩展名之前
        try {
            java.nio.file.Files.writeString(scratchDir.resolve("灰度发布检测点001_压力.docx"), "已存在");
        } catch (java.io.IOException e) {
            failures.add("自检准备同名文件失败：" + e);
        }
        panel.setConflict(CreateRules.Conflict.SKIP);
        String skipped = panel.summaryText();
        sb.append("    撞名=跳过：").append(skipped).append('\n');
        if (!skipped.contains("跳过 1 项")) {
            failures.add("同名已存在时应报「跳过 1 项」，实际：" + skipped);
        }
        panel.setConflict(CreateRules.Conflict.SUFFIX);
        String renamed = panel.summaryText();
        String renamedPreview = panel.previewText();
        sb.append("    撞名=自动改名：").append(renamed).append('\n');
        if (!renamed.contains("自动改名")) {
            failures.add("切成自动改名后小结应说明有几项被改名，实际：" + renamed);
        }
        if (!renamedPreview.contains("灰度发布检测点001_压力 (2).docx")) {
            failures.add("自动改名必须把 (2) 插在扩展名之前，实际预览：" + renamedPreview);
        }

        // 3) 数量保护三档
        panel.setCount(50);
        String atNormal = panel.warnText();
        panel.setCount(CreateRules.WARN_COUNT + 100);
        String atWarn = panel.warnText();
        panel.setCount(CreateRules.STRONG_WARN_COUNT + 100);
        boolean needsConfirm = panel.needsExtraConfirmation();
        String atStrong = panel.warnText();
        sb.append("    数量 50 → 警告=").append(atNormal.isEmpty() ? "（无）" : atNormal).append('\n');
        sb.append("    数量 ").append(CreateRules.WARN_COUNT + 100).append(" → 提示=").append(atWarn)
                .append('\n');
        sb.append("    数量 ").append(CreateRules.STRONG_WARN_COUNT + 100).append(" → 需二次确认=")
                .append(needsConfirm).append("　提示=").append(atStrong).append('\n');
        if (!atNormal.isEmpty()) {
            failures.add("数量不大时不该有警告，实际：" + atNormal);
        }
        if (!atWarn.contains("预计")) {
            failures.add("数量较多时应显示预计耗时，实际：" + atWarn);
        }
        if (!needsConfirm) {
            failures.add("数量很大时必须要求二次确认");
        }
        if (!atStrong.contains("再确认")) {
            failures.add("强警告里应当说明还会再确认一次，实际：" + atStrong);
        }

        // 4) 数量上限：界面这一层的保护是"根本填不进去"（Spinner 把上限焊死），
        //    比"填进去再报错"更硬。规则层的拒绝由 CreateRulesTest 覆盖。
        panel.setCount(CreateRules.MAX_COUNT);
        boolean maxValid = panel.isValid();
        panel.setCount(CreateRules.MAX_COUNT + 1);
        int clamped = panel.rules().count;
        boolean overValid = panel.isValid();
        sb.append("    上限 ").append(CreateRules.MAX_COUNT).append("：可用=").append(maxValid)
                .append("　尝试填 ").append(CreateRules.MAX_COUNT + 1)
                .append("（实际被限成 ").append(clamped).append("）可用=").append(overValid).append('\n');
        if (!maxValid) {
            failures.add("恰好等于上限时应当允许创建");
        }
        if (clamped != CreateRules.MAX_COUNT) {
            failures.add("数量输入框没有把值限制在上限内，实际得到 " + clamped);
        }

        // 5) 不递增 + 数量大于 1：规则阶段就该拦住
        panel.setSequence(CreateRules.Sequence.NONE, 1, 3);
        panel.setCount(5);
        String noSeq = panel.warnText();
        boolean noSeqValid = panel.isValid();
        sb.append("    不递增+数量5：可用=").append(noSeqValid).append("　提示=").append(noSeq).append('\n');
        if (noSeqValid) {
            failures.add("不递增却要建多个时必须拦住（否则执行时第 2 项起必然撞名）");
        }

        // 6) 字母递增在预览里也要进位：数量取 27，让最后一项正好落在 Z 之后的 AA。
        //    预览只列前 5 个 + 最后 1 个，所以"第 27 项"必须正好是最后一项才看得到
        //    （第一次这里写成 28，最后一项成了 AB，断言自然不成立）
        panel.setSequence(CreateRules.Sequence.UPPER, 1, 3);
        panel.setCount(27);
        String alphaPreview = panel.previewText();
        panel.setCount(3);
        if (!alphaPreview.contains("灰度发布检测点AA_压力.docx")) {
            failures.add("字母递增到第 27 项应进位成 AA，实际预览：" + alphaPreview);
        }
        return sb.toString();
    }

    private BatchCreateDialog() {
    }

    /**
     * 只构建内容面板，不弹窗。
     *
     * <p>供截图与布局回归使用。刻意<b>不预填任何输入</b>：这样截图里显示的就是各个
     * 输入框的提示词，而"提示词能不能看出位置"正是需要被看见、无法被断言的东西。
     */
    public static javafx.scene.Parent buildContentForPreview(Path targetDir, int count) {
        return new Panel(targetDir, count).root;
    }

    /**
     * 弹出对话框。
     *
     * @param targetDir 新东西建在哪个目录（就是当前被管理的根目录）
     * @param count    初始数量，便于连续使用时继承上一次
     * @return 用户确认后的规则与施工图；取消时为空
     */
    public static Optional<Result> show(Window owner, Path targetDir, int count) {
        if (targetDir == null) {
            return Optional.empty();
        }
        Panel panel = new Panel(targetDir, count);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("批量创建");
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(panel.root);
        ButtonType okType = new ButtonType("创建", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);
        com.zean.filepanel.ui.Themes.applyToDialog(dialog.getDialogPane(), owner);

        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(okType);
        panel.setOkButton(okButton);

        // 数量很大时点"创建"要再过一道：确认框里带上数量与预计耗时，
        // 让用户在被拦住的这一刻就知道自己要付出多少时间
        if (okButton != null) {
            okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                if (panel.needsExtraConfirmation() && !panel.confirmLargeCount(owner)) {
                    event.consume();
                }
            });
        }

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != okType || !panel.isValid()) {
            return Optional.empty();
        }
        return Optional.of(new Result(panel.rules(), panel.plan()));
    }

    /** 内容面板。拆出来是为了让自检能在不弹窗（不阻塞 JavaFX 线程）的前提下驱动它。 */
    static final class Panel {

        private final Path targetDir;
        private final VBox root = new VBox(8);

        private final ComboBox<CreateRules.Target> targetBox = new ComboBox<>();
        private final TextField extensionField = new TextField();
        private final Label extensionLabel = new Label("扩展名");
        private final TextField prefixField = new TextField();
        private final TextField keywordField = new TextField();
        private final TextField suffixField = new TextField();
        private final ComboBox<CreateRules.Sequence> sequenceBox = new ComboBox<>();
        private final Spinner<Integer> startSpinner = new Spinner<>(0, 999_999, 1);
        private final Spinner<Integer> digitsSpinner = new Spinner<>(1, 10, 3);
        private final Label startLabel = new Label("起始");
        private final Label digitsLabel = new Label("位数");
        private final Spinner<Integer> countSpinner =
                new Spinner<>(1, CreateRules.MAX_COUNT, 10);
        private final ComboBox<CreateRules.Conflict> conflictBox = new ComboBox<>();
        private final TextArea contentArea = new TextArea();
        private final Label contentLabel = new Label("初始内容（可留空）");

        private final Label summaryLabel = new Label();
        private final Label estimateLabel = new Label();
        private final Label warnLabel = new Label();
        private final TextArea previewArea = new TextArea();

        private javafx.scene.Node okButton;

        Panel(Path targetDir, int count) {
            this.targetDir = targetDir;

            targetBox.getItems().addAll(CreateRules.Target.values());
            targetBox.setValue(CreateRules.Target.FOLDER);
            targetBox.setOnAction(e -> refresh());

            extensionField.setText("txt");
            extensionField.setPrefColumnCount(8);
            extensionField.textProperty().addListener((o, a, b) -> refresh());

            // 提示词刻意不写"例如 灰度发布"这种具体例子，而是写成"位置示意"：
            // XXX记录 / ......记录...... / 记录XXX
            // 具体例子不能说明"我填的东西会出现在名字的哪一段"，而位置示意可以——
            // 用户看一眼就知道前缀在最前、关键词在中间、后缀在最后，
            // 不用去读任何说明文字。（这是用户直接提出的改法。）
            prefixField.setPromptText("XXX记录");
            prefixField.setPrefColumnCount(12);
            prefixField.textProperty().addListener((o, a, b) -> refresh());

            keywordField.setPromptText("......记录......");
            keywordField.setPrefColumnCount(12);
            keywordField.textProperty().addListener((o, a, b) -> refresh());

            suffixField.setPromptText("记录XXX");
            suffixField.setPrefColumnCount(12);
            suffixField.textProperty().addListener((o, a, b) -> refresh());

            sequenceBox.getItems().addAll(CreateRules.Sequence.values());
            sequenceBox.setValue(CreateRules.Sequence.NUMBER);
            sequenceBox.setOnAction(e -> refresh());

            startSpinner.setPrefWidth(96);
            startSpinner.valueProperty().addListener((o, a, b) -> refresh());
            digitsSpinner.setPrefWidth(80);
            digitsSpinner.valueProperty().addListener((o, a, b) -> refresh());

            // 统一走 valueFactory 改值：Spinner.setValue 在泛型推断下会挑到不合适的重载，
            // 而 getValueFactory().setValue(...) 没有任何歧义（startSpinner 那边也是这么写的）
            countSpinner.getValueFactory().setValue(
                    Math.max(1, Math.min(CreateRules.MAX_COUNT, count)));
            countSpinner.setEditable(true);
            countSpinner.setPrefWidth(120);
            countSpinner.valueProperty().addListener((o, a, b) -> refresh());

            conflictBox.getItems().addAll(CreateRules.Conflict.values());
            conflictBox.setValue(CreateRules.Conflict.SKIP);
            conflictBox.setOnAction(e -> refresh());

            contentArea.setPrefRowCount(2);
            contentArea.setPromptText("留空则创建空文件");
            contentArea.textProperty().addListener((o, a, b) -> refresh());

            summaryLabel.getStyleClass().add("dialog-headline");
            estimateLabel.getStyleClass().add("dialog-dim");
            warnLabel.setWrapText(true);

            previewArea.setEditable(false);
            previewArea.setWrapText(false);
            previewArea.getStyleClass().add("details-area");
            previewArea.setPrefRowCount(7);
            previewArea.setPrefColumnCount(56);

            HBox targetRow = new HBox(6, new Label("创建"), targetBox, extensionLabel, extensionField);
            targetRow.setAlignment(Pos.CENTER_LEFT);
            HBox nameRow = new HBox(6, new Label("前缀"), prefixField,
                    new Label("关键词"), keywordField, new Label("后缀"), suffixField);
            nameRow.setAlignment(Pos.CENTER_LEFT);

            // 名字各段的位置示意。光看三个输入框，用户不知道拼起来是什么顺序；
            // 这行把它直接摆出来，并与下面的实时预览使用同一套措辞。
            Label orderHint = new Label("名字拼成的顺序：前缀 → 关键词 → 序号 → 后缀 → 扩展名"
                    + "（下面预览里能直接看到结果）");
            orderHint.getStyleClass().add("dialog-dim");
            orderHint.setWrapText(true);
            HBox seqRow = new HBox(6, new Label("递增"), sequenceBox,
                    startLabel, startSpinner, digitsLabel, digitsSpinner);
            seqRow.setAlignment(Pos.CENTER_LEFT);
            HBox countRow = new HBox(6, new Label("数量"), countSpinner,
                    new Label("撞名"), conflictBox);
            countRow.setAlignment(Pos.CENTER_LEFT);

            Label targetHint = new Label("目标目录：" + targetDir);
            targetHint.getStyleClass().add("dialog-dim");
            targetHint.setWrapText(true);

            root.getChildren().addAll(targetHint, targetRow, nameRow, orderHint, seqRow, countRow,
                    contentLabel, contentArea, summaryLabel, estimateLabel, warnLabel,
                    new Label("前几个名字会长这样："), previewArea);
            root.setPadding(new Insets(6, 4, 0, 4));
            root.setPrefWidth(660);

            refresh();
        }

        void setOkButton(javafx.scene.Node okButton) {
            this.okButton = okButton;
            applyValidity();
        }

        /** 当前规则（已规范化）。 */
        CreateRules rules() {
            CreateRules rules = new CreateRules();
            rules.target = targetBox.getValue();
            rules.extension = extensionField.getText();
            rules.prefix = prefixField.getText();
            rules.keyword = keywordField.getText();
            rules.suffix = suffixField.getText();
            rules.sequence = sequenceBox.getValue();
            rules.start = startSpinner.getValue();
            rules.digits = digitsSpinner.getValue();
            rules.count = countSpinner.getValue();
            rules.conflict = conflictBox.getValue();
            rules.initialContent = contentArea.getText();
            rules.normalize();
            return rules;
        }

        /** 当前施工图（会读磁盘判断撞名）。 */
        BatchCreatePlan plan() {
            return BatchCreatePlan.build(targetDir, rules());
        }

        boolean isValid() {
            return rules().validate() == null && rules().warningLevel() < 3;
        }

        boolean needsExtraConfirmation() {
            return rules().warningLevel() == 2;
        }

        /** 大数量的二次确认。 */
        boolean confirmLargeCount(Window owner) {
            CreateRules rules = rules();
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(owner);
            dialog.setTitle("确认创建");
            dialog.setHeaderText(null);
            ButtonType yes = new ButtonType("仍然创建", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(yes, ButtonType.CANCEL);
            Label text = new Label("即将创建 " + rules.count + " 个" + rules.target.label() + "。\n"
                    + rules.describeEstimate() + "\n\n"
                    + "撤销功能可以整体收拾，但数量太多时收拾本身也要花时间。确定继续吗？");
            text.setWrapText(true);
            VBox box = new VBox(text);
            box.setPadding(new Insets(6, 4, 0, 4));
            box.setPrefWidth(420);
            dialog.getDialogPane().setContent(box);
            com.zean.filepanel.ui.Themes.applyToDialog(dialog.getDialogPane(), owner);
            Optional<ButtonType> answer = dialog.showAndWait();
            return answer.isPresent() && answer.get() == yes;
        }

        // -------------------------------------------------------------- 预览

        /** 统一的重算入口：任何一处变动都走它，保证预览、小结、警告三者永远一致。 */
        private void refresh() {
            boolean isFile = rules().target == CreateRules.Target.FILE;
            extensionField.setVisible(isFile);
            extensionField.setManaged(isFile);
            extensionLabel.setVisible(isFile);
            extensionLabel.setManaged(isFile);
            contentArea.setVisible(isFile);
            contentArea.setManaged(isFile);
            contentLabel.setVisible(isFile);
            contentLabel.setManaged(isFile);

            boolean numbered = rules().sequence == CreateRules.Sequence.NUMBER;
            digitsSpinner.setVisible(numbered);
            digitsSpinner.setManaged(numbered);
            digitsLabel.setVisible(numbered);
            digitsLabel.setManaged(numbered);
            boolean sequenced = rules().sequence != CreateRules.Sequence.NONE;
            startSpinner.setVisible(sequenced);
            startSpinner.setManaged(sequenced);
            startLabel.setVisible(sequenced);
            startLabel.setManaged(sequenced);

            CreateRules rules = rules();
            String problem = rules.validate();
            BatchCreatePlan plan = problem == null ? plan() : BatchCreatePlan.build(null, rules);

            summaryLabel.setText(problem == null ? plan.summary() : "规则还不能用");
            estimateLabel.setText(problem == null ? rules.describeEstimate() : "");

            warnLabel.getStyleClass().removeAll("dialog-warning", "dialog-error");
            if (problem != null) {
                warnLabel.setText(problem);
                warnLabel.getStyleClass().add("dialog-error");
            } else {
                switch (rules.warningLevel()) {
                    case 1 -> {
                        warnLabel.setText("数量较多：" + rules.describeEstimate());
                        warnLabel.getStyleClass().add("dialog-warning");
                    }
                    case 2 -> {
                        warnLabel.setText("数量很大：" + rules.describeEstimate()
                                + "。点「创建」后还会再确认一次。");
                        warnLabel.getStyleClass().add("dialog-warning");
                    }
                    case 3 -> {
                        warnLabel.setText("一次最多创建 " + CreateRules.MAX_COUNT
                                + " 项。请减少数量，或分几次创建。");
                        warnLabel.getStyleClass().add("dialog-error");
                    }
                    default -> warnLabel.setText("");
                }
            }

            previewArea.setText(problem == null ? plan.preview(5) : "");
            applyValidity();
        }

        private void applyValidity() {
            if (okButton != null) {
                okButton.setDisable(!isValid());
            }
        }

        // -------------------------------------------------- 供自检使用的入口

        String summaryText() {
            return summaryLabel.getText();
        }

        String estimateText() {
            return estimateLabel.getText();
        }

        String warnText() {
            return warnLabel.getText();
        }

        String previewText() {
            return previewArea.getText();
        }

        void enter(String prefix, String keyword, String suffix) {
            prefixField.setText(prefix);
            keywordField.setText(keyword);
            suffixField.setText(suffix);
            refresh();
        }

        void setTarget(CreateRules.Target target, String extension) {
            targetBox.setValue(target);
            extensionField.setText(extension);
            refresh();
        }

        void setSequence(CreateRules.Sequence sequence, int start, int digits) {
            sequenceBox.setValue(sequence);
            startSpinner.getValueFactory().setValue(start);
            digitsSpinner.getValueFactory().setValue(digits);
            refresh();
        }

        void setCount(int count) {
            countSpinner.getValueFactory().setValue(count);
            refresh();
        }

        void setConflict(CreateRules.Conflict conflict) {
            conflictBox.setValue(conflict);
            refresh();
        }

        void setInitialContent(String content) {
            contentArea.setText(content);
            refresh();
        }
    }
}
