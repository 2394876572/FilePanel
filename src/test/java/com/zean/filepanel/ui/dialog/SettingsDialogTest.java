package com.zean.filepanel.ui.dialog;

import com.zean.filepanel.core.DeletePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置对话框的纯逻辑测试。
 *
 * <p>只测不需要 JavaFX 工具包的部分（解析与措辞）。面板本身（回显、实时预览、
 * "勾了一律走回收站要禁用阈值输入"）由 {@code --ui-selftest} 里
 * {@link SettingsDialog#selfCheck} 在真实 JavaFX 环境下断言——那些行为离开控件就没有意义。
 */
class SettingsDialogTest {

    @Test
    @DisplayName("数字 + 单位按 1024 进制换算（界面上写 1 MB 得到的正好是阈值下限）")
    void parsesNumberAndUnit() {
        assertEquals(1024L * 1024, SettingsDialog.parseThreshold("1", "MB"));
        assertEquals(1024L * 1024 * 1024, SettingsDialog.parseThreshold("1", "GB"));
        assertEquals(512L * 1024 * 1024, SettingsDialog.parseThreshold("512", "MB"));
        assertEquals(2L * 1024 * 1024 * 1024, SettingsDialog.parseThreshold("2", "GB"));
        // 小数要能接受：用户会写 1.5 GB
        assertEquals((long) (1.5 * 1024 * 1024 * 1024), SettingsDialog.parseThreshold("1.5", "GB"));
        // 前后空格与大小写单位都应容忍
        assertEquals(1024L * 1024, SettingsDialog.parseThreshold("  1  ", "mb"));
    }

    @Test
    @DisplayName("非法输入返回 -1 而不是抛异常（输入框的中间态必然是「非法」的，那不是错误）")
    void returnsMinusOneForBadInput() {
        assertEquals(-1, SettingsDialog.parseThreshold(null, "MB"));
        assertEquals(-1, SettingsDialog.parseThreshold("", "MB"));
        assertEquals(-1, SettingsDialog.parseThreshold("   ", "MB"));
        assertEquals(-1, SettingsDialog.parseThreshold("abc", "MB"));
        assertEquals(-1, SettingsDialog.parseThreshold("1,5", "MB"));
        assertEquals(-1, SettingsDialog.parseThreshold("0", "MB"), "0 必须判为非法");
        assertEquals(-1, SettingsDialog.parseThreshold("-5", "MB"), "负数必须判为非法");
        assertEquals(-1, SettingsDialog.parseThreshold("1", "TB"), "未支持的单位必须判为非法");
        assertEquals(-1, SettingsDialog.parseThreshold("1", null));
        assertEquals(-1, SettingsDialog.parseThreshold("NaN", "MB"));
        assertEquals(-1, SettingsDialog.parseThreshold("Infinity", "GB"));
    }

    @Test
    @DisplayName("溢出保护：超出 long 范围的输入判为非法，而不是饱和成 Long.MAX_VALUE")
    void rejectsOverflow() {
        // 9999999999 GB ≈ 1.07e19 字节，超过 Long.MAX_VALUE（≈9.22e18）。
        // 若饱和成 Long.MAX_VALUE，阈值会变成"永远不会永久删除"，
        // 与用户"想更激进地永久删除"的输入意图正好相反。
        assertEquals(-1, SettingsDialog.parseThreshold("9999999999", "GB"));
        // 而 999999999 GB ≈ 1.07e18 仍在 long 范围内，属于合法输入（接近 8 EiB，用户自己的选择）
        assertTrue(SettingsDialog.parseThreshold("999999999", "GB") > 0);
    }

    @Test
    @DisplayName("规则预览与 DeletePolicy 的描述完全同源，不另写一套措辞")
    void ruleTextMatchesPolicyDescribe() {
        assertEquals(new DeletePolicy(1024L * 1024, false).describe(),
                SettingsDialog.describeRule(1024L * 1024, false));
        assertEquals(new DeletePolicy(1024L * 1024, true).describe(),
                SettingsDialog.describeRule(1024L * 1024, true));

        assertTrue(SettingsDialog.describeRule(1024L * 1024, false).contains("1.00 MB"),
                SettingsDialog.describeRule(1024L * 1024, false));
        assertTrue(SettingsDialog.describeRule(1024L * 1024, true).contains("一律放入回收站"),
                SettingsDialog.describeRule(1024L * 1024, true));
    }

    @Test
    @DisplayName("解析结果低于下限时由 DeletePolicy 抬高，且能事先问出来")
    void belowMinimumIsDetectable() {
        long parsed = SettingsDialog.parseThreshold("0.5", "MB");
        assertTrue(parsed > 0, "0.5 MB 是合法输入，只是低于下限");
        assertTrue(DeletePolicy.isBelowMinimum(parsed), "必须能事先判断出会被抬高");
        assertEquals(DeletePolicy.MIN_THRESHOLD_BYTES, DeletePolicy.clampThreshold(parsed));
        // 界面据此显示"将按 1.00 MB 处理"，而不是静默改值
        assertTrue(SettingsDialog.describeRule(DeletePolicy.clampThreshold(parsed), false)
                .contains("1.00 MB"));
    }
}
