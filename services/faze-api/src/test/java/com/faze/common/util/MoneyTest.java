/*
 * 职责：Money 的单元测试（第 1 段 D 组前置）。
 * 调用方：`mvn test`（不启动 Spring，纯函数测试，毫秒级）。
 * 数据来源：无。
 * 注意：
 *   1. 这是《后端代码规范》§12.4 要求的「资金链路必测」的第一块：
 *      金额计算的守恒性（分不丢、和不变）比任何接口测试都更值得先测。
 *   2. 重点断言「守恒」而不是「某个具体数字」：守恒是本质，具体数字只是某次实现的结果。
 */
package com.faze.common.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 金额工具测试。
 */
class MoneyTest {

    /**
     * 按基点抽成：15% 的 10000 分 = 1500 分。
     */
    @Test
    @DisplayName("percent：15% 抽成精确")
    void percentComputes() {
        assertEquals(1500L, Money.percent(10_000L, 1500));
        assertEquals(0L, Money.percent(0L, 1500));
    }

    /**
     * 抽成四舍五入到整数分，不产生小数分。
     */
    @Test
    @DisplayName("percent：结果必须是整数分且按 HALF_UP")
    void percentRoundsHalfUp() {
        // 999 分 * 15% = 149.85 → 150
        assertEquals(150L, Money.percent(999L, 1500));
    }

    /**
     * 均分必须守恒：三份之和恒等于总额。
     */
    @Test
    @DisplayName("splitEvenly：余数给最后一份，总和守恒")
    void splitEvenlyConservesTotal() {
        long[] parts = Money.splitEvenly(10_000L, 3);
        assertArrayEquals(new long[]{3333L, 3333L, 3334L}, parts);
        assertEquals(10_000L, sum(parts));
    }

    /**
     * 按权重分摊必须守恒。
     */
    @Test
    @DisplayName("splitByWeight：权重分摊后总和守恒")
    void splitByWeightConservesTotal() {
        long[] parts = Money.splitByWeight(1000L, new long[]{1, 2, 3});
        assertEquals(1000L, sum(parts));
    }

    /**
     * 权重全 0 时退化为均分。
     */
    @Test
    @DisplayName("splitByWeight：权重全 0 退化为均分")
    void splitByWeightFallsBackToEvenly() {
        long[] parts = Money.splitByWeight(100L, new long[]{0, 0});
        assertArrayEquals(new long[]{50L, 50L}, parts);
    }

    /**
     * 非法参数必须抛，不许静默返回 0（静默会掩盖调用方的错误）。
     */
    @Test
    @DisplayName("非法参数：份数/权重非法时抛异常")
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> Money.splitEvenly(100L, 0));
        assertThrows(IllegalArgumentException.class, () -> Money.splitByWeight(100L, new long[]{}));
        assertThrows(IllegalArgumentException.class, () -> Money.splitByWeight(100L, new long[]{-1}));
    }

    /**
     * 分转元只用于展示。
     */
    @Test
    @DisplayName("centsToYuanText：展示用，两位小数")
    void centsToYuanTextFormats() {
        assertEquals("12.34", Money.centsToYuanText(1234L));
        assertEquals("0.00", Money.centsToYuanText(0L));
    }

    private long sum(long[] values) {
        long total = 0;
        for (long v : values) {
            total += v;
        }
        return total;
    }
}
