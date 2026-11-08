/*
 * 职责：银行卡号格式校验（Luhn 校验 + 长度约束），供提现收款账户绑定使用。
 * 调用方：modules/workbench/service/WorkbenchServiceImpl（绑定/更换收款账户）。
 * 数据来源：客户端提交的卡号字符串。
 * 注意：
 *   1. **只做格式校验，不做实名核验**：平台没有银行卡实名鉴权能力，
 *      「必须与实名一致」只做提示（《第 5 段工作流》§七 第 9 项、§八.2）。
 *   2. 长度按银联卡 16~19 位（借记卡 19 位是真实存在的，不是笔误）。
 *   3. 本类**不含日志**：卡号是 L4 字段，进日志的地方一律只留后 4 位（调用方负责）。
 *   4. 纯函数，无状态，可直接单测。
 */
package com.faze.common.util;

/**
 * 银行卡号校验工具。
 */
public final class BankCardValidator {

    /** 卡号允许长度：16~19 位数字（银联借记卡最长 19 位）。 */
    private static final int MIN_LENGTH = 16;

    /** 卡号允许长度上限。 */
    private static final int MAX_LENGTH = 19;

    /** 允许的字符集：纯数字（不做空格/横杠容错，前端负责清洗）。 */
    private static final String DIGITS_ONLY = "^\\d+$";

    private BankCardValidator() {
    }

    /**
     * 校验银行卡号（长度 + Luhn）。
     *
     * @param cardNo 卡号（可含首尾空白，内部 trim）
     * @return 合法返回 true
     */
    public static boolean isValid(String cardNo) {
        if (cardNo == null) {
            return false;
        }
        String digits = cardNo.trim();
        if (digits.length() < MIN_LENGTH || digits.length() > MAX_LENGTH) {
            return false;
        }
        if (!digits.matches(DIGITS_ONLY)) {
            return false;
        }
        return passLuhn(digits);
    }

    /**
     * 取卡号后 4 位（对外展示与日志用）。
     *
     * @param cardNo 卡号
     * @return 后 4 位；卡号为空或不足 4 位时返回 null
     */
    public static String tail4(String cardNo) {
        if (cardNo == null) {
            return null;
        }
        String digits = cardNo.trim();
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }

    /**
     * Luhn 校验（模 10 算法）。
     *
     * <p>从右往左，偶数位（从 0 计数的奇数下标）乘 2，结果大于 9 则减 9，全部相加能被 10 整除。</p>
     *
     * @param digits 纯数字串
     * @return 通过校验返回 true
     */
    private static boolean passLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
