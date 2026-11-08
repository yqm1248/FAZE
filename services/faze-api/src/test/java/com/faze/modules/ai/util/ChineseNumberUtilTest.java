/*
 * 职责：ChineseNumberUtil 的单测（B3）——中文数字 ↔ 阿拉伯数字。
 * 调用方：mvn test（纯 JUnit，无 Spring/无库）。
 * 数据来源：无（纯函数）。
 * 注意：
 *   1. **1~1260 全覆盖**：这是法条条号的实际范围（民法典 1260 条），逐个双向验证，
 *      不抽样——抽取类 bug 往往只在进位的个位数上出现（一百零一 / 一千零一）。
 *   2. 重点边界见工作流 B3 卡住第 1 条：十九/二十/一百/一百零一/一百一十/一千零一/
 *      一千零六十/一千二百六十。
 *   3. 解析失败必须返回 null 而不是抛异常（校验链路靠它判「不存在」）。
 */
package com.faze.modules.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 中文数字转换测试。
 */
class ChineseNumberUtilTest {

    /**
     * 1~1260 双向全覆盖：toArabic(toChinese(n)) == n，且 toChinese 非空。
     */
    @Test
    @DisplayName("1~1260 双向全覆盖")
    void roundTripOneTo1260() {
        for (int n = 1; n <= 1260; n++) {
            String chinese = ChineseNumberUtil.toChinese(n);
            assertEquals(n, ChineseNumberUtil.toArabic(chinese),
                    "round trip 失败：n=" + n + " chinese=" + chinese);
        }
    }

    /**
     * 工作流点名的边界值（中文 → 数字）。
     */
    @Test
    @DisplayName("关键边界：十九/二十/一百/一百零一/一百一十/一千零一/一千零六十/一千二百六十")
    void keyBoundaries() {
        assertEquals(19, ChineseNumberUtil.toArabic("十九"));
        assertEquals(20, ChineseNumberUtil.toArabic("二十"));
        assertEquals(100, ChineseNumberUtil.toArabic("一百"));
        assertEquals(101, ChineseNumberUtil.toArabic("一百零一"));
        assertEquals(110, ChineseNumberUtil.toArabic("一百一十"));
        assertEquals(1001, ChineseNumberUtil.toArabic("一千零一"));
        assertEquals(1060, ChineseNumberUtil.toArabic("一千零六十"));
        assertEquals(1260, ChineseNumberUtil.toArabic("一千二百六十"));
        assertEquals(680, ChineseNumberUtil.toArabic("六百八十"));
        assertEquals(9999, ChineseNumberUtil.toArabic("九千九百九十九"));
    }

    /**
     * 阿拉伯数字串与「两」的兼容。
     */
    @Test
    @DisplayName("阿拉伯数字串 / 两")
    void digitsAndLiang() {
        assertEquals(680, ChineseNumberUtil.toArabic("680"));
        assertEquals(680, ChineseNumberUtil.toArabic(" 680 "));
        assertEquals(2000, ChineseNumberUtil.toArabic("两千"));
    }

    /**
     * 非法输入返回 null，不抛异常。
     */
    @Test
    @DisplayName("非法输入返回 null")
    void invalidReturnsNull() {
        assertNull(ChineseNumberUtil.toArabic(null));
        assertNull(ChineseNumberUtil.toArabic(""));
        assertNull(ChineseNumberUtil.toArabic("  "));
        assertNull(ChineseNumberUtil.toArabic("abc"));
        assertNull(ChineseNumberUtil.toArabic("十百"));
        assertNull(ChineseNumberUtil.toArabic("一百百"));
        assertNull(ChineseNumberUtil.toArabic("一二三"));
        assertNull(ChineseNumberUtil.toArabic("10000"));
        assertNull(ChineseNumberUtil.toArabic("一万"));
    }

    /**
     * 0 与负数：0 合法，toChinese 越界抛 IllegalArgumentException（编程错误）。
     */
    @Test
    @DisplayName("0 合法；toChinese 越界抛 IllegalArgumentException")
    void zeroAndRange() {
        assertEquals(0, ChineseNumberUtil.toArabic("零"));
        assertEquals("零", ChineseNumberUtil.toChinese(0));
        assertEquals(-1, assertThrowsIllegalArgument(() -> ChineseNumberUtil.toChinese(-1)));
        assertEquals(-1, assertThrowsIllegalArgument(() -> ChineseNumberUtil.toChinese(10000)));
    }

    /**
     * 断言抛 IllegalArgumentException 并返回占位值。
     *
     * @param action 动作
     * @return 固定 -1（仅用于断言占位）
     */
    private int assertThrowsIllegalArgument(Runnable action) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, action::run);
        return -1;
    }
}
