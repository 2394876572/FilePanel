package com.zean.filepanel;

import com.zean.filepanel.ui.MainApp;

/**
 * 独立启动入口。
 *
 * <p>存在这个类而不是直接把 {@link MainApp} 当主类，是为了绕开 JavaFX 的一个经典限制：
 * 当 JavaFX 从 classpath（而非 module-path）加载时，若主类本身继承 {@code javafx.application.Application}，
 * JVM 会直接报 “Error: JavaFX runtime components are missing, and are required to run this application”。
 * 用一个不继承 Application 的普通类做入口即可规避，这是 JavaFX 官方 FAQ 推荐的做法。
 *
 * <p>入口职责：
 * <ol>
 *   <li>接管命令行参数（后续 M1 起解析 {@code --root}，M0 仅识别 {@code --selftest}）</li>
 *   <li>把参数原样交给 {@link MainApp}</li>
 * </ol>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        MainApp.main(args);
    }
}
