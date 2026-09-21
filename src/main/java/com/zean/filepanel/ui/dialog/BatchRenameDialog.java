package com.zean.filepanel.ui.dialog;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.ops.BatchRenamePlan;
import com.zean.filepanel.ops.RenameRules;
import com.zean.filepanel.ui.FormatUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 批量重命名对话框。
 *
 * <h2>核心设计：预览即承诺</h2>
 * 左侧改规则、右侧实时看到每一行的"原名 → 新名"以及状态（将重命名 / 无变化 / 冲突 / 不合法）。
 * 对话框返回的是<b>用户看到的那个方案本身</b>，而不是"规则"，由界面层直接执行它——
 * 这样就不存在"预览时算出来一个结果、执行时又算一遍算出另一个结果"的可能。
 *
 * <h2>有问题的项怎么处理</h2>
 * 提供"跳过有问题的项"与"有冲突就中止"两种策略，默认跳过。
 * 默认跳过的理由：一批 50 个文件里因为 1 个重名就全部不动，用户往往更难受；
 * 而冲突项本身会被明确列出，不会让人以为"全都改好了"。
 */
public final class BatchRenameDialog {

    /**
     * 用户确认的执行方案。
     *
     * @param entries     要执行的条目（就是预览里那些"将重命名"的行）
     * @param description 写进撤销日志的描述
     * @param rules       本次使用的规则，供调用方记住、下次打开时预填
     */
    public record Result(List<BatchRenamePlan.Entry> entries, String description, RenameRules rules) {
    }

    private final List<FileItem> items;
    private final LocalDateTime now;

    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final CheckBox regexBox = new CheckBox("正则");
    private final CheckBox caseSensitiveBox = new CheckBox("区分大小写");
    private final TextField prefixField = new TextField();
    private final TextField suffixField = new TextField();
    private final CheckBox numberingBox = new CheckBox("序号");
    private final Spinner<Integer> numberStart = new Spinner<>(0, 999_999, 1);
    private final Spinner<Integer> numberStep = new Spinner<>(1, 1000, 1);
    private final Spinner<Integer> numberDigits = new Spinner<>(1, 10, 2);
    private final ChoiceBox<RenameRules.NumberPosition> numberPosition = new ChoiceBox<>();
    private final ChoiceBox<RenameRules.CaseMode> caseMode = new ChoiceBox<>();
    private final ChoiceBox<RenameRules.ExtensionMode> extensionMode = new ChoiceBox<>();
    private final TextField templateField = new TextField();
    private final CheckBox skipProblemsBox = new CheckBox("跳过有问题的项（不勾选则发现问题时中止整批）");

    private final TableView<BatchRenamePlan.Entry> preview = new TableView<>();
    private final Label summaryLabel = new Label();
    private final Label warningLabel = new Label();

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton ruleModeButton = new RadioButton("规则模式");
    private final RadioButton templateModeButton = new RadioButton("模板模式");

    private BatchRenamePlan plan = BatchRenamePlan.build(List.of(), new RenameRules(), LocalDateTime.now());
    private ButtonType okType;

    private BatchRenameDialog(Window owner, List<FileItem> items, RenameRules initial) {
        this.items = List.copyOf(items);
        this.now = LocalDateTime.now();

        Dialog<Result> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("批量重命名");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        if (initial != null) {
            applyInitial(initial);
        }

        BorderPane root = new BorderPane();
        root.setLeft(buildForm());
        root.setCenter(buildPreview());
        root.setPadding(new Insets(4));
        root.setPrefSize(980, 520);

        dialog.getDialogPane().setContent(root);
        okType = new ButtonType("执行重命名", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(1020);
        com.zean.filepanel.ui.Themes.applyToDialog(dialog.getDialogPane(), owner);

        wireRuleChanges();
        rebuildPreview();

        dialog.setResultConverter(button -> {
            if (button != okType) {
                return null;
            }
            List<BatchRenamePlan.Entry> entries = skipProblemsBox.isSelected()
                    ? plan.actionable() : plan.entries();
            if (entries.isEmpty()) {
                return null;
            }
            RenameRules rules = currentRules();
            return new Result(entries, "批量重命名 " + entries.size() + " 项（"
                    + rules.describe() + "）", rules);
        });

        this.dialog = dialog;
    }

    private final Dialog<Result> dialog;

    /** 弹出对话框。 */
    public static Optional<Result> show(Window owner, List<FileItem> items, RenameRules initial) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        return new BatchRenameDialog(owner, items, initial).dialog.showAndWait();
    }

    private void applyInitial(RenameRules rules) {
        findField.setText(rules.find);
        replaceField.setText(rules.replace);
        regexBox.setSelected(rules.useRegex);
        caseSensitiveBox.setSelected(rules.caseSensitive);
        prefixField.setText(rules.prefix);
        suffixField.setText(rules.suffix);
        numberingBox.setSelected(rules.numbering);
        numberStart.getValueFactory().setValue(rules.numberStart);
        numberStep.getValueFactory().setValue(rules.numberStep);
        numberDigits.getValueFactory().setValue(rules.numberDigits);
        numberPosition.setValue(rules.numberPosition);
        caseMode.setValue(rules.caseMode);
        extensionMode.setValue(rules.extensionMode);
        templateField.setText(rules.template);
    }

    // ------------------------------------------------------------------ 表单

    private VBox buildForm() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        grid.setPadding(new Insets(6, 12, 6, 4));

        int row = 0;
        grid.add(new Label("模式"), 0, row);
        ruleModeButton.setToggleGroup(modeGroup);
        templateModeButton.setToggleGroup(modeGroup);
        ruleModeButton.setSelected(true);
        HBox modes = new HBox(12, ruleModeButton, templateModeButton);
        modes.setAlignment(Pos.CENTER_LEFT);
        grid.add(modes, 1, row++, 2, 1);

        grid.add(new Label("查找"), 0, row);
        findField.setPromptText("要替换的文字");
        findField.setPrefColumnCount(14);
        grid.add(findField, 1, row);
        HBox findOpts = new HBox(8, regexBox, caseSensitiveBox);
        findOpts.setAlignment(Pos.CENTER_LEFT);
        grid.add(findOpts, 2, row++);

        grid.add(new Label("替换为"), 0, row);
        replaceField.setPromptText("留空表示删除");
        grid.add(replaceField, 1, row++, 2, 1);

        grid.add(new Label("前缀"), 0, row);
        prefixField.setPromptText("加在名字前面");
        grid.add(prefixField, 1, row++, 2, 1);

        grid.add(new Label("后缀"), 0, row);
        suffixField.setPromptText("加在名字后面");
        grid.add(suffixField, 1, row++, 2, 1);

        numberingBox.setTooltip(new Tooltip("例如 报告-01、报告-03、报告-05"));
        grid.add(numberingBox, 0, row);
        numberStart.setPrefWidth(78);
        numberStep.setPrefWidth(70);
        numberDigits.setPrefWidth(66);
        numberPosition.getItems().setAll(RenameRules.NumberPosition.values());
        numberPosition.setValue(RenameRules.NumberPosition.AFTER);
        numberPosition.setPrefWidth(64);
        HBox numbering = new HBox(4,
                new Label("起始"), numberStart,
                new Label("步长"), numberStep,
                new Label("位数"), numberDigits,
                new Label("位置"), numberPosition);
        numbering.setAlignment(Pos.CENTER_LEFT);
        grid.add(numbering, 1, row++, 2, 1);

        grid.add(new Label("主名"), 0, row);
        caseMode.getItems().setAll(RenameRules.CaseMode.values());
        caseMode.setValue(RenameRules.CaseMode.KEEP);
        grid.add(caseMode, 1, row++, 2, 1);

        grid.add(new Label("扩展名"), 0, row);
        extensionMode.getItems().setAll(RenameRules.ExtensionMode.values());
        extensionMode.setValue(RenameRules.ExtensionMode.KEEP);
        grid.add(extensionMode, 1, row++, 2, 1);

        grid.add(new Label("模板"), 0, row);
        templateField.setPromptText("例如 灰度发布-{date}-{n}　可用：{name} {ext} {n} {nn} {date} {time} {yyyy} {MM} {dd}");
        templateField.setPrefColumnCount(28);
        grid.add(templateField, 1, row++, 2, 1);

        skipProblemsBox.setSelected(true);
        summaryLabel.getStyleClass().add("dialog-headline");
        summaryLabel.setWrapText(true);
        warningLabel.getStyleClass().add("dialog-message");
        warningLabel.setWrapText(true);

        VBox box = new VBox(6, grid, skipProblemsBox, summaryLabel, warningLabel);
        box.setPrefWidth(430);
        box.setPadding(new Insets(0, 8, 0, 0));
        ScrollPane scroller = new ScrollPane(box);
        scroller.setFitToWidth(true);
        scroller.setPrefWidth(446);
        scroller.getStyleClass().add("dialog-scroller");

        // 包装一层返回 VBox，但左侧实际用 ScrollPane，避免小屏幕上控件被裁掉
        VBox wrapper = new VBox(scroller);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        return wrapper;
    }

    private VBox buildPreview() {
        TableColumn<BatchRenamePlan.Entry, String> original = new TableColumn<>("原名");
        original.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().item().name()));
        original.setPrefWidth(220);

        TableColumn<BatchRenamePlan.Entry, String> renamed = new TableColumn<>("新名");
        renamed.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().newName()));
        renamed.setPrefWidth(220);

        TableColumn<BatchRenamePlan.Entry, BatchRenamePlan.Entry> status =
                new TableColumn<>("状态");
        status.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        status.setPrefWidth(180);
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(BatchRenamePlan.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setTooltip(null);
                    getStyleClass().removeAll("rename-ok", "rename-problem", "rename-unchanged");
                    return;
                }
                String text = entry.status().label();
                if (entry.status() == BatchRenamePlan.Status.OK && entry.caseOnlyChange()) {
                    text += "（仅大小写）";
                }
                if (!entry.message().isEmpty()) {
                    text += "：" + entry.message();
                }
                setText(text);
                getStyleClass().removeAll("rename-ok", "rename-problem", "rename-unchanged");
                getStyleClass().add(switch (entry.status()) {
                    case OK -> "rename-ok";
                    case UNCHANGED -> "rename-unchanged";
                    default -> "rename-problem";
                });
            }
        });

        preview.getColumns().setAll(List.of(original, renamed, status));
        preview.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        preview.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(BatchRenamePlan.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                getStyleClass().removeAll("rename-row-problem", "rename-row-unchanged");
                if (!empty && entry != null) {
                    switch (entry.status()) {
                        case ILLEGAL, CONFLICT -> getStyleClass().add("rename-row-problem");
                        case UNCHANGED -> getStyleClass().add("rename-row-unchanged");
                        default -> {
                        }
                    }
                }
            }
        });

        Label header = new Label("预览（" + items.size() + " 项，按当前顺序编号）");
        header.getStyleClass().add("dialog-headline");

        VBox box = new VBox(6, header, preview);
        VBox.setVgrow(preview, Priority.ALWAYS);
        box.setPadding(new Insets(6, 4, 6, 8));
        return box;
    }

    // ------------------------------------------------------------------ 联动

    private void wireRuleChanges() {
        // 规则模式与模板模式互斥：同时生效会让用户无法预期结果
        ruleModeButton.selectedProperty().addListener((o, was, is) -> updateModeEnabled());
        templateModeButton.selectedProperty().addListener((o, was, is) -> {
            templateField.setDisable(!is);
            rebuildPreview();
        });

        findField.textProperty().addListener((o, a, b) -> rebuildPreview());
        replaceField.textProperty().addListener((o, a, b) -> rebuildPreview());
        regexBox.selectedProperty().addListener((o, a, b) -> rebuildPreview());
        caseSensitiveBox.selectedProperty().addListener((o, a, b) -> rebuildPreview());
        prefixField.textProperty().addListener((o, a, b) -> rebuildPreview());
        suffixField.textProperty().addListener((o, a, b) -> rebuildPreview());
        numberingBox.selectedProperty().addListener((o, a, b) -> rebuildPreview());
        numberStart.valueProperty().addListener((o, a, b) -> rebuildPreview());
        numberStep.valueProperty().addListener((o, a, b) -> rebuildPreview());
        numberDigits.valueProperty().addListener((o, a, b) -> rebuildPreview());
        numberPosition.valueProperty().addListener((o, a, b) -> rebuildPreview());
        caseMode.valueProperty().addListener((o, a, b) -> rebuildPreview());
        extensionMode.valueProperty().addListener((o, a, b) -> rebuildPreview());
        templateField.textProperty().addListener((o, a, b) -> rebuildPreview());
        skipProblemsBox.selectedProperty().addListener((o, a, b) -> updateSummary());

        updateModeEnabled();
    }

    private void updateModeEnabled() {
        boolean template = templateModeButton.isSelected();
        templateField.setDisable(!template);
        findField.setDisable(template);
        replaceField.setDisable(template);
        regexBox.setDisable(template);
        caseSensitiveBox.setDisable(template);
        prefixField.setDisable(template);
        suffixField.setDisable(template);
        numberingBox.setDisable(template);
        numberStart.setDisable(template);
        numberStep.setDisable(template);
        numberDigits.setDisable(template);
        numberPosition.setDisable(template);
        caseMode.setDisable(template);
        rebuildPreview();
    }

    private RenameRules currentRules() {
        RenameRules rules = new RenameRules();
        if (templateModeButton.isSelected()) {
            rules.template = templateField.getText() == null ? "" : templateField.getText();
            // 模板模式下扩展名处理仍然有效（用户可能想同时统一扩展名大小写）
            rules.extensionMode = extensionMode.getValue() == null
                    ? RenameRules.ExtensionMode.KEEP : extensionMode.getValue();
            return rules;
        }
        rules.find = text(findField);
        rules.replace = text(replaceField);
        rules.useRegex = regexBox.isSelected();
        rules.caseSensitive = caseSensitiveBox.isSelected();
        rules.prefix = text(prefixField);
        rules.suffix = text(suffixField);
        rules.numbering = numberingBox.isSelected();
        rules.numberStart = numberStart.getValue();
        rules.numberStep = numberStep.getValue();
        rules.numberDigits = numberDigits.getValue();
        rules.numberPosition = numberPosition.getValue();
        rules.caseMode = caseMode.getValue() == null ? RenameRules.CaseMode.KEEP : caseMode.getValue();
        rules.extensionMode = extensionMode.getValue() == null
                ? RenameRules.ExtensionMode.KEEP : extensionMode.getValue();
        return rules;
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText();
    }

    private void rebuildPreview() {
        RenameRules rules = currentRules();
        plan = BatchRenamePlan.build(items, rules, now);
        preview.setItems(FXCollections.observableArrayList(plan.entries()));

        if (!rules.hasAnyRule()) {
            setWarning("还没有设置任何规则，先在上面填点东西。", false);
        } else if (plan.hasProblems()) {
            setWarning("有 " + plan.problemCount() + " 项存在问题（红色行）。"
                    + (skipProblemsBox.isSelected()
                    ? "将跳过它们，只处理其余项。"
                    : "当前设置为发现问题即中止整批。"), true);
        } else {
            setWarning("", false);
        }
        updateSummary();
    }

    private void setWarning(String text, boolean warning) {
        warningLabel.setText(text);
        warningLabel.getStyleClass().removeAll("dialog-error", "dialog-dim");
        warningLabel.getStyleClass().add(warning ? "dialog-error" : "dialog-dim");
    }

    private void updateSummary() {
        summaryLabel.setText(plan.summary() + "　·　" + FormatUtil.count(items.size()) + " 项已选中");
        javafx.scene.Node okButton = dialog.getDialogPane().lookupButton(okType);
        if (okButton != null) {
            int actionable = skipProblemsBox.isSelected() ? plan.actionableCount() : plan.size();
            boolean canRun = actionable > 0 && !(plan.hasProblems() && !skipProblemsBox.isSelected());
            okButton.setDisable(!canRun);
        }
    }
}
