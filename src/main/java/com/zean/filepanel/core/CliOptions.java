package com.zean.filepanel.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行参数。
 *
 * <p>支持四种运行模式，其中后三种是<b>为验证而存在</b>的：
 * <ul>
 *   <li>默认：打开图形界面</li>
 *   <li>{@code --selftest}：无人值守启动自检（M6 用来证明免装 Java 分发成立）</li>
 *   <li>{@code --scan [路径]}：无界面扫描并把统计打到标准输出，让扫描结果可被脚本断言，
 *       而不是只能靠肉眼看界面</li>
 *   <li>{@code --ui-selftest [路径]}：构建真实窗口对象并跑完整条链路（扫描 → 列表 → 状态栏），
 *       然后输出各环节的实际数字。它验证的是“表格真的出表了”，而不仅仅是“扫描器算对了”</li>
 *   <li>{@code --screenshot <文件>}：显示窗口、等扫描完成后把界面渲染成 PNG 并退出。
 *       单元测试无法发现 CSS 或布局损坏，这个模式让“界面长什么样”也能被自动留证与回归比对</li>
 * </ul>
 *
 * @param root       显式指定的扫描根目录（{@code --root}）
 * @param selfTest   是否运行启动自检
 * @param scan       是否运行无界面扫描
 * @param scanTarget 无界面扫描的目标目录；为 null 时使用解析出的根目录
 * @param uiSelfTest 是否运行界面链路自检
 * @param screenshot 截图输出文件；为 null 表示不截图
 * @param search     截图前要应用的搜索词，便于留下带搜索状态的界面截图
 * @param contentSearch 全文内容搜索的关键词；非 null 时无界面建立索引并输出命中
 * @param deleteTest  按当前生效的删除策略真的删掉这个文件，并报告走了哪条分支
 * @param settingsPreview 只渲染设置面板并写成 PNG；为 null 表示不渲染
 * @param createPreview 只渲染批量创建面板并写成 PNG；为 null 表示不渲染
 * @param deletePreview 只渲染删除确认框并写成 PNG；为 null 表示不渲染
 * @param iconSheet 把系统真实图标渲染成图集 PNG（M8-b PoC 证据）；为 null 表示不渲染
 * @param unknownOptions 无法识别的 {@code --xxx} 参数，供调用方报错而不是默默打开界面
 * @param help       是否打印帮助
 */
public record CliOptions(Path root, boolean selfTest, boolean scan, Path scanTarget,
                         boolean uiSelfTest, Path screenshot, String search, String theme,
                         Path recycleTest, String contentSearch, Path deleteTest,
                         Path settingsPreview, Path createPreview, Path deletePreview,
                         Path iconSheet, List<String> unknownOptions, boolean help) {

    private static final String ROOT = "--root";
    private static final String SCAN = "--scan";
    private static final String SELFTEST = "--selftest";
    private static final String UI_SELFTEST = "--ui-selftest";
    private static final String SCREENSHOT = "--screenshot";
    private static final String SEARCH = "--search";
    private static final String THEME = "--theme";
    private static final String RECYCLE_TEST = "--recycle-test";
    private static final String CONTENT_SEARCH = "--content-search";
    private static final String DELETE_TEST = "--delete-test";
    private static final String SETTINGS_PREVIEW = "--settings-preview";
    private static final String CREATE_PREVIEW = "--create-preview";
    private static final String DELETE_PREVIEW = "--delete-preview";
    private static final String ICON_SHEET = "--icon-sheet";

    public static CliOptions parse(String[] args) {
        Path root = null;
        Path scanTarget = null;
        Path screenshot = null;
        String search = null;
        String theme = null;
        Path recycleTest = null;
        String contentSearch = null;
        Path deleteTest = null;
        Path settingsPreview = null;
        Path createPreview = null;
        Path deletePreview = null;
        Path iconSheet = null;
        boolean selfTest = false;
        boolean scan = false;
        boolean uiSelfTest = false;
        boolean help = false;
        List<String> unknownOptions = new ArrayList<>();

        // 注意：不能用 List.of(args)——只要数组里有一个 null 元素它就直接抛 NPE。
        // 参数解析属于启动路径，任何输入都必须能活下来。
        List<String> list = new ArrayList<>();
        if (args != null) {
            for (String a : args) {
                if (a != null) {
                    list.add(a);
                }
            }
        }
        for (int i = 0; i < list.size(); i++) {
            String arg = list.get(i);
            if (arg.isBlank()) {
                continue;
            }
            if (SELFTEST.equals(arg)) {
                selfTest = true;
            } else if ("--help".equals(arg) || "-h".equals(arg) || "/?".equals(arg)) {
                help = true;
            } else if (SEARCH.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    search = list.get(++i);
                }
            } else if (arg.startsWith(SEARCH + "=")) {
                search = arg.substring(SEARCH.length() + 1);
            } else if (RECYCLE_TEST.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    recycleTest = toPath(list.get(++i));
                }
            } else if (arg.startsWith(RECYCLE_TEST + "=")) {
                recycleTest = toPath(arg.substring(RECYCLE_TEST.length() + 1));
            } else if (CONTENT_SEARCH.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    contentSearch = list.get(++i);
                }
            } else if (arg.startsWith(CONTENT_SEARCH + "=")) {
                contentSearch = arg.substring(CONTENT_SEARCH.length() + 1);
            } else if (DELETE_TEST.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    deleteTest = toPath(list.get(++i));
                }
            } else if (arg.startsWith(DELETE_TEST + "=")) {
                deleteTest = toPath(arg.substring(DELETE_TEST.length() + 1));
            } else if (SETTINGS_PREVIEW.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    settingsPreview = toPath(list.get(++i));
                }
            } else if (arg.startsWith(SETTINGS_PREVIEW + "=")) {
                settingsPreview = toPath(arg.substring(SETTINGS_PREVIEW.length() + 1));
            } else if (CREATE_PREVIEW.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    createPreview = toPath(list.get(++i));
                }
            } else if (arg.startsWith(CREATE_PREVIEW + "=")) {
                createPreview = toPath(arg.substring(CREATE_PREVIEW.length() + 1));
            } else if (DELETE_PREVIEW.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    deletePreview = toPath(list.get(++i));
                }
            } else if (arg.startsWith(DELETE_PREVIEW + "=")) {
                deletePreview = toPath(arg.substring(DELETE_PREVIEW.length() + 1));
            } else if (ICON_SHEET.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    iconSheet = toPath(list.get(++i));
                }
            } else if (arg.startsWith(ICON_SHEET + "=")) {
                iconSheet = toPath(arg.substring(ICON_SHEET.length() + 1));
            } else if (THEME.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    theme = list.get(++i);
                }
            } else if (arg.startsWith(THEME + "=")) {
                theme = arg.substring(THEME.length() + 1);
            } else if (SCREENSHOT.equals(arg)) {
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    screenshot = toPath(list.get(++i));
                }
            } else if (arg.startsWith(SCREENSHOT + "=")) {
                screenshot = toPath(arg.substring(SCREENSHOT.length() + 1));
            } else if (UI_SELFTEST.equals(arg)) {
                uiSelfTest = true;
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    root = toPath(list.get(++i));
                }
            } else if (arg.startsWith(UI_SELFTEST + "=")) {
                uiSelfTest = true;
                root = toPath(arg.substring(UI_SELFTEST.length() + 1));
            } else if (SCAN.equals(arg)) {
                scan = true;
                // 路径可选：下一个参数若不像选项则视为目标目录
                if (i + 1 < list.size() && !isOption(list.get(i + 1))) {
                    scanTarget = toPath(list.get(++i));
                }
            } else if (arg.startsWith(SCAN + "=")) {
                scan = true;
                scanTarget = toPath(arg.substring(SCAN.length() + 1));
            } else if (ROOT.equals(arg)) {
                if (i + 1 < list.size()) {
                    root = toPath(list.get(++i));
                }
            } else if (arg.startsWith(ROOT + "=")) {
                root = toPath(arg.substring(ROOT.length() + 1));
            } else if (isOption(arg)) {
                // 刻意记下来而不是忽略：拼错的选项（例如 --delet-test）如果被静默忽略，
                // 程序会当成"没有任何命令"直接打开图形界面，命令行调用方就永远等不到退出。
                // 这个坑是实测踩出来的：一次 --delete-test 打错字，进程挂着不返回。
                unknownOptions.add(arg);
            }
        }
        return new CliOptions(root, selfTest, scan, scanTarget, uiSelfTest, screenshot,
                search, theme, recycleTest, contentSearch, deleteTest, settingsPreview,
                createPreview, deletePreview, iconSheet, List.copyOf(unknownOptions), help);
    }

    private static boolean isOption(String s) {
        return s != null && s.startsWith("--");
    }

    /** 空串与非法路径一律返回 null，交给调用方走兜底逻辑，而不是抛异常打断启动。 */
    private static Path toPath(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Path.of(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 是否需要在打开界面前先处理命令（即不进入交互式 GUI）。 */
    public boolean isHeadlessMode() {
        return selfTest || scan || uiSelfTest || screenshot != null || recycleTest != null
                || contentSearch != null || deleteTest != null || settingsPreview != null
                || createPreview != null || deletePreview != null || iconSheet != null
                || !unknownOptions.isEmpty() || help;
    }

    public static String helpText() {
        return """
                文件面板 FilePanel

                用法：
                  FilePanel                          打开图形界面
                  FilePanel --root <目录>             指定要管理的文件夹
                  FilePanel --scan [目录]             无界面扫描并输出统计（用于验证）
                  FilePanel --ui-selftest [目录]      界面链路自检（用于验证）
                  FilePanel --screenshot <文件>       把界面渲染成 PNG（用于验证）
                  FilePanel --search <关键词>         配合截图：截图前先应用一次搜索
                  FilePanel --recycle-test <文件>     验证删除是否真的进了回收站
                  FilePanel --content-search <关键词>  无界面全文搜索：建索引并输出命中与摘要
                  FilePanel --delete-test <文件>      按当前生效的删除策略真的删掉它，并报告走了哪条分支
                  FilePanel --settings-preview <文件> 只把设置面板渲染成 PNG（用于验证布局）
                  FilePanel --create-preview <文件>   只把批量创建面板渲染成 PNG（用于验证布局）
                  FilePanel --delete-preview <文件>   只把删除确认框渲染成 PNG（用于验证布局）
                  FilePanel --icon-sheet <文件>       把系统真实图标打成图集 PNG（M8 PoC）
                  FilePanel --selftest               运行环境自检
                  FilePanel --help                   显示本帮助

                软件放进哪个文件夹就管理哪个文件夹：
                  把本程序所在目录作为默认扫描根目录；
                  也可把文件夹拖到程序图标上，或使用 --root 指定。
                """;
    }
}
