package com.zean.filepanel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootResolverTest {

    @Test
    @DisplayName("命令行指定的目录优先")
    void explicitRootWins(@TempDir Path explicit, @TempDir Path remembered) {
        RootResolver.Resolved resolved = RootResolver.resolve(explicit, remembered);

        assertEquals(explicit.toAbsolutePath().normalize(), resolved.root());
        assertTrue(resolved.reason().contains("--root"), "原因应说明来自命令行");
    }

    @Test
    @DisplayName("命令行路径无效时继续往下找，绝不返回不可用的目录")
    void invalidExplicitFallsThrough(@TempDir Path valid, @TempDir Path base) {
        Path missing = base.resolve("no-such-dir");

        RootResolver.Resolved resolved = RootResolver.resolve(missing, valid);

        assertEquals(valid.toAbsolutePath().normalize(), resolved.root(), "应退到记忆值");
        assertNotEquals(missing.toAbsolutePath().normalize(), resolved.root());
    }

    @Test
    @DisplayName("记忆值也无效时仍能给出一个可读的目录（兜底不为空）")
    void alwaysFallsBackToUsableDirectory(@TempDir Path base) {
        Path missingA = base.resolve("a");
        Path missingB = base.resolve("b");

        RootResolver.Resolved resolved = RootResolver.resolve(missingA, missingB);

        assertNotNull(resolved.root());
        assertTrue(Files.isDirectory(resolved.root()), "兜底结果必须是真实存在的目录");
        assertTrue(Files.isReadable(resolved.root()));
        assertFalse(resolved.reason().isBlank(), "必须说明为什么选了这个目录");
    }

    @Test
    @DisplayName("用文件（而非目录）指定根目录会被拒绝")
    void fileIsNotAcceptedAsRoot(@TempDir Path base) throws Exception {
        Path file = base.resolve("a.txt");
        Files.writeString(file, "x");

        RootResolver.Resolved resolved = RootResolver.resolve(file, null);

        assertNotEquals(file.toAbsolutePath().normalize(), resolved.root(),
                "文件不能作为扫描根目录");
    }

    @Test
    @DisplayName("识别 Maven 开发期产物目录（target/classes 与 target/test-classes）")
    void detectsMavenDevOutput() {
        assertTrue(RootResolver.isMavenDevOutput(Path.of("C:/p/target/classes")));
        assertTrue(RootResolver.isMavenDevOutput(Path.of("C:/p/target/test-classes")));
        assertFalse(RootResolver.isMavenDevOutput(Path.of("C:/p/classes")),
                "不在 target 下的 classes 目录不应被当作开发产物");
        assertFalse(RootResolver.isMavenDevOutput(Path.of("C:/p/target")));
    }

    @Test
    @DisplayName("只有安装版在既没有 --root 也没记住目录时，才需要问用户选哪个文件夹")
    void folderChoiceIsOnlyNeededForInstalledFirstRun() {
        // 绿色版：根目录由启动器位置推出，永远不需要问
        assertFalse(RootResolver.requiresFolderChoice(false, false, false), "绿色版不该弹选择框");
        assertFalse(RootResolver.requiresFolderChoice(true, false, false));

        // 安装版：有 --root 或记住过目录都不问
        assertFalse(RootResolver.requiresFolderChoice(true, false, true), "命令行给了就照用");
        assertFalse(RootResolver.requiresFolderChoice(false, true, true), "记住过就不该再问一遍");

        // 安装版首次启动：必须问，否则它会去管自己的安装目录
        assertTrue(RootResolver.requiresFolderChoice(false, false, true),
                "安装版首次启动必须让用户选文件夹");
    }

    @Test
    @DisplayName("安装模式标记默认关闭，显式设为 true 才生效（绿色版绝不能被误判）")
    void installedModeIsOptIn() {
        String previous = System.getProperty(RootResolver.INSTALLED_PROPERTY);
        try {
            System.clearProperty(RootResolver.INSTALLED_PROPERTY);
            assertFalse(RootResolver.isInstalledMode(), "没有标记时必须按绿色版处理");

            System.setProperty(RootResolver.INSTALLED_PROPERTY, "true");
            assertTrue(RootResolver.isInstalledMode());

            System.setProperty(RootResolver.INSTALLED_PROPERTY, "false");
            assertFalse(RootResolver.isInstalledMode(), "显式 false 也按绿色版处理");
        } finally {
            if (previous == null) {
                System.clearProperty(RootResolver.INSTALLED_PROPERTY);
            } else {
                System.setProperty(RootResolver.INSTALLED_PROPERTY, previous);
            }
        }
    }
}
