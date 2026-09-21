package com.zean.filepanel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {

    @Test
    @DisplayName("--root 支持空格与等号两种写法")
    void parsesRootBothForms() {
        assertEquals(Path.of("C:/work"), CliOptions.parse(new String[]{"--root", "C:/work"}).root());
        assertEquals(Path.of("C:/work"), CliOptions.parse(new String[]{"--root=C:/work"}).root());
    }

    @Test
    @DisplayName("--scan 的路径可选：不写则用解析出的根目录")
    void parsesScanWithAndWithoutPath() {
        CliOptions noPath = CliOptions.parse(new String[]{"--scan"});
        assertTrue(noPath.scan());
        assertNull(noPath.scanTarget(), "未指定路径时应交给根目录解析处理");

        CliOptions withPath = CliOptions.parse(new String[]{"--scan", "C:/work"});
        assertTrue(withPath.scan());
        assertEquals(Path.of("C:/work"), withPath.scanTarget());

        CliOptions eqForm = CliOptions.parse(new String[]{"--scan=C:/work"});
        assertEquals(Path.of("C:/work"), eqForm.scanTarget());
    }

    @Test
    @DisplayName("--scan 后面紧跟另一个选项时，不能把选项当成路径")
    void scanDoesNotSwallowFollowingOption() {
        CliOptions opts = CliOptions.parse(new String[]{"--scan", "--root", "C:/work"});

        assertTrue(opts.scan());
        assertNull(opts.scanTarget(), "不能把 --root 当成扫描目标");
        assertEquals(Path.of("C:/work"), opts.root());
    }

    @Test
    @DisplayName("模式标志互相独立")
    void parsesModeFlags() {
        assertFalse(CliOptions.parse(new String[0]).isHeadlessMode(), "无参数时应打开界面");

        CliOptions selfTest = CliOptions.parse(new String[]{"--selftest"});
        assertTrue(selfTest.selfTest());
        assertFalse(selfTest.scan());
        assertTrue(selfTest.isHeadlessMode());

        assertTrue(CliOptions.parse(new String[]{"--scan"}).isHeadlessMode());
        assertTrue(CliOptions.parse(new String[]{"-h"}).help());
        assertTrue(CliOptions.parse(new String[]{"--help"}).isHeadlessMode());
    }

    @Test
    @DisplayName("--ui-selftest 可带路径，也可不带")
    void parsesUiSelfTest() {
        CliOptions bare = CliOptions.parse(new String[]{"--ui-selftest"});
        assertTrue(bare.uiSelfTest());
        assertFalse(bare.scan(), "界面自检与无界面扫描是两个独立模式");
        assertNull(bare.root());
        assertTrue(bare.isHeadlessMode());

        assertEquals(Path.of("C:/work"),
                CliOptions.parse(new String[]{"--ui-selftest", "C:/work"}).root());
        assertEquals(Path.of("C:/work"),
                CliOptions.parse(new String[]{"--ui-selftest=C:/work"}).root());

        // 不能把后面的选项当成路径
        CliOptions withFlag = CliOptions.parse(new String[]{"--ui-selftest", "--selftest"});
        assertNull(withFlag.root());
        assertTrue(withFlag.selfTest());
    }

    @Test
    @DisplayName("未知参数、null 与空串不导致失败")
    void tolerantOfJunk() {
        CliOptions opts = CliOptions.parse(new String[]{"--unknown", "", null, "--scan"});
        assertTrue(opts.scan());

        CliOptions nullArgs = CliOptions.parse(null);
        assertFalse(nullArgs.scan());
        assertNull(nullArgs.root());
    }

    @Test
    @DisplayName("--root 缺少取值时不抛异常，只是不生效")
    void rootWithoutValueIsIgnored() {
        CliOptions opts = CliOptions.parse(new String[]{"--root"});
        assertNull(opts.root());

        CliOptions emptyValue = CliOptions.parse(new String[]{"--root="});
        assertNull(emptyValue.root(), "空值应视为未指定，交由兜底逻辑");
    }

    @Test
    @DisplayName("--delete-preview 支持空格与等号两种写法，且是命令行模式")
    void parsesDeletePreview() {
        CliOptions spaced = CliOptions.parse(new String[]{"--delete-preview", "C:/out.png"});
        assertEquals(Path.of("C:/out.png"), spaced.deletePreview());
        assertTrue(spaced.isHeadlessMode(), "预览模式不能落到图形界面");

        CliOptions equals = CliOptions.parse(new String[]{"--delete-preview=C:/out.png"});
        assertEquals(Path.of("C:/out.png"), equals.deletePreview());

        // 不能把后面的选项当成输出路径
        CliOptions withFlag = CliOptions.parse(new String[]{"--delete-preview", "--selftest"});
        assertNull(withFlag.deletePreview());
        assertTrue(withFlag.selfTest());

        assertNull(CliOptions.parse(new String[]{"--delete-preview"}).deletePreview(),
                "缺取值时不生效，但也不能抛异常");
    }

    @Test
    @DisplayName("帮助文本列出了全部模式")
    void helpMentionsAllModes() {
        String help = CliOptions.helpText();
        assertTrue(help.contains("--root"));
        assertTrue(help.contains("--scan"));
        assertTrue(help.contains("--selftest"));
        assertTrue(help.contains("--delete-test"));
        assertTrue(help.contains("--content-search"));
        assertTrue(help.contains("--settings-preview"));
        assertTrue(help.contains("--delete-preview"));
        assertTrue(help.contains("--help"));
    }

    @Test
    @DisplayName("拼错的选项被记下来，而不是静默忽略后打开图形界面")
    void unknownOptionsAreReported() {
        // 实测踩过：把 --delete-test 打成 --delete-tests，参数被忽略，
        // 程序按"没有命令"直接开了界面，命令行调用方就一直等一个不会退出的进程。
        CliOptions typo = CliOptions.parse(new String[]{"--delete-tests", "C:/x"});
        assertEquals(List.of("--delete-tests"), typo.unknownOptions());
        assertTrue(typo.isHeadlessMode(), "有未知选项时必须走命令行分支，不能落到图形界面");

        CliOptions unknownEqualsForm = CliOptions.parse(new String[]{"--threshold=100"});
        assertEquals(List.of("--threshold=100"), unknownEqualsForm.unknownOptions());

        // 认识的选项不能被误报
        CliOptions known = CliOptions.parse(new String[]{
                "--root", "C:/work", "--scan", "--delete-test", "C:/f.bin", "--content-search", "x"});
        assertTrue(known.unknownOptions().isEmpty(), String.valueOf(known.unknownOptions()));

        // 单个连字符（例如 -h）与普通路径参数都不算未知选项
        assertTrue(CliOptions.parse(new String[]{"-h"}).unknownOptions().isEmpty());
        assertTrue(CliOptions.parse(new String[]{"C:/some/folder"}).unknownOptions().isEmpty());
    }
}
