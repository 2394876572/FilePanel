package com.zean.filepanel.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 需要跨次启动保留的界面状态。
 *
 * <p>只放“用户调过一次就不想再调”的东西。搜索词、当前选中项这类瞬态信息刻意不持久化——
 * 重新打开时看到一个自己没输入过的搜索词，比什么都不记住更让人困惑。
 *
 * <p>用可变 POJO 而非 record：{@code version} 与各字段都需要“缺省即默认值”的语义，
 * 旧配置文件里没有的字段应该安静地用默认值补上，而不是解析失败。
 */
public class UiState {

    /** 配置结构版本，便于将来做迁移。 */
    public int version = 1;

    /** 是否显示文件夹（设计决策 D7）。 */
    public boolean showFolders = false;

    /** “最近使用”卡片区是否收起。收起后重开程序不应又自己弹出来。 */
    public boolean recentStripCollapsed = false;

    /** 主题：{@code light} 或 {@code dark}。 */
    public String theme = "light";

    /** 排序所在列的 id。 */
    public String sortColumnId = "name";

    /** 是否升序。 */
    public boolean sortAscending = true;

    /** 各列的宽度与可见性。 */
    public List<ColumnState> columns = new ArrayList<>();

    /**
     * 列宽布局的版本号。
     *
     * <p>存在的理由：默认列宽一旦改动（例如把"修改时间"从 125 加宽到 148 以免时间戳被截断），
     * 老用户的配置文件里仍然存着旧的宽度，而恢复逻辑会一直照用——
     * 结果就是"修好了，但只有新用户能看到"。
     * 因此保存时写入当前版本；版本落后时只恢复列的显隐、改用新的默认宽度。
     */
    public int layoutVersion = 0;

    /** 窗口尺寸与位置。 */
    public double windowWidth = 1320;
    public double windowHeight = 720;
    public double windowX = Double.NaN;
    public double windowY = Double.NaN;

    /**
     * 永久删除阈值（字节），默认 1 GiB。
     *
     * <p><b>为什么这个设置放在"界面状态"里</b>：为一个数字单开一个 store 和一份 JSON，
     * 换来的只有"文件更多、要同步的地方更多"。它和 {@code theme}、{@code showFolders} 一样
     * 属于"用户偏好"，且生命周期与本文件完全一致（按根目录保存、缺省即默认值）。
     *
     * <p>这里刻意<b>不做校验</b>：{@link com.zean.filepanel.core.DeletePolicy} 的构造函数会用
     * {@code Math.max(下限, 值)} 兜住。校验只写一处，读配置文件这条路径就不会有第二个规则。
     */
    public long deleteThresholdBytes = com.zean.filepanel.core.DeletePolicy.DEFAULT_THRESHOLD_BYTES;

    /**
     * 是否"所有删除一律放入回收站"（忽略阈值）。
     *
     * <p>D4 给保守用户留的退路：宁可回收站被占满，也不接受任何永久删除。
     */
    public boolean alwaysRecycle = false;

    /**
     * 搜索范围（{@code ALL} / {@code NAME} / {@code KIND} / {@code SIZE}）。
     *
     * <p>存字符串而不是枚举：配置文件被手工改成不认识的值时，枚举反序列化会直接抛异常、
     * 整个配置退回默认（连带丢掉窗口位置与列宽）。字符串 + 读取时宽容匹配，
     * 最坏情况只丢这一个字段。
     */
    public String searchScope = "ALL";
}
