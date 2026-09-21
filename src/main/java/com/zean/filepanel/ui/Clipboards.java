package com.zean.filepanel.ui;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.util.List;

/** 剪贴板相关的小工具。放在 ui 层是因为剪贴板属于界面设施，不属于文件操作。 */
final class Clipboards {

    private Clipboards() {
    }

    /** 复制纯文本到系统剪贴板。 */
    static void copyText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** 把多行文本按行拼起来复制（用于批量复制路径）。 */
    static void copyLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        copyText(String.join(System.lineSeparator(), lines));
    }
}
