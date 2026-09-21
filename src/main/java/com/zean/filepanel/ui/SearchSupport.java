package com.zean.filepanel.ui;

import com.zean.filepanel.core.FileItem;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 界面层的小工具：把模型里的时间转成可用于比较的本地日期。 */
final class SearchSupport {

    private SearchSupport() {
    }

    static LocalDate toLocalDate(FileItem item) {
        if (item == null || item.modified() == null) {
            return null;
        }
        Instant instant = item.modified().toInstant();
        return instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
