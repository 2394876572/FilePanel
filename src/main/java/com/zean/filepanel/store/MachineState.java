package com.zean.filepanel.store;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;

/**
 * 机器级状态：**不属于任何一个被管理文件夹**的那点数据。
 *
 * <h2>为什么需要它，以及它和 {@code .filepanel} 的分工</h2>
 * 其余所有状态（配置、最近使用、收藏、标签、索引、撤销日志）都放在
 * <b>被管理的那个文件夹</b>下的 {@code .filepanel\} 里——这是刻意的，好处是
 * "把文件夹拷走，数据跟着走"。但有一件事放不进去：
 * <b>"上一次打开的是哪个文件夹"</b>。要记住它，就必须有一个不依赖任何根目录的位置。
 *
 * <h2>为什么安装版必须要它</h2>
 * 绿色版压根不需要：程序放在哪个文件夹就管哪个文件夹，根目录是推出来的。
 * 但装到 {@code %LOCALAPPDATA%\Programs\FilePanel} 之后，程序所在目录是它自己的安装目录，
 * 管它没有任何意义——所以安装版必须"记住用户上次真正在管的那个目录"。
 * （这也是本项目此前一个真实的缺口：{@code RootResolver} 的"上次打开的文件夹"
 * 参数一直传的是 {@code null}，因为没有地方能存它。）
 *
 * <h2>为什么用 {@code %LOCALAPPDATA%} 而不是注册表或用户主目录</h2>
 * <ul>
 *   <li>注册表：这个程序一贯"不写注册表"，为了一个字符串破例不值得；</li>
 *   <li>用户主目录根下扔一个文件：太显眼、太不礼貌；</li>
 *   <li>{@code %LOCALAPPDATA%}：这是 Windows 给"每个用户的本地程序数据"准备的位置，
 *       与安装位置（也在 {@code %LOCALAPPDATA%\Programs}）同源，卸载时一起处理即可。</li>
 * </ul>
 *
 * <h2>坏了怎么办</h2>
 * 与其它 store 一样继承 {@link JsonFileStore}：读坏就回到默认值（没有记住任何目录），
 * 表现为"下次启动让你重新选一次文件夹"。**绝不能因为它坏了就打不开程序。**
 */
public final class MachineState extends JsonFileStore {

    public static final String DIR_NAME = "FilePanel";
    public static final String FILE_NAME = "machine.json";

    /** 持久化的字段。用可变 POJO：缺字段即默认值，旧文件能安静升级。 */
    public static final class Data {
        /** 上一次真正在管理的文件夹（绝对路径）。为空表示还没选过。 */
        public String lastRoot = "";

        /**
         * 这个状态文件是被哪个版本写下的，仅用于排查。
         * 刻意不做迁移逻辑：目前只有一个字段，加版本判断是过度设计。
         */
        public String writtenBy = "";
    }

    public MachineState(Path dataDirectory) {
        super(dataDirectory, FILE_NAME);
    }

    /**
     * 默认位置：{@code %LOCALAPPDATA%\FilePanel\}。
     *
     * <p>环境变量缺失时（极少数受限环境）退回用户主目录下的同名隐藏目录——
     * 宁可位置不理想，也不能因为拿不到环境变量就让功能失效。
     */
    public static Path defaultDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, DIR_NAME);
        }
        return Path.of(System.getProperty("user.home", "."), "." + DIR_NAME);
    }

    /** 读。读不到或读坏都返回"没记住任何目录"，而不是 null。 */
    public Data load() {
        Data data = readOrNull(new TypeReference<Data>() {
        });
        return data == null ? new Data() : data;
    }

    /**
     * 记住"上次管理的文件夹"。
     *
     * @return 是否写入成功（失败不影响使用，只是下次要重选）
     */
    public boolean rememberRoot(Path root, String version) {
        if (root == null) {
            return false;
        }
        Data data = new Data();
        data.lastRoot = root.toAbsolutePath().normalize().toString();
        data.writtenBy = version == null ? "" : version;
        return write(data);
    }

    /** 取上次管理的文件夹；没记住或路径已不存在时返回 null。 */
    public Path lastRoot() {
        String value = load().lastRoot;
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(value);
            // 目录可能已经被删掉/改名/在拔掉的移动盘上——那就当作没记住，
            // 由调用方走"让用户重新选"的流程
            return java.nio.file.Files.isDirectory(path) ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 清掉记住的目录（卸载时用；也让用户有一个"忘了它"的出口）。 */
    public boolean forget() {
        deleteFile();
        return !java.nio.file.Files.exists(file());
    }
}
