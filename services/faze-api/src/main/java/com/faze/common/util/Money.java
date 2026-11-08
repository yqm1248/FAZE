/*
 * 职责：金额计算的**唯一**入口（第 1 段 D 组，后端规范 §8.6）。
 * 调用方：modules/trade（扣费、退款分摊、结算、抽成）。
 * 数据来源：无（纯函数，不注入、不查库）。
 * 注意：
 *   1. **一律整数分（Long）**，禁止 double/float。0.1 + 0.2 = 0.30000000000000004
 *      在资金链里是事故，不是精度问题。
 *   2. **舍入只在这里做**：业务代码不许自己 setScale。分散写舍入方式，
 *      迟早会出现「A 处四舍五入、B 处截断」，同一笔钱两处算出不同结果。
 *   3. `splitEvenly` 的余数**必须落给最后一份**，保证 sum(结果) == 总额。
 *      「四舍五入后差额不管」会让账永远差一分钱，且很难查。
 *   4. 比例用**基点**（10000 基点 = 100%），避免引入小数比例再四舍五入。
 */
package com.faze.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额工具。
 */
public final class Money {

    /** 基点基数：10000 基点 = 100%。 */
    public static final long BASIS_POINTS_PER_UNIT = 10_000L;

    /** 一元 = 100 分。 */
    public static final long CENTS_PER_YUAN = 100L;

    private Money() {
    }

    /**
     * 按基点计算一个金额。
     *
     * @param amountCents  金额（分）
     * @param basisPoints  基点（10000 = 100%）
     * @return 计算结果（分），四舍五入到整数分
     */
    public static long percent(long amountCents, int basisPoints) {
        return BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(BASIS_POINTS_PER_UNIT), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * 均分金额，余数全给最后一份。
     *
     * <p>例：10000 分分给 3 人 → [3333, 3333, 3334]，三份之和 == 10000。</p>
     *
     * @param totalCents 总额（分）
     * @param parts      份数（必须 > 0）
     * @return 每份金额（分），长度等于 parts
     * @throws IllegalArgumentException 份数非正数
     */
    public static long[] splitEvenly(long totalCents, int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("份数必须大于 0");
        }
        long base = totalCents / parts;
        long remainder = totalCents - base * parts;
        long[] out = new long[parts];
        for (int i = 0; i < parts; i++) {
            out[i] = base;
        }
        out[parts - 1] += remainder;
        return out;
    }

    /**
     * 按比例分摊（用于多人分摊同一笔退款）。
     *
     * <p>先算每份，再把除不尽的余数补给最后一份，保证总和守恒。</p>
     *
     * @param totalCents 总额（分）
     * @param weights    每一份的权重（非负；全 0 时退化为均分）
     * @return 每份金额（分），之和等于 totalCents
     * @throws IllegalArgumentException 权重数组为空或含负数
     */
    public static long[] splitByWeight(long totalCents, long[] weights) {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("权重不能为空");
        }
        long sum = 0;
        for (long w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("权重不能为负");
            }
            sum += w;
        }
        if (sum == 0) {
            return splitEvenly(totalCents, weights.length);
        }
        long[] out = new long[weights.length];
        long allocated = 0;
        for (int i = 0; i < weights.length; i++) {
            out[i] = BigDecimal.valueOf(totalCents)
                    .multiply(BigDecimal.valueOf(weights[i]))
                    .divide(BigDecimal.valueOf(sum), 0, RoundingMode.DOWN)
                    .longValueExact();
            allocated += out[i];
        }
        out[weights.length - 1] += totalCents - allocated;
        return out;
    }

    /**
     * 分转元（仅用于展示或日志，**不参与计算**）。
     *
     * @param cents 金额（分）
     * @return 形如 "12.34" 的字符串
     */
    public static String centsToYuanText(long cents) {
        return BigDecimal.valueOf(cents)
                .movePointLeft(2)
                .setScale(2, RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    /**
     * 判断是否为合法金额（正数）。
     *
     * @param cents 金额（分）
     * @return 大于 0 返回 true
     */
    public static boolean isPositive(long cents) {
        return cents > 0;
    }
}
