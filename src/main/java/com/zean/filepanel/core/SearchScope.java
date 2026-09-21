package com.zean.filepanel.core;

/**
 * 搜索范围：裸关键词应该去哪几个字段里找。
 *
 * <h2>为什么要有这一层</h2>
 * 默认行为（{@link #ALL}）是"文件名<b>或所在路径</b>包含"。这带来一个用户很难自己搞明白的现象：
 * 搜"灰度发布"会把放在 {@code 灰度发布管理平台\} 目录里的、名字里根本没有"灰度发布"的文件也列出来。
 * 对多数人这是好事（按目录名找东西很自然），但"我只想按文件名找"这个需求同样真实，
 * 而且靠语法表达不了——{@code *.docx} 那类通配符要求用户先知道怎么拼。
 * 所以把它做成一个下拉：不用学语法，选一下就行。
 *
 * <h2>显式语法永远优先</h2>
 * 范围只作用于<b>裸关键词</b>。用户在"文件名"范围下写 {@code size:>10MB}，
 * 仍然按大小筛，不会被当成文件名去搜。
 * 理由是使用者常把整条复杂查询粘进搜索框（同事发来的一串条件、自己上一次的查询），
 * 如果切换范围会让这些语法静默失效，那这个下拉就成了陷阱而不是帮手。
 */
public enum SearchScope {

    /** 文件名或所在路径包含（默认，等价于历史行为）。 */
    ALL("全部", "文件名或所在路径包含"),

    /** 只匹配文件名。 */
    NAME("文件名", "只匹配文件名，不看所在路径"),

    /** 把关键词当类型名（中英文都认）。 */
    KIND("类型", "按文件类型匹配，例如 图片 / 表格 / 文档 / 代码"),

    /** 把关键词当大小并配一个比较方式。 */
    SIZE("大小", "按文件大小匹配，例如 10MB");

    private final String label;
    private final String hint;

    SearchScope(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    /** 界面下拉里显示的文本。 */
    public String label() {
        return label;
    }

    /** 一句话解释这个范围在做什么，显示在搜索框右侧的提示里。 */
    public String hint() {
        return hint;
    }

    @Override
    public String toString() {
        return label;
    }
}
