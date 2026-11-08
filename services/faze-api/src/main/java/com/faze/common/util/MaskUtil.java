/*
 * 职责：文本脱敏工具——把手机号 / 身份证号 / 银行卡号改成可展示的掩码形式。
 * 调用方：modules/ops 的 ContentReviewService（hit_snippet 落库前脱敏）、
 *         后续任何需要「把用户输入片段写进日志或审核表」的地方。
 * 数据来源：无（纯函数，不查库、不注入、无状态）。
 * 注意：
 *   1. **只脱敏、不删除**：把 `13812345678` 变成 `138****5678`，保留可辨认性；
 *      一律不用「整段替换成 ***」——那样审核员看不出是什么内容，等于把证据删了。
 *   2. **替换顺序必须由长到短**：身份证（18 位）→ 银行卡（16~19 位）→ 手机号（11 位）。
 *      反过来做的话，身份证里的 11 位连续数字会先被手机号规则切碎，结果既不是身份证
 *      也没脱敏干净。
 *   3. 这是**正则匹配**，不做校验位算法：目的是防止「片段里夹着号码」出现在审核表，
 *      不是判定号码真伪。误伤（把 18 位订单号当身份证）在审核场景可接受。
 *   4. 本类**不改原文**：它只处理「要落库/要打日志的那一段副本」，业务表里的原文不动
 *      （这是第 7 段 §零.4 第 1 条红线的配套）。
 */
package com.faze.common.util;

import java.util.regex.Pattern;

/**
 * 脱敏工具（纯函数）。
 */
public final class MaskUtil {

    /** 中国大陆手机号：11 位，1 开头，第二位 3~9。 */
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");

    /** 18 位身份证（末位可为 X）。 */
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");

    /** 银行卡：16~19 位连续数字。 */
    private static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");

    /** 工具类，禁止实例化。 */
    private MaskUtil() {
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位。
     *
     * @param phone 手机号（null / 长度不足时原样返回）
     * @return 形如 {@code 138****5678}
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 身份证号脱敏：保留前 3 位与后 4 位，中间固定 11 个星号。
     *
     * @param idCard 身份证号（null / 长度不足时原样返回）
     * @return 形如 {@code 110***********1234}
     */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return idCard;
        }
        return idCard.substring(0, 3) + "***********" + idCard.substring(idCard.length() - 4);
    }

    /**
     * 银行卡号脱敏：只保留后 4 位。
     *
     * @param bankCard 卡号（null / 长度不足时原样返回）
     * @return 形如 {@code ************1234}
     */
    public static String maskBankCard(String bankCard) {
        if (bankCard == null || bankCard.length() <= 4) {
            return bankCard;
        }
        return "*".repeat(bankCard.length() - 4) + bankCard.substring(bankCard.length() - 4);
    }

    /**
     * 对一段自由文本做整体脱敏。
     *
     * <p>顺序固定：身份证 → 银行卡 → 手机号（见类注释第 2 条）。</p>
     *
     * @param text 原文片段（null / 空白原样返回）
     * @return 脱敏后的片段
     */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = ID_CARD.matcher(text).replaceAll(m -> maskIdCard(m.group()));
        result = BANK_CARD.matcher(result).replaceAll(m -> maskBankCard(m.group()));
        result = PHONE.matcher(result).replaceAll(m -> maskPhone(m.group()));
        return result;
    }
}
