package com.zean.filepanel.store;

/**
 * 单列的界面状态。
 *
 * <p>用可变 POJO 而不是 record：Jackson 反序列化需要无参构造，
 * 而且缺字段时应保留默认值（旧版本配置文件里没有新列，不应让整份配置解析失败）。
 */
public class ColumnState {

    /** 列标识，与 {@code FileTable} 里列的 id 对应。 */
    public String id;

    /** 列宽（像素）。 */
    public double width;

    /** 是否可见。 */
    public boolean visible = true;

    public ColumnState() {
    }

    public ColumnState(String id, double width, boolean visible) {
        this.id = id;
        this.width = width;
        this.visible = visible;
    }
}
