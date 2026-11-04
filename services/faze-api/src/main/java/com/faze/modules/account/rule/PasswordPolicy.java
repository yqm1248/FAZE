/*
 * 职责：密码强度规则的**纯函数**实现（第 1 段 B2）。
 * 调用方：modules/account/service/AuthService（注册、改密、重置密码共用）。
 * 数据来源：无（不查库、不注入、无状态）。
 * 注意：
 *   1. 之所以放在 rule/ 层：它不依赖 Spring，可以直接 new / 静态调用做单测，
 *      测试成本接近零（《后端代码规范》§3.8）。
 *   2. 规则来自 B2 的风险出口：「≥8 位、含字母与数字」。**不加特殊字符强制**——
 *      段 1 没有运营依据支撑，加了只会让用户改用更弱的「Aa1!Aa1!」。
 *   3. 长度上限 64 与 BO 的 @Size 一致；BCrypt 只取前 72 字节，超长截断会静默改变密码，
 *      所以上限必须挡住。
 */
package com.faze.modules.account.rule;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.util.regex.Pattern;

/**
 * 密码强度规则。
 */
public final class PasswordPolicy {

    /** 最小长度。 */
    private static final int MIN_LENGTH = 8;
    /** 最大长度（BCrypt 72 字节限制的保守值）。 */
    private static final int MAX_LENGTH = 64;

    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    private PasswordPolicy() {
    }

    /**
     * 校验密码强度，不通过直接抛业务异常。
     *
     * @param password 明文密码
     * @throws BusinessException 402010 密码强度不足
     */
    public static void assertStrong(String password) {
        if (!isStrong(password)) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_WEAK);
        }
    }

    /**
     * 判断密码是否满足强度要求。
     *
     * @param password 明文密码
     * @return 满足返回 true
     */
    public static boolean isStrong(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return false;
        }
        return HAS_LETTER.matcher(password).find() && HAS_DIGIT.matcher(password).find();
    }
}
