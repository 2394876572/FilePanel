package com.zean.filepanel.store;

import java.nio.file.Path;

/**
 * 界面配置的读写。
 *
 * <p>位置：<b>扫描根目录下的 {@code .filepanel\config.json}</b>（设计决策 D1 的便携方案）。
 * 选它而不是 {@code %APPDATA%} 的理由：把整个文件夹拷到另一台机器，历史与偏好一起过去。
 * 代价是配置随根目录走——换一个文件夹就是另一份列宽。这是便携换来的必然取舍。
 *
 * <p>容错行为见 {@link JsonFileStore}：读失败回默认值、写失败只记日志。
 */
public final class ConfigStore extends JsonFileStore {

    /** 数据目录名。这个值同时出现在默认排除规则里，改名必须两边一起改。 */
    public static final String DIR_NAME = ".filepanel";

    private static final String FILE_NAME = "config.json";

    private final Path root;

    public ConfigStore(Path root) {
        super(root == null ? null : root.resolve(DIR_NAME), FILE_NAME);
        this.root = root;
    }

    /** 读取配置；文件不存在或损坏时返回默认值。 */
    public UiState load() {
        UiState state = readOrNull(UiState.class);
        return state == null ? new UiState() : state;
    }

    /**
     * 写入配置。
     *
     * @return 是否写入成功；调用方可据此提示用户，但不应该因此中断任何流程
     */
    public boolean save(UiState state) {
        return write(state);
    }

    /** 本配置对应的根目录。 */
    public Path root() {
        return root;
    }
}
