package com.zean.filepanel.core;

import java.util.List;

/**
 * 一批选中项的删除方案。
 *
 * @param recyclable 将放入回收站的条目（可还原）
 * @param permanent  将永久删除的条目（不可恢复）
 */
public record DeletePlan(List<FileItem> recyclable, List<FileItem> permanent) {

    /** 是否混合了两类条目。混合时必须让用户明确选择，不能静默按策略执行。 */
    public boolean isMixed() {
        return !recyclable.isEmpty() && !permanent.isEmpty();
    }

    public boolean isEmpty() {
        return recyclable.isEmpty() && permanent.isEmpty();
    }

    /** 是否全部都是永久删除。 */
    public boolean isAllPermanent() {
        return recyclable.isEmpty() && !permanent.isEmpty();
    }

    /** 是否全部都可回收。 */
    public boolean isAllRecyclable() {
        return permanent.isEmpty() && !recyclable.isEmpty();
    }

    public int total() {
        return recyclable.size() + permanent.size();
    }

    /** 全部条目（可回收 + 永久），顺序与用户选中顺序无关，仅用于"全部按策略处理"。 */
    public List<FileItem> items() {
        java.util.ArrayList<FileItem> all = new java.util.ArrayList<>(recyclable.size() + permanent.size());
        all.addAll(recyclable);
        all.addAll(permanent);
        return List.copyOf(all);
    }

    /** 将被永久删除的条目总字节数（目录按 0 计，与 {@link FileItem#size()} 一致）。 */
    public long permanentBytes() {
        return permanent.stream().mapToLong(FileItem::size).sum();
    }
}
