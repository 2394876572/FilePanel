package com.zean.filepanel.ui.dialog;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.store.TagStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 标签编辑对话框。
 *
 * <p>区分两种情形，因为它们的操作意图完全不同：
 * <ul>
 *   <li><b>选中单个文件</b>：完整编辑——可添加、可移除，
 *       并能看到这个文件当前有哪些标签（点击标签上的 × 移除）。</li>
 *   <li><b>选中多个文件</b>：只能<b>批量添加</b>。批量"移除"很容易误伤
 *       （有些文件有、有些没有，用户很难预期结果），所以刻意不提供。</li>
 * </ul>
 *
 * <p>已有的标签以可点击的候选形式列出，避免用户手打时出现"重要"和"重要 "这种
 * 看起来一样、实际是两个标签的情况。
 */
public final class TagDialog {

    /**
     * 编辑结果。
     *
     * @param tagsToAdd    要添加的标签
     * @param tagsToRemove 要移除的标签（仅单文件编辑时非空）
     */
    public record Result(List<String> tagsToAdd, List<String> tagsToRemove) {

        public boolean isEmpty() {
            return tagsToAdd.isEmpty() && tagsToRemove.isEmpty();
        }
    }

    private TagDialog() {
    }

    public static Optional<Result> show(Window owner, List<FileItem> items, TagStore store) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        boolean single = items.size() == 1;
        FileItem first = items.get(0);

        Set<String> toAdd = new LinkedHashSet<>();
        Set<String> toRemove = new LinkedHashSet<>();

        Label headline = new Label(single
                ? "为「" + first.name() + "」设置标签"
                : "为选中的 " + items.size() + " 项添加标签");
        headline.getStyleClass().add("dialog-headline");
        headline.setWrapText(true);

        // 单文件：显示当前标签，每个标签可点击移除
        FlowPane currentTags = new FlowPane(6, 6);
        if (single) {
            List<String> existing = store.tagsOf(first.path());
            if (existing.isEmpty()) {
                Label none = new Label("（暂无标签）");
                none.getStyleClass().add("dialog-dim");
                currentTags.getChildren().add(none);
            } else {
                for (String tag : existing) {
                    currentTags.getChildren().add(removableChip(tag, toRemove, currentTags));
                }
            }
        }

        TextField input = new TextField();
        input.setPromptText("输入新标签后回车或点“添加”");
        HBox.setHgrow(input, Priority.ALWAYS);

        Button addButton = new Button("添加");
        Label pendingLabel = new Label();
        pendingLabel.getStyleClass().add("dialog-dim");

        FlowPane pending = new FlowPane(6, 6);

        Runnable commitInput = () -> {
            String tag = TagStore.normalize(input.getText());
            if (tag == null) {
                input.clear();
                return;
            }
            if (single && store.tagsOf(first.path()).contains(tag)) {
                // 已存在：撤销可能的移除意图，并提示
                toRemove.remove(tag);
                pendingLabel.setText("「" + tag + "」已有该标签");
            } else {
                toAdd.add(tag);
                refreshPending(pending, toAdd, toRemove, pendingLabel);
            }
            input.clear();
        };

        addButton.setOnAction(e -> commitInput.run());
        input.setOnAction(e -> commitInput.run());

        HBox inputRow = new HBox(6, input, addButton);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        // 候选：库里已有的标签，点击即加入
        FlowPane candidates = new FlowPane(6, 6);
        Map<String, Integer> counts = store.tagCounts();
        if (counts.isEmpty()) {
            Label none = new Label("（这个文件夹里还没有任何标签，直接输入即可创建）");
            none.getStyleClass().add("dialog-dim");
            candidates.getChildren().add(none);
        } else {
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                Label chip = new Label(e.getKey() + " " + e.getValue());
                chip.getStyleClass().add("tag-chip-action");
                chip.setTooltip(new Tooltip("点击添加这个已有标签"));
                chip.setOnMouseClicked(event -> {
                    String tag = e.getKey();
                    if (single && store.tagsOf(first.path()).contains(tag)) {
                        return;
                    }
                    if (toAdd.add(tag)) {
                        refreshPending(pending, toAdd, toRemove, pendingLabel);
                    }
                });
                candidates.getChildren().add(chip);
            }
        }

        VBox content = new VBox(8);
        content.setPadding(new Insets(4, 4, 0, 4));
        content.setPrefWidth(520);
        content.getChildren().add(headline);
        if (single) {
            content.getChildren().addAll(
                    new Label("当前标签（点标签可移除）："), currentTags);
        }
        content.getChildren().addAll(
                new Label("新标签："), inputRow, pending, pendingLabel,
                new Label("已有标签："), candidates);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("标签");
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        ButtonType okType = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);
        com.zean.filepanel.ui.Themes.applyToDialog(dialog.getDialogPane(), owner);

        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.get() != okType) {
            return Optional.empty();
        }
        // 输入框里还有没提交的内容，一并算上——用户按了确定就是想要它
        String trailing = TagStore.normalize(input.getText());
        if (trailing != null) {
            toAdd.add(trailing);
            toRemove.remove(trailing);
        }
        Result result = new Result(new ArrayList<>(toAdd), new ArrayList<>(toRemove));
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    private static Label removableChip(String tag, Set<String> toRemove, FlowPane container) {
        Label chip = new Label(tag + "  ✕");
        chip.getStyleClass().add("tag-chip-action");
        chip.setTooltip(new Tooltip("点击移除这个标签"));
        chip.setOnMouseClicked(event -> {
            toRemove.add(tag);
            container.getChildren().remove(chip);
            if (container.getChildren().isEmpty()) {
                Label none = new Label("（已全部标记为移除）");
                none.getStyleClass().add("dialog-dim");
                container.getChildren().add(none);
            }
        });
        return chip;
    }

    private static void refreshPending(FlowPane pending, Set<String> toAdd,
                                       Set<String> toRemove, Label label) {
        pending.getChildren().clear();
        for (String tag : toAdd) {
            Label chip = new Label("+ " + tag);
            chip.getStyleClass().add("tag-chip-pending");
            chip.setOnMouseClicked(event -> {
                toAdd.remove(tag);
                refreshPending(pending, toAdd, toRemove, label);
            });
            pending.getChildren().add(chip);
        }
        label.setText(toAdd.isEmpty() ? "" : "点击可撤销");
    }
}
