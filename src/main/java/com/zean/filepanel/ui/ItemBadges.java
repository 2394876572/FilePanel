package com.zean.filepanel.ui;

import com.zean.filepanel.core.FileItem;

import java.util.List;

/**
 * 表格向外界询问"这个条目有没有被收藏、打了哪些标签"。
 *
 * <p>抽成接口而不是让 {@link FileTable} 直接依赖 store 包，是为了让表格只知道"怎么问、怎么画"，
 * 不关心数据存在哪里。收藏与标签的读写语义（归一化路径、重命名迁移、容错落盘）
 * 全部留在 store 层，表格不会有机会绕过它们。
 */
public interface ItemBadges {

    boolean isFavorite(FileItem item);

    List<String> tagsOf(FileItem item);

    /** 用户点击星标。 */
    void toggleFavorite(FileItem item);

    /** 不带任何装饰的空实现，供测试与预览场景使用。 */
    ItemBadges NONE = new ItemBadges() {
        @Override
        public boolean isFavorite(FileItem item) {
            return false;
        }

        @Override
        public List<String> tagsOf(FileItem item) {
            return List.of();
        }

        @Override
        public void toggleFavorite(FileItem item) {
            // 空实现
        }
    };
}
