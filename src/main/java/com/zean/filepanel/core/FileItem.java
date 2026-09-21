package com.zean.filepanel.core;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Locale;

/**
 * 一个被扫描到的文件或文件夹的元数据快照。
 *
 * <p>设计为不可变 record：扫描结果可能被多线程读取（扫描线程写入、UI 线程遍历），
 * 不可变对象天然安全，M4 的索引缓存也能直接序列化。
 *
 * <p><b>不含标签字段</b>：标签属于用户数据而非文件系统属性，其生命周期与本对象完全不同
 * （重命名要迁移、文件删除要清理、可批量编辑）。M4 会用独立的 {@code TagStore}
 * 以路径为键维护，避免把两套关注点搅在一个 record 里。
 *
 * @param name      文件名（含扩展名）
 * @param path      绝对路径
 * @param relPath   相对根目录的路径，统一用 {@code /} 分隔（跨平台一致，便于搜索与展示）
 * @param parentRel 相对根目录的父目录路径；位于根目录下时为空串
 * @param directory 是否为目录
 * @param ext       小写扩展名，不含点；无扩展名为空串
 * @param kind      类型分类
 * @param size      字节数；目录为 0（不递归求和，避免扫描期额外开销）
 * @param created   创建时间
 * @param modified  修改时间
 * @param accessed  访问时间
 * @param depth     相对根目录的层级，根目录直接子项为 0
 * @param hidden    是否隐藏属性
 * @param readOnly  是否只读属性
 * @param system    是否系统属性
 */
public record FileItem(
        String name,
        Path path,
        String relPath,
        String parentRel,
        boolean directory,
        String ext,
        FileKind kind,
        long size,
        FileTime created,
        FileTime modified,
        FileTime accessed,
        int depth,
        boolean hidden,
        boolean readOnly,
        boolean system) {

    /**
     * 从文件名提取小写扩展名（不含点）。
     *
     * <p>约定：以点开头的名字（如 {@code .gitignore}）视为<b>无扩展名</b>，
     * 这与资源管理器一致；点在末尾（如 {@code backup.}）同样视为无扩展名。
     */
    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** “类型”列的展示文本，例如 {@code 文档 · DOCX}、{@code 文件夹}、{@code 其他}。 */
    public String typeText() {
        if (directory) {
            return FileKind.DIRECTORY.displayName();
        }
        if (ext.isEmpty()) {
            return kind.displayName();
        }
        return kind.displayName() + " · " + ext.toUpperCase(Locale.ROOT);
    }

    /** “所在位置”列的展示文本；位于根目录下时显示为“（根目录）”。 */
    public String locationText() {
        return parentRel.isEmpty() ? "（根目录）" : parentRel;
    }
}
