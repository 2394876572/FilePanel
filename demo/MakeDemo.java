import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 生成《文件面板》截图用的示例文件夹。
 *
 * <p><b>这里全部是为演示虚构的内容，不含任何真实资料</b>——存在的理由就是让截图
 * （进而让 README 与 docs\screenshots）里出现的每一个文件名、标签、路径都是干净的。
 * 真实的工作资料一旦被截进图里，撤回来比一开始就别放进去贵得多。
 *
 * <p>用法（JDK 17 支持单文件源码直接运行，不需要先编译）：
 * <pre>
 *   scripts\demo-screenshots.cmd          :: 生成示例数据 + 重拍全部截图
 *   C:\jdk17\bin\java demo\MakeDemo.java demo\示例文件夹   :: 只生成示例数据
 * </pre>
 *
 * <p>除了文件本身，还会预置 {@code .filepanel\} 下的 {@code favorites.json} /
 * {@code tags.json} / {@code recent.json}：否则「收藏 / 标签 / 最近使用」这三块
 * 在截图里永远是空的，而那正是 M4 要展示的东西。预置的是本文件的 JSON 格式，
 * 不是"造一份假数据给界面看"——三个 store 读的就是这几个文件。
 */
public class MakeDemo {

    /** 埋在正文里的关键词，用来演示 content: 全文搜索。 */
    static final String THEME_WORD = "灰度发布";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法：java demo\\MakeDemo.java <示例文件夹路径>");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(root)) {
            deleteTree(root);
        }
        Files.createDirectories(root);

        text(root.resolve("README.md"), """
                # 示例文件夹

                这里全部是**为演示生成的虚构内容**，不含任何真实资料。

                用途：给《文件面板》的截图提供一份干净的样本数据。
                正文里特意埋了「%s」这个词，用于演示按内容搜索。
                """.formatted(THEME_WORD));
        text(root.resolve("发布流程说明.md"), """
                # 发布流程

                1. 提交变更，跑通流水线
                2. 先在预发环境验证
                3. %s：先放 5%% 流量，观察 24 小时
                4. 无异常再全量
                """.formatted(THEME_WORD));
        bigImage(root.resolve("全景示意图.png"), 2400, 1500);

        Path product = Files.createDirectories(root.resolve("产品资料"));
        docx(product.resolve("产品手册V2.docx"), "产品手册 第2版", "第一章 概述", "第二章 安装与部署", "第三章 常见问题");
        docx(product.resolve("需求说明.docx"), "需求说明", "本文描述本轮迭代的目标", "其中包含" + THEME_WORD + "相关的改动");
        text(product.resolve("版本记录.txt"), "v1.0 首次发布\nv1.1 修复若干问题\nv2.0 " + THEME_WORD + "\n");

        Path meeting = Files.createDirectories(root.resolve("会议记录"));
        docx(meeting.resolve("周会纪要_0321.docx"), "周会纪要 03-21", "议题一 进度同步", "议题二 " + THEME_WORD + " 方案确认");
        text(meeting.resolve("评审记录.txt"), "评审时间 03-22\n结论 通过，按" + THEME_WORD + "执行\n");

        Path design = Files.createDirectories(root.resolve("设计"));
        image(design.resolve("架构图.png"), 640, 400, new Color(0x2E7D32), new Color(0xC8E6C9));
        image(design.resolve("流程图.png"), 480, 320, new Color(0x1565C0), new Color(0xBBDEFB));
        image(design.resolve("界面草图.png"), 520, 360, new Color(0x6A1B9A), new Color(0xE1BEE7));

        Path data = Files.createDirectories(root.resolve("数据"));
        xlsx(data.resolve("销售数据_2026Q1.xlsx"), List.of("季度", "区域", "金额(万元)"),
                List.of("2026Q1", "华东", "1280"), List.of("2026Q1", "华南", "960"));
        xlsx(data.resolve("库存清单.xlsx"), List.of("物料", "数量"),
                List.of("A-100", "320"), List.of("B-200", "150"));
        text(data.resolve("导出记录.csv"), "日期,数量,备注\n2026-03-01,120," + THEME_WORD + "\n");

        Path code = Files.createDirectories(root.resolve("代码"));
        text(code.resolve("build.py"), "def main():\n    print('构建完成')\n\nif __name__ == '__main__':\n    main()\n");
        text(code.resolve("config.yaml"), "env: staging\nrollout: " + THEME_WORD + "\npercent: 5\n");
        text(code.resolve("query.sql"), "SELECT area, SUM(amount) FROM sales\nWHERE quarter = '2026Q1'\nGROUP BY area;\n");
        text(code.resolve("app.js"), "export function rollout(percent) {\n  return `" + THEME_WORD + ": ${percent}%`;\n}\n");

        Path misc = Files.createDirectories(root.resolve("其他"));
        pptx(misc.resolve("产品介绍.pptx"), "产品介绍", THEME_WORD + "能力说明", "下一步计划");
        zip(misc.resolve("归档_2025.zip"));
        binary(misc.resolve("安装包.exe"), 384 * 1024);
        text(misc.resolve("联系方式.txt"), "support@example.com\n");

        seedState(root);

        try (var walk = Files.walk(root)) {
            System.out.println("示例文件数：" + walk.filter(Files::isRegularFile).count());
        }
        System.out.println("示例文件夹：" + root);
    }

    // ------------------------------------------------- 预置收藏 / 标签 / 最近使用

    /**
     * 写 .filepanel 下的三份用户数据。
     *
     * <p>时间戳刻意做成"刚刚 / 半小时前 / 几小时前 / 一天前"的梯度，
     * 这样截图里的「最近使用」卡片能体现相对时间的显示效果。
     */
    static void seedState(Path root) throws IOException {
        Path dataDir = Files.createDirectories(root.resolve(".filepanel"));

        record Pick(String relative, String op, long minutesAgo) {
        }
        List<Pick> recents = List.of(
                new Pick("产品资料\\产品手册V2.docx", "OPEN", 3),
                new Pick("设计\\架构图.png", "COPY_PATH", 31),
                new Pick("数据\\销售数据_2026Q1.xlsx", "RENAME", 62),
                new Pick("会议记录\\周会纪要_0321.docx", "REVEAL", 128),
                new Pick("其他\\产品介绍.pptx", "FAVORITE", 24 * 60 + 20));

        StringBuilder recent = new StringBuilder("[\n");
        long now = System.currentTimeMillis();
        long seq = 1;
        for (int i = 0; i < recents.size(); i++) {
            Pick pick = recents.get(i);
            Path file = root.resolve(pick.relative());
            recent.append("  {\n")
                    .append("    \"path\" : \"").append(json(file)).append("\",\n")
                    .append("    \"name\" : \"").append(json(file.getFileName().toString())).append("\",\n")
                    .append("    \"op\" : \"").append(pick.op()).append("\",\n")
                    .append("    \"timestamp\" : ").append(now - pick.minutesAgo() * 60_000L).append(",\n")
                    .append("    \"seq\" : ").append(seq++).append(",\n")
                    .append("    \"count\" : 1,\n")
                    .append("    \"directory\" : false,\n")
                    .append("    \"size\" : ").append(Files.size(file)).append("\n")
                    .append(i == recents.size() - 1 ? "  }\n" : "  }, \n");
        }
        recent.append("]");
        text(dataDir.resolve("recent.json"), recent.toString());

        List<String> favorites = List.of("产品资料\\产品手册V2.docx", "设计\\架构图.png");
        StringBuilder fav = new StringBuilder("[\n");
        for (int i = 0; i < favorites.size(); i++) {
            Path file = root.resolve(favorites.get(i));
            fav.append("  {\n")
                    .append("    \"path\" : \"").append(json(file)).append("\",\n")
                    .append("    \"timestamp\" : ").append(now - (i + 1) * 3_600_000L).append("\n")
                    .append(i == favorites.size() - 1 ? "  }\n" : "  }, \n");
        }
        fav.append("]");
        text(dataDir.resolve("favorites.json"), fav.toString());

        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("发布流程说明.md", List.of("重要", "待评审"));
        tags.put("产品资料\\产品手册V2.docx", List.of("重要", "产品资料"));
        tags.put("数据\\销售数据_2026Q1.xlsx", List.of("数据"));
        StringBuilder tag = new StringBuilder("[\n");
        int index = 0;
        for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
            Path file = root.resolve(entry.getKey());
            List<String> quoted = new ArrayList<>();
            for (String one : entry.getValue()) {
                quoted.add("\"" + json(one) + "\"");
            }
            tag.append("  {\n")
                    .append("    \"path\" : \"").append(json(file)).append("\",\n")
                    .append("    \"tags\" : [ ").append(String.join(", ", quoted)).append(" ]\n")
                    .append(index == tags.size() - 1 ? "  }\n" : "  }, \n");
            index++;
        }
        tag.append("]");
        text(dataDir.resolve("tags.json"), tag.toString());
    }

    static String json(Path path) {
        return json(path.toString());
    }

    /** 只处理反斜杠：示例数据里没有引号与控制字符。 */
    static String json(String raw) {
        return raw.replace("\\", "\\\\");
    }

    // ------------------------------------------------------------------ 造文件

    static void text(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    static void image(Path path, int w, int h, Color base, Color light) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, light, w, h, base));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 190));
        g.fillRoundRect(w / 8, h / 8, w / 3, h / 5, 24, 24);
        g.fillOval(w / 2, h / 2, w / 3, h / 3);
        g.setColor(new Color(0, 0, 0, 60));
        for (int i = 0; i < 6; i++) {
            g.fillRect(w / 8, h * 3 / 5 + i * (h / 24), w * 3 / 5 - i * (w / 20), h / 40);
        }
        g.dispose();
        ImageIO.write(img, "png", path.toFile());
    }

    /** 随机噪点、压不动，用来造一张 >1MB 的图，让 size:>1MB 这类搜索有结果。 */
    static void bigImage(Path path, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x += 3) {
                int v = (int) (Math.random() * 255);
                g.setColor(new Color(v, (v * 7) % 255, (v * 13) % 255));
                g.fillRect(x, y, 3, 1);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", path.toFile());
    }

    static void docx(Path path, String... paragraphs) throws IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document "
                + "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        for (String p : paragraphs) {
            xml.append("<w:p><w:r><w:t>").append(escape(p)).append("</w:t></w:r></w:p>");
        }
        xml.append("</w:body></w:document>");
        zipOf(path, Map.of("word/document.xml", xml.toString()));
    }

    static void xlsx(Path path, List<String> header, List<String>... rows) throws IOException {
        StringBuilder shared = new StringBuilder("<sst>");
        for (String h : header) {
            shared.append("<si><t>").append(escape(h)).append("</t></si>");
        }
        for (List<String> row : rows) {
            for (String cell : row) {
                shared.append("<si><t>").append(escape(cell)).append("</t></si>");
            }
        }
        shared.append("</sst>");
        StringBuilder sheet = new StringBuilder("<worksheet><sheetData><row>");
        for (int i = 0; i < header.size(); i++) {
            sheet.append("<c t=\"s\"><v>").append(i).append("</v></c>");
        }
        sheet.append("</row></sheetData></worksheet>");
        zipOf(path, Map.of("xl/sharedStrings.xml", shared.toString(),
                "xl/worksheets/sheet1.xml", sheet.toString()));
    }

    static void pptx(Path path, String... lines) throws IOException {
        StringBuilder xml = new StringBuilder("<p:sld><p:cSld>");
        for (String line : lines) {
            xml.append("<a:p><a:r><a:t>").append(escape(line)).append("</a:t></a:r></a:p>");
        }
        xml.append("</p:cSld></p:sld>");
        zipOf(path, Map.of("ppt/slides/slide1.xml", xml.toString()));
    }

    static void zip(Path path) throws IOException {
        zipOf(path, Map.of("说明.txt", "这是一个用于演示的压缩包，内容为虚构。"));
    }

    static void zipOf(Path path, Map<String, String> entries) throws IOException {
        try (OutputStream raw = Files.newOutputStream(path);
             ZipOutputStream out = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    static void binary(Path path, int bytes) throws IOException {
        byte[] data = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            data[i] = (byte) (i % 251);
        }
        Files.write(path, data);
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            all.sort((a, b) -> b.getNameCount() - a.getNameCount());
            for (Path p : all) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 删除失败（例如被占用）不应打断生成，后面会直接覆盖写
                }
            }
        }
    }
}
