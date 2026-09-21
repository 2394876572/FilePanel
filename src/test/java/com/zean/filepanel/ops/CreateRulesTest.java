package com.zean.filepanel.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量创建的命名规则测试。
 *
 * <p>重点压在三个最容易错的地方：<b>字母进位</b>（Z 之后该是 AA，不能变成别的）、
 * <b>后缀必须在扩展名之前</b>（放到后面文件就打不开了）、
 * 以及<b>数量大于 1 却不递增</b>这种会在执行时才炸的规则。
 */
class CreateRulesTest {

    private static CreateRules folders(String prefix, String keyword, String suffix, int count) {
        CreateRules rules = new CreateRules();
        rules.target = CreateRules.Target.FOLDER;
        rules.prefix = prefix;
        rules.keyword = keyword;
        rules.suffix = suffix;
        rules.count = count;
        rules.sequence = CreateRules.Sequence.NUMBER;
        rules.digits = 3;
        rules.start = 1;
        return rules;
    }

    @Test
    @DisplayName("名字构成：前缀 + 关键词 + 序号 + 后缀（序号固定在中段）")
    void nameIsBuiltInFixedOrder() {
        CreateRules rules = folders("灰度发布", "检测点", "_压力", 3);

        assertEquals("灰度发布检测点001_压力", rules.nameAt(0));
        assertEquals("灰度发布检测点002_压力", rules.nameAt(1));
        assertEquals("灰度发布检测点003_压力", rules.nameAt(2));
    }

    @Test
    @DisplayName("文件：后缀插在扩展名之前，扩展名必须留在最后")
    void extensionStaysLast() {
        CreateRules rules = folders("报表", "月", "_终稿", 2);
        rules.target = CreateRules.Target.FILE;
        rules.extension = "docx";

        assertEquals("报表月001_终稿.docx", rules.nameAt(0));
        assertTrue(rules.nameAt(1).endsWith(".docx"),
                "扩展名跑到后缀前面会让文件失去类型：" + rules.nameAt(1));
        // 带点的写法要能容忍（用户会顺手写 .docx）
        rules.extension = ".xlsx";
        assertEquals("报表月001_终稿.xlsx", rules.nameAt(0));
        assertEquals("xlsx", rules.extensionOrEmpty());
    }

    @Test
    @DisplayName("数字序号按位数补零，位数不够时不截断而是变长")
    void numberPadding() {
        CreateRules rules = folders("x", "", "", 12);
        rules.digits = 3;
        assertEquals("x001", rules.nameAt(0));
        assertEquals("x012", rules.nameAt(11));

        // 数量超过位数能表达的范围时，序号自然变长——绝不能被截断，否则会出现重名
        rules = folders("x", "", "", 1200);
        rules.digits = 3;
        assertEquals("x1000", rules.nameAt(999));
        assertNotEqualsNames(rules, 1200);
    }

    private static void assertNotEqualsNames(CreateRules rules, int count) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            assertTrue(seen.add(rules.nameAt(i)), "第 " + i + " 项与前一项重名：" + rules.nameAt(i));
        }
    }

    @Test
    @DisplayName("字母递增用 Excel 列名那套进位：Z 之后是 AA，AZ 之后是 BA")
    void alphaCarries() {
        assertEquals("A", CreateRules.alpha(1));
        assertEquals("Z", CreateRules.alpha(26));
        assertEquals("AA", CreateRules.alpha(27));
        assertEquals("AB", CreateRules.alpha(28));
        assertEquals("AZ", CreateRules.alpha(52));
        assertEquals("BA", CreateRules.alpha(53));
        assertEquals("ZZ", CreateRules.alpha(702));
        assertEquals("AAA", CreateRules.alpha(703));
        // 边界：0 与负数不产生序号（对应"起始值 0"这种写法）
        assertEquals("", CreateRules.alpha(0));
        assertEquals("", CreateRules.alpha(-5));
    }

    @Test
    @DisplayName("字母序号：大小写两种形式，且 30 项不会出现重名")
    void alphaSequenceInNames() {
        CreateRules upper = folders("点", "", "", 30);
        upper.sequence = CreateRules.Sequence.UPPER;
        assertEquals("点A", upper.nameAt(0));
        assertEquals("点Z", upper.nameAt(25));
        assertEquals("点AA", upper.nameAt(26), "第 27 项必须进位，而不是回到 A");
        assertNotEqualsNames(upper, 30);

        CreateRules lower = folders("点", "", "", 3);
        lower.sequence = CreateRules.Sequence.LOWER;
        assertEquals("点a", lower.nameAt(0));
        assertEquals("点c", lower.nameAt(2));
    }

    @Test
    @DisplayName("起始值可自定义（含 0），序号随起始值偏移")
    void startOffset() {
        CreateRules rules = folders("第", "章", "", 3);
        rules.start = 0;
        assertEquals("第章000", rules.nameAt(0));
        assertEquals("第章002", rules.nameAt(2));

        rules.start = 10;
        assertEquals("第章010", rules.nameAt(0));
        assertEquals("第章012", rules.nameAt(2));

        // 字母形式 + 起始 0：第 0 项没有字母，第 1 项是 A
        rules.sequence = CreateRules.Sequence.UPPER;
        rules.start = 0;
        assertEquals("第章", rules.nameAt(0));
        assertEquals("第章A", rules.nameAt(1));
        assertNotEqualsNames(rules, 30);
    }

    @Test
    @DisplayName("数量大于 1 却不递增的规则必须被拒绝（否则执行时第 2 项起必然撞名）")
    void nonSequencedMultiCountIsRejected() {
        CreateRules rules = folders("同名", "", "", 5);
        rules.sequence = CreateRules.Sequence.NONE;
        String problem = rules.validate();
        assertNotNull(problem, "该规则应当被拒绝");
        assertTrue(problem.contains("递增"), problem);

        // 只建 1 个时不递增是合理的
        rules.count = 1;
        assertNull(rules.validate(), rules.validate());
    }

    @Test
    @DisplayName("空名字与非法字符在规则阶段就被拦住，而不是建到一半才失败")
    void validateCatchesBadNames() {
        CreateRules empty = folders("", "", "", 1);
        empty.sequence = CreateRules.Sequence.NONE;
        assertNotNull(empty.validate());
        assertTrue(empty.validate().contains("不能为空"), empty.validate());

        CreateRules illegal = folders("含<非法>字符", "", "", 2);
        assertNotNull(illegal.validate());
        assertTrue(illegal.validate().contains("不可用"), illegal.validate());

        // 保留设备名
        CreateRules reserved = folders("CON", "", "", 1);
        reserved.sequence = CreateRules.Sequence.NONE;
        reserved.target = CreateRules.Target.FILE;
        assertNotNull(reserved.validate());

        // 文件必须有扩展名
        CreateRules noExt = folders("a", "", "", 2);
        noExt.target = CreateRules.Target.FILE;
        noExt.extension = "  ";
        assertNotNull(noExt.validate());
        assertTrue(noExt.validate().contains("扩展名"), noExt.validate());
    }

    @Test
    @DisplayName("数量风险按预计耗时分档：1 秒内安静、超 1 秒提示、超 5 秒需确认、超 5 万拒绝")
    void warningLevels() {
        CreateRules rules = folders("x", "", "", 10);
        assertEquals(0, rules.warningLevel());

        // 阈值是由时间反推的，所以"刚好不提示"的那一档必须仍然安静
        rules.count = CreateRules.WARN_COUNT;
        assertEquals(0, rules.warningLevel(),
                "预计耗时刚好等于提示阈值时应按安静处理（" + rules.describeEstimate() + "）");

        rules.count = CreateRules.WARN_COUNT + 100;
        assertEquals(1, rules.warningLevel(), rules.describeEstimate());
        assertTrue(rules.estimatedMillis() > CreateRules.WARN_MILLIS);

        rules.count = CreateRules.STRONG_WARN_COUNT + 100;
        assertEquals(2, rules.warningLevel(), rules.describeEstimate());
        assertTrue(rules.estimatedMillis() > CreateRules.STRONG_WARN_MILLIS);

        // 关键不变式：警告与"真的要等"必须一一对应，不能出现"警告了但其实是一瞬间"
        for (int count : new int[]{10, 100, 500, 1000, 5000, 20000}) {
            CreateRules probe = folders("x", "", "", count);
            boolean warned = probe.warningLevel() > 0;
            boolean slow = probe.estimatedMillis() > CreateRules.WARN_MILLIS;
            assertEquals(slow, warned,
                    count + " 项：警告=" + warned + " 但预计 " + probe.estimatedMillis() + " ms");
        }

        rules.count = CreateRules.MAX_COUNT + 1;
        assertEquals(3, rules.warningLevel());
        assertNotNull(rules.validate(), "超过上限时规则也应判为不可用");
        assertTrue(rules.validate().contains("最多"), rules.validate());

        // 夹取仍然会发生（防止程序化调用一次生成上亿条计划），但那是"最后一道保险"，
        // 判断超限必须发生在夹取之前——否则这个状态永远观察不到
        rules.normalize();
        assertEquals(CreateRules.MAX_COUNT, rules.count);

        CreateRules zero = folders("x", "", "", 0);
        assertEquals(0, zero.warningLevel(), "0 项不是「数量风险」，它只是一个无效输入");
        assertNotNull(zero.validate(), "0 项必须被拒绝");
        assertTrue(zero.validate().contains("至少"), zero.validate());
    }

    @Test
    @DisplayName("预计耗时随数量线性增长，并说明含重新扫描")
    void estimateMentionsRescan() {
        CreateRules rules = folders("x", "", "", 1000);
        long millis = rules.estimatedMillis();

        assertTrue(millis >= 1000 * CreateRules.MILLIS_PER_CREATE, String.valueOf(millis));
        assertTrue(rules.describeEstimate().contains("重新扫描"), rules.describeEstimate());
        // 估长不估短：说"不到 1 秒"的时候应该真的是很快
        CreateRules tiny = folders("x", "", "", 5);
        assertTrue(tiny.describeEstimate().contains("不到 1 秒"), tiny.describeEstimate());
    }

    @Test
    @DisplayName("界面枚举一律显示中文：下拉框不设转换器时看到的就是 toString，绝不能漏出 FOLDER/SKIP")
    void enumLabelsAreChinese() {
        // 这条测试来自一个真实缺陷：对话框里的三个下拉框显示成了 FOLDER / NUMBER / SKIP。
        // 原因是 ComboBox 默认显示枚举的 toString，而它等于枚举名。
        // 这类问题单测与自检都不会红——只有真的打开对话框才看得见，
        // 所以专门用一条断言把它钉住。
        java.util.List<Enum<?>> all = new java.util.ArrayList<>();
        all.addAll(java.util.List.of(CreateRules.Target.values()));
        all.addAll(java.util.List.of(CreateRules.Sequence.values()));
        all.addAll(java.util.List.of(CreateRules.Conflict.values()));

        for (Enum<?> value : all) {
            String shown = value.toString();
            assertFalse(shown.equals(value.name()),
                    "枚举 " + value.name() + " 直接显示了英文名，下拉框里就会是英文");
            assertTrue(shown.codePoints().anyMatch(c -> c > 127),
                    "枚举 " + value.name() + " 的显示文本里没有中文：" + shown);
        }

        // 具体文案也钉住，避免以后被顺手改成别的
        assertEquals("文件夹", CreateRules.Target.FOLDER.toString());
        assertEquals("文件", CreateRules.Target.FILE.toString());
        assertEquals("数字 1,2,3", CreateRules.Sequence.NUMBER.toString());
        assertEquals("已存在就跳过", CreateRules.Conflict.SKIP.toString());
        assertTrue(CreateRules.Conflict.SUFFIX.toString().contains("自动改名"),
                CreateRules.Conflict.SUFFIX.toString());
    }

    @Test
    @DisplayName("描述文本能说明建什么、多少个、撞名策略")
    void describeCoversTheEssentials() {
        CreateRules rules = folders("灰度发布", "检测点", "_压力", 12);
        String text = rules.describe();

        assertTrue(text.contains("12"), text);
        assertTrue(text.contains("文件夹"), text);
        assertTrue(text.contains("灰度发布检测点001_压力"), text);
        assertTrue(text.contains("灰度发布检测点012_压力"), text);
        assertTrue(text.contains("跳过"), "应写明撞名策略：" + text);
    }

    // ------------------------------------------------------------ 计划层

    @Test
    @DisplayName("撞名策略=跳过：已存在的项标为跳过，其余照建")
    void planSkipsExisting(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("灰度发布检测点002_压力"));
        CreateRules rules = folders("灰度发布", "检测点", "_压力", 4);

        BatchCreatePlan plan = BatchCreatePlan.build(dir, rules);

        assertEquals(4, plan.total());
        assertEquals(3, plan.toCreateCount());
        assertEquals(1, plan.skippedCount());
        assertEquals(0, plan.renamedCount());
        assertEquals(BatchCreatePlan.Status.SKIP_EXISTS,
                plan.find("灰度发布检测点002_压力").status());
        assertTrue(plan.summary().contains("跳过 1 项"), plan.summary());
    }

    @Test
    @DisplayName("撞名策略=自动改名：插在扩展名之前，且不覆盖原文件")
    void planRenamesOnConflict(@TempDir Path dir) throws IOException {
        Path existing = Files.writeString(dir.resolve("报表月001_终稿.docx"), "原有内容");
        CreateRules rules = folders("报表", "月", "_终稿", 2);
        rules.target = CreateRules.Target.FILE;
        rules.extension = "docx";
        rules.conflict = CreateRules.Conflict.SUFFIX;

        BatchCreatePlan plan = BatchCreatePlan.build(dir, rules);

        assertEquals(2, plan.toCreateCount());
        assertEquals(0, plan.skippedCount());
        assertEquals(1, plan.renamedCount());
        BatchCreatePlan.Entry renamed = plan.find("报表月001_终稿 (2).docx");
        assertNotNull(renamed, "应在扩展名之前插入 (2)：" + plan.entries());
        assertEquals(BatchCreatePlan.Status.RENAMED, renamed.status());
        assertEquals("报表月001_终稿.docx", renamed.wanted(), "要记下它本来想叫什么");
        // 绝不覆盖：原文件内容必须原样
        assertEquals("原有内容", Files.readString(existing));
    }

    @Test
    @DisplayName("自动改名会连着让位：已有 (2) 时就找 (3)")
    void suffixKeepsLooking(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "1");
        Files.writeString(dir.resolve("a (2).txt"), "2");
        Files.writeString(dir.resolve("a (3).txt"), "3");

        Path free = BatchCreatePlan.findFreeName(dir, "a.txt", CreateRules.Target.FILE);

        assertNotNull(free);
        assertEquals("a (4).txt", free.getFileName().toString());
    }

    @Test
    @DisplayName("文件夹撞名时也自动改名（且不会把点当成扩展名切开）")
    void folderRenameKeepsWholeName(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("2026.09 归档"));

        Path free = BatchCreatePlan.findFreeName(dir, "2026.09 归档", CreateRules.Target.FOLDER);

        assertNotNull(free);
        assertEquals("2026.09 归档 (2)", free.getFileName().toString(),
                "文件夹名里的点不是扩展名，不能被切开：" + free.getFileName());
    }

    @Test
    @DisplayName("预览包含第一项与最后一项——只看开头发现不了位数不够的问题")
    void previewShowsFirstAndLast(@TempDir Path dir) {
        CreateRules rules = folders("点", "", "", 120);

        BatchCreatePlan plan = BatchCreatePlan.build(dir, rules);
        String preview = plan.preview(5);

        assertTrue(preview.contains("点001"), preview);
        assertTrue(preview.contains("点120"), "预览必须带上最后一项：" + preview);
        assertTrue(preview.contains("最后一项"), preview);
    }

    @Test
    @DisplayName("空计划与 null 输入不抛异常")
    void planHandlesBadInput(@TempDir Path dir) {
        assertTrue(BatchCreatePlan.build(null, folders("x", "", "", 3)).isEmpty());
        assertTrue(BatchCreatePlan.build(dir, null).isEmpty());
        assertEquals("（没有可新建的项）", BatchCreatePlan.build(dir, null).preview(3));
    }

    @Test
    @DisplayName("名字非法的项被标为 ILLEGAL 并说明原因，不算进待建数")
    void planMarksIllegalEntries(@TempDir Path dir) {
        CreateRules rules = folders("含|竖线", "", "", 2);

        BatchCreatePlan plan = BatchCreatePlan.build(dir, rules);

        assertEquals(0, plan.toCreateCount());
        assertEquals(2, plan.skippedCount());
        assertEquals(BatchCreatePlan.Status.ILLEGAL, plan.entries().get(0).status());
        assertTrue(plan.entries().get(0).note().contains("不可用"), plan.entries().get(0).note());
    }

    @Test
    @DisplayName("执行：建文件夹与带内容的文件，内容必须是 UTF-8 写进去的")
    void executeCreatesFoldersAndFiles(@TempDir Path dir) throws IOException {
        CreateRules folderRules = folders("目录", "", "", 3);
        CreateService service = new CreateService();
        CreateService.Outcome folders = service.execute(
                BatchCreatePlan.build(dir, folderRules), folderRules, null, null);

        assertEquals(3, folders.createdCount());
        assertFalse(folders.hasFailures(), String.valueOf(folders.failures()));
        assertTrue(Files.isDirectory(dir.resolve("目录001")));

        CreateRules fileRules = folders("文档", "", "", 2);
        fileRules.target = CreateRules.Target.FILE;
        fileRules.extension = "txt";
        fileRules.initialContent = "灰度发布管理系统";
        CreateService.Outcome files = service.execute(
                BatchCreatePlan.build(dir, fileRules), fileRules, null, null);

        assertEquals(2, files.createdCount());
        assertEquals("灰度发布管理系统",
                Files.readString(dir.resolve("文档001.txt"), StandardCharsets.UTF_8));
        assertEquals("灰度发布管理系统",
                Files.readString(dir.resolve("文档002.txt"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("执行绝不覆盖：即便计划出了岔子，已存在的文件也不能被动")
    void executeNeverOverwrites(@TempDir Path dir) throws IOException {
        Path existing = Files.writeString(dir.resolve("x001.txt"), "原有内容");
        CreateRules rules = folders("x", "", "", 2);
        rules.target = CreateRules.Target.FILE;
        rules.extension = "txt";
        rules.initialContent = "新内容";
        // 手工造一个"计划里要求建一个已存在的名字"的极端情况
        BatchCreatePlan plan = BatchCreatePlan.build(dir, rules);
        CreateService.Outcome outcome = new CreateService().execute(plan, rules, null, null);

        // x001 已存在 → 计划里就是跳过，所以只会建 x002
        assertEquals(1, outcome.createdCount());
        assertEquals(1, outcome.skipped());
        assertEquals("原有内容", Files.readString(existing), "已存在的文件绝不能被覆盖");
    }

    @Test
    @DisplayName("执行把成功的项记进撤销日志，且撤销日志与重命名日志分开")
    void executeRecordsUndoJournal(@TempDir Path dir, @TempDir Path dataDir) {
        com.zean.filepanel.store.CreateJournal journal =
                new com.zean.filepanel.store.CreateJournal(dataDir);
        CreateRules rules = folders("记录", "", "", 2);

        new CreateService().execute(BatchCreatePlan.build(dir, rules), rules, journal, null);

        assertTrue(journal.last().isPresent());
        assertEquals(2, journal.last().get().size());
        assertTrue(journal.describeLast().contains("可撤销"), journal.describeLast());
        assertTrue(Files.isRegularFile(dataDir.resolve("create-journal.json")));
        assertFalse(Files.exists(dataDir.resolve("rename-journal.json")),
                "两份日志必须分开：撤销创建去删文件、撤销重命名去改名，混在一起会出大错");
    }

    @Test
    @DisplayName("单项失败不影响其余项：计划生成之后才冒出来的同名文件只让那一项失败")
    void failuresDoNotAbortTheBatch(@TempDir Path dir) throws IOException {
        CreateRules rules = folders("项", "", "", 3);
        // 先生成计划（此时三项都还不存在），再插入一个同名文件，
        // 模拟"计划与现实之间发生了别的事情"——执行时必须如实报失败，但继续建其余的
        BatchCreatePlan plan = BatchCreatePlan.build(dir, rules);
        assertEquals(3, plan.toCreateCount());
        Files.writeString(dir.resolve("项002"), "别人抢先建了");

        CreateService.Outcome outcome = new CreateService().execute(plan, rules, null, null);

        assertEquals(2, outcome.createdCount(), "另外两项必须照建");
        assertEquals(1, outcome.failures().size());
        assertTrue(outcome.failures().get(0).message().contains("已存在"),
                outcome.failures().get(0).message());
        assertTrue(Files.exists(dir.resolve("项001")));
        assertTrue(Files.exists(dir.resolve("项003")));
        // 抢先那个文件的内容不能被破坏
        assertEquals("别人抢先建了", Files.readString(dir.resolve("项002")));
    }

    @Test
    @DisplayName("进度回调覆盖每一项，且 done 从 1 递增到 total")
    void progressReportsEveryItem(@TempDir Path dir) {
        CreateRules rules = folders("进度", "", "", 5);
        java.util.List<String> reports = new java.util.ArrayList<>();

        new CreateService().execute(BatchCreatePlan.build(dir, rules), rules, null,
                (done, total, name) -> reports.add(done + "/" + total + ":" + name));

        assertEquals(List.of("1/5:进度001", "2/5:进度002", "3/5:进度003", "4/5:进度004",
                "5/5:进度005"), reports);
    }

    @Test
    @DisplayName("撤销日志只保留最近若干批，且移除后 last 会退到上一批")
    void journalKeepsRecentBatches(@TempDir Path dataDir) {
        com.zean.filepanel.store.CreateJournal journal =
                new com.zean.filepanel.store.CreateJournal(dataDir);
        String first = journal.record("第一批", List.of(Path.of("C:/a")));
        journal.record("第二批", List.of(Path.of("C:/b")));

        assertTrue(journal.last().isPresent());
        assertEquals("第二批", journal.last().get().description);

        assertTrue(journal.removeBatch(journal.last().get().id));
        assertEquals("第一批", journal.last().get().description);
        assertTrue(journal.removeBatch(first));
        assertTrue(journal.last().isEmpty());
        assertFalse(journal.removeBatch("不存在"), "移除不存在的批次应返回 false，而不是抛异常");
    }
}
