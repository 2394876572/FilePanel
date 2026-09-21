package com.zean.filepanel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排除规则测试。
 *
 * <p>这是本产品最容易“静默出错”的地方：规则写错不会抛异常，只会让用户的文件莫名消失，
 * 因此对大小写、通配符、目录/文件区分都必须有显式断言。
 */
class ExclusionsTest {

    @Test
    @DisplayName("默认规则排除常见依赖与构建目录，且忽略大小写")
    void excludesDefaultDirectoriesIgnoringCase() {
        Exclusions ex = Exclusions.defaults();

        assertTrue(ex.excludesDirectory(Path.of("C:/work/.venv")), ".venv 应被排除");
        assertTrue(ex.excludesDirectory(Path.of("C:/work/.VENV")), "大小写不同也应排除");
        assertTrue(ex.excludesDirectory(Path.of("C:/work/Node_Modules")), "大小写不同也应排除");
        assertTrue(ex.excludesDirectory(Path.of("C:/work/target")), "target 应被排除");
        assertTrue(ex.excludesDirectory(Path.of("C:/work/__pycache__")), "__pycache__ 应被排除");
        assertTrue(ex.excludesDirectory(Path.of("C:/work/FilePanel-Src")), "本工具源码目录应被排除");
        assertTrue(ex.excludesDirectory(Path.of("C:/work/.filepanel")), "本工具数据目录应被排除");
    }

    @Test
    @DisplayName("普通目录不被排除")
    void keepsOrdinaryDirectories() {
        Exclusions ex = Exclusions.defaults();

        assertFalse(ex.excludesDirectory(Path.of("C:/work/灰度发布管理平台")));
        assertFalse(ex.excludesDirectory(Path.of("C:/work/图片")));
        assertFalse(ex.excludesDirectory(Path.of("C:/work/某项目")));
        assertFalse(ex.excludesDirectory(Path.of("C:/work/doc")));
    }

    @Test
    @DisplayName("Office 临时文件与常见临时扩展名被排除")
    void excludesTempFiles() {
        Exclusions ex = Exclusions.defaults();

        assertTrue(ex.excludesFile(Path.of("C:/work/~$任务功能梳理.docx")), "Office 锁文件应被排除");
        assertTrue(ex.excludesFile(Path.of("C:/work/a.tmp")));
        assertTrue(ex.excludesFile(Path.of("C:/work/A.TMP")), "扩展名大小写不同也应排除");
        assertTrue(ex.excludesFile(Path.of("C:/work/module.pyc")));
        assertTrue(ex.excludesFile(Path.of("C:/work/Thumbs.db")));
        assertTrue(ex.excludesFile(Path.of("C:/work/desktop.ini")));
    }

    @Test
    @DisplayName("正常文档不被误伤")
    void keepsRealDocuments() {
        Exclusions ex = Exclusions.defaults();

        assertFalse(ex.excludesFile(Path.of("C:/work/会议记录_0718.docx")));
        assertFalse(ex.excludesFile(Path.of("C:/work/项目发布记录.xlsx")));
        assertFalse(ex.excludesFile(Path.of("C:/work/作业区分布图1.png")));
        assertFalse(ex.excludesFile(Path.of("C:/work/交接文档.txt")));
    }

    @Test
    @DisplayName("通配符规则只匹配模式，不做子串匹配")
    void wildcardMatchesWholeName() {
        // ~$* 只应命中以 ~$ 开头的名字，不能命中中间含 ~$ 的名字
        assertTrue(Exclusions.defaults().excludesFile(Path.of("C:/w/~$x.docx")));
        assertFalse(Exclusions.defaults().excludesFile(Path.of("C:/w/a~$b.docx")));
    }

    @Test
    @DisplayName("关闭开关后不排除任何内容，但规则本身保留")
    void disabledExcludesNothing() {
        Exclusions off = Exclusions.defaults().withEnabled(false);

        assertFalse(off.excludesDirectory(Path.of("C:/work/.venv")));
        assertFalse(off.excludesFile(Path.of("C:/work/~$x.docx")));
        assertFalse(off.isEnabled());
        // 关掉之后仍能再打开，说明规则没有被清空
        assertTrue(off.withEnabled(true).excludesDirectory(Path.of("C:/work/.venv")));
        assertTrue(off.dirRuleCount() > 0, "规则数量应保留");
    }

    @Test
    @DisplayName("空规则集不排除任何内容")
    void noneExcludesNothing() {
        Exclusions none = Exclusions.none();

        assertFalse(none.excludesDirectory(Path.of("C:/work/.venv")));
        assertFalse(none.excludesFile(Path.of("C:/work/Thumbs.db")));
        assertFalse(none.isEnabled());
    }

    @Test
    @DisplayName("glob 转正则：* 与 ? 均可用，且特殊字符按字面量处理")
    void globTranslation() {
        assertTrue(Glob.matches("*.tmp", "a.tmp"));
        assertTrue(Glob.matches("*.tmp", "A.TMP"));
        assertFalse(Glob.matches("*.tmp", "a.tmpx"));
        assertTrue(Glob.matches("?.log", "a.log"));
        assertFalse(Glob.matches("?.log", "ab.log"));
        // 正则元字符必须当字面量：~$ 中的 $ 不能被当成行尾
        assertTrue(Glob.matches("~$*", "~$x"));
        // 通配符只做整体匹配，不做子串匹配
        assertFalse(Glob.matches("~$*", "a~$b"));
    }

    @Test
    @DisplayName("通配符缓存不会因为反复编译而返回错误结果")
    void globCacheIsCorrect() {
        for (int i = 0; i < 2000; i++) {
            assertTrue(Glob.matches("abc*", "abcdef"), "第 " + i + " 次应仍然匹配");
        }
        assertFalse(Glob.matches("abc*", "xabc"));
    }

    @Test
    @DisplayName("null 与无文件名路径不抛异常")
    void handlesNullSafely() {
        assertFalse(Exclusions.defaults().excludesDirectory(null));
        assertFalse(Exclusions.defaults().excludesFile(null));
    }

    @Test
    @DisplayName("规则访问器返回的是「本实例」的规则，而不是默认常量")
    void accessorsDescribeThisInstance() {
        Exclusions defaults = Exclusions.defaults();
        assertFalse(defaults.compiledFilePatterns().isEmpty());
        assertFalse(defaults.compiledDirPatterns().isEmpty());
        // 文件规则只有通配符这一种形式，所以计数与访问器必须一一对应
        assertEquals(defaults.fileRuleCount(), defaults.compiledFilePatterns().size(),
                "访问器给出的文件规则条数必须与 describe() 用的计数一致");
        // 目录规则 = 精确名 + 通配符两种，dirRuleCount() 是两者之和（34），
        // 而 compiledDirPatterns() 只含通配符那部分（3）——这里断言的是"它确实只是其中一部分"，
        // 免得以后有人误以为两者应当相等（本测试第一版就是这么写错的）
        assertTrue(defaults.compiledDirPatterns().size() < defaults.dirRuleCount(),
                "目录通配符只是目录规则的一部分");
        assertEquals(defaults.dirNames().size() + defaults.compiledDirPatterns().size(),
                defaults.dirRuleCount(), "精确名 + 通配符 应当等于 dirRuleCount()");

        // 这条是修复前会失败的那个用例：旧实现返回静态常量，
        // 于是"没有任何规则"的实例也会报出 17 条文件规则——访问器在说谎。
        Exclusions none = Exclusions.none();
        assertTrue(none.compiledFilePatterns().isEmpty(),
                "none() 实例不该有任何文件规则，实际 " + none.compiledFilePatterns().size() + " 条");
        assertTrue(none.compiledDirPatterns().isEmpty());
    }

    // --------------------------------------------------------- 程序自身目录

    /** 造一个平铺部署的目录骨架：FilePanel.exe + app\ + runtime\。 */
    private static Path fakeDeployment(Path dir) throws java.io.IOException {
        java.nio.file.Files.createDirectories(dir.resolve("app"));
        java.nio.file.Files.createDirectories(dir.resolve("runtime"));
        java.nio.file.Files.writeString(dir.resolve("FilePanel.exe"), "MZ");
        return dir;
    }

    @Test
    @DisplayName("平铺部署目录下，程序自己的 app\\ 与 runtime\\ 按精确路径排除（开发期与打包后必须一致）")
    void excludesOwnFoldersInDeployment(@TempDir Path dir) throws java.io.IOException {
        Path deploy = fakeDeployment(dir);

        Exclusions ex = Exclusions.forRoot(deploy);

        assertTrue(ex.excludesDirectory(deploy.resolve("app")), "app\\ 是程序自己的依赖目录");
        assertTrue(ex.excludesDirectory(deploy.resolve("runtime")), "runtime\\ 是程序自带的运行时");
        assertTrue(ex.excludesFile(deploy.resolve("FilePanel.ico")), "图标是程序家具");
        // exe 本身必须留着：那是用户要双击的入口
        assertFalse(ex.excludesFile(deploy.resolve("FilePanel.exe")));
        // 用户自己的同名目录不能被误伤——这是当初选择"精确路径"而不是"按名字"的理由
        assertFalse(ex.excludesDirectory(dir.resolve("other").resolve("app")));
        assertFalse(ex.excludesDirectory(dir.resolve("other").resolve("runtime")));
    }

    @Test
    @DisplayName("三个标记缺任意一个都不做自身排除，避免误藏用户同名目录")
    void doesNotGuessSelfPathsFromPartialLayout(@TempDir Path dir) throws java.io.IOException {
        // 只有 app\ 与 runtime\，没有 exe：这是用户自己的目录结构，不能动
        java.nio.file.Files.createDirectories(dir.resolve("app"));
        java.nio.file.Files.createDirectories(dir.resolve("runtime"));

        Exclusions ex = Exclusions.forRoot(dir);

        assertFalse(ex.excludesDirectory(dir.resolve("app")), "缺少 FilePanel.exe 时不应认定为本程序");
        assertFalse(ex.excludesDirectory(dir.resolve("runtime")));
        assertTrue(ex.selfPaths().isEmpty(), "不应凭空生出精确路径规则");
    }

    @Test
    @DisplayName("forRoot 对 null 与不存在的路径安全降级为默认规则")
    void forRootIsSafeOnBadInput() {
        assertTrue(Exclusions.forRoot(null).excludesDirectory(Path.of("C:/w/.venv")));
        assertTrue(Exclusions.forRoot(Path.of("C:/definitely/not/here"))
                .excludesDirectory(Path.of("C:/w/node_modules")));
    }
}
