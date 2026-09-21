package com.zean.filepanel.ui.dialog;

import com.zean.filepanel.core.FileItem;
import com.zean.filepanel.ops.RenameService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 重命名对话框。
 *
 * <h2>为什么把主名和扩展名分成两个输入框</h2>
 * "扩展名保护"如果做成"只让你改主名"，用户偶尔真的需要改扩展名时就只能去资源管理器；
 * 如果做成"整串随便改"，又极易在选中文本时误删扩展名，得到一个打不开的文件。
 * 拆成两个框、扩展名预填，既不会被误删，也可以有意修改；改动扩展名时额外给一句提醒。
 *
 * <h2>为什么校验是实时的</h2>
 * 非法字符、保留设备名、重名冲突都是"提交后才发现"最恼人的错误类型——
 * 用户已经点过一次确定，还得重新打开对话框再来一遍。
 * 这里每敲一个字符就跑一次校验并把确定按钮置灰，错误信息就显示在输入框下方。
 */
public class RenameDialog extends Dialog<String> {

    private final TextField baseField = new TextField();
    private final TextField extensionField = new TextField();
    private final Label messageLabel = new Label();
    private final FileItem item;

    private RenameDialog(Window owner, FileItem item) {
        this.item = item;

        initOwner(owner);
        setTitle("重命名");
        setHeaderText(null);
        setResizable(true);

        RenameService.NameParts parts = RenameService.split(item.name());
        baseField.setText(parts.base());
        extensionField.setText(parts.extension().isEmpty() ? "" : parts.extension().substring(1));
        extensionField.setPrefColumnCount(6);
        baseField.setPrefColumnCount(28);

        // 目录没有扩展名，隐藏扩展名输入以免误导
        boolean hasExtensionField = !item.directory();

        HBox nameRow = new HBox(2);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        nameRow.getChildren().add(baseField);
        if (hasExtensionField) {
            Label dot = new Label(".");
            dot.getStyleClass().add("rename-dot");
            nameRow.getChildren().addAll(dot, extensionField);
        }

        Label original = new Label("原名称：" + item.name());
        original.getStyleClass().add("dialog-dim");

        Label location = new Label("位置：" + item.locationText());
        location.getStyleClass().add("dialog-dim");

        messageLabel.getStyleClass().add("dialog-message");
        messageLabel.setWrapText(true);
        messageLabel.setMinHeight(34);

        VBox content = new VBox(8, original, location, new Label("新名称："), nameRow, messageLabel);
        content.setPadding(new Insets(4, 4, 0, 4));
        content.setPrefWidth(460);

        getDialogPane().setContent(content);
        ButtonType okType = new ButtonType("重命名", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);
        com.zean.filepanel.ui.Themes.applyToDialog(getDialogPane(), owner);

        baseField.textProperty().addListener((obs, was, is) -> revalidate());
        extensionField.textProperty().addListener((obs, was, is) -> revalidate());
        revalidate();

        // 返回完整的新文件名
        setResultConverter(button -> button == okType ? buildName() : null);

        javafx.application.Platform.runLater(() -> {
            baseField.requestFocus();
            baseField.selectAll();
        });
    }

    /** 弹出对话框并返回用户输入的新文件名。 */
    public static Optional<String> show(Window owner, FileItem item) {
        RenameDialog dialog = new RenameDialog(owner, item);
        return dialog.showAndWait();
    }

    private String buildName() {
        String base = baseField.getText() == null ? "" : baseField.getText().trim();
        String extension = extensionField.getText() == null ? "" : extensionField.getText().trim();
        if (item.directory() || extension.isEmpty()) {
            return base;
        }
        return base + "." + extension;
    }

    private void revalidate() {
        String name = buildName();
        RenameService.Validation syntax = RenameService.validateName(name);
        if (!syntax.ok()) {
            showError(syntax.message());
            return;
        }
        Path target;
        try {
            target = item.path().resolveSibling(name);
        } catch (RuntimeException e) {
            showError("文件名含非法字符");
            return;
        }
        RenameService.Validation conflict = RenameService.validateTarget(item.path(), target);
        if (!conflict.ok()) {
            showError(conflict.message());
            return;
        }

        setOkDisabled(false);
        RenameService.NameParts original = RenameService.split(item.name());
        String originalExtension = original.extension().isEmpty()
                ? "" : original.extension().substring(1);
        String currentExtension = extensionField.getText() == null ? "" : extensionField.getText().trim();
        if (item.directory() || originalExtension.equalsIgnoreCase(currentExtension)) {
            messageLabel.setText(name.equals(item.name()) ? "名称未改变" : " ");
            messageLabel.getStyleClass().remove("dialog-warning");
        } else {
            messageLabel.setText("注意：扩展名由 ." + originalExtension + " 改为 ." + currentExtension
                    + "，可能导致文件无法被正确打开");
            if (!messageLabel.getStyleClass().contains("dialog-warning")) {
                messageLabel.getStyleClass().add("dialog-warning");
            }
        }
    }

    private void showError(String message) {
        messageLabel.setText(message);
        if (!messageLabel.getStyleClass().contains("dialog-error")) {
            messageLabel.getStyleClass().add("dialog-error");
        }
        messageLabel.getStyleClass().remove("dialog-warning");
        setOkDisabled(true);
    }

    private void setOkDisabled(boolean disabled) {
        javafx.scene.Node okButton = getDialogPane().lookupButton(
                getDialogPane().getButtonTypes().get(0));
        if (okButton != null) {
            okButton.setDisable(disabled);
        }
    }
}
