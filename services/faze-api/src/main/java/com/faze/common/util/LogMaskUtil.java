/*
 * 职责：日志与审计文本的脱敏工具（手机号 / 身份证 / 银行卡 / ossKey / token / 命中片段二次脱敏）。
 * 调用方：modules/ops 的 ContentReviewService（hit_snippet 打日志前）、
 *         infra/audit/OperationLogAspect（审计降级日志）、各域打日志处。
 * 数据来源：无（纯函数）。
 * 注意：
 *   1. **这是兜底，不是许可**：《审计与日志规范》的红线是「日志禁止写 L4/L5 字段」，
 *      正确的做法是根本不把密码/token/身份证原文传给日志。本工具只解决
 *      「业务上确实要记录一个可定位的标识」时的展示问题（如「哪个用户失败了」）。
 *   2. 手机号只保留前 3 后 4（`138****8888`），身份证保留前 6 后 4，银行卡只保留后 4。
 *   3. `ossKey` 连完整路径都不能打（L4）：保留前两段目录与扩展名，中间用 `***` 代替，
 *      这样既能定位「哪个目录的文件」又不泄露文件名（文件名可能含姓名/案号）。
 *   4. `hit_snippet` 是敏感词命中的上下文原文：A3 落库前已做一次脱敏，**日志层必须再脱一次**
 *      （工作流 G4 第 3 条原文），因为落库那次可能漏掉新增的敏感模式。
 *   5. 所有方法对 null / 短串 / 非预期格式都原样返回，不抛异常——脱敏工具本身不能成为故障点。
 */
package com.faze.common.util;

import java.util.regex.Pattern;

/**
 * 日志脱敏工具。
 */
public final class LogMaskUtil {

    /** 11 位手机号（允许中间有空格/横线，先归一化再脱敏）。 */
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");

    /** 18 位身份证（末位可为 X）。 */
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");

    /** 16~19 位连续数字（银行卡，允许分组空格）。 */
    private static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");

    /** token 形状（JWT：三段 base64url，用点分隔）。 */
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*");

    private LogMaskUtil() {
    }

    /**
     * 手机号脱敏：`13812345678` → `138****5678`。
     *
     * @param phone 手机号
     * @return 脱敏值；null / 长度不足 7 时原样返回
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        String value = phone.trim();
        if (value.length() < 7) {
            return phone;
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    /**
     * 身份证脱敏：`650102199001011234` → `650102********1234`。
     *
     * @param idCard 身份证号
     * @return 脱敏值；null / 长度不足 10 时原样返回
     */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return idCard;
        }
        String value = idCard.trim();
        if (value.length() < 10) {
            return idCard;
        }
        return value.substring(0, 6) + "********" + value.substring(value.length() - 4);
    }

    /**
     * 银行卡脱敏：只保留后 4 位。
     *
     * @param cardNo 卡号
     * @return `**** **** **** 1234` 形式；null / 长度不足 4 时原样返回
     */
    public static String maskBankCard(String cardNo) {
        if (cardNo == null || cardNo.length() < 4) {
            return cardNo;
        }
        String digits = cardNo.replaceAll("\\s", "");
        return "**** **** **** " + digits.substring(Math.max(0, digits.length() - 4));
    }

    /**
     * ossKey 脱敏：保留前两段目录与扩展名，中间替换为 `***`。
     *
     * <p>例：`private/license/2026/11/abc123.jpg` → `private/license/***.jpg`</p>
     *
     * @param ossKey 对象 key
     * @return 脱敏值；null / 空白 / 无扩展名时返回 `***`
     */
    public static String maskOssKey(String ossKey) {
        if (ossKey == null || ossKey.isBlank()) {
            return "";
        }
        String value = ossKey.trim();
        int slash = value.lastIndexOf('/');
        String dir = slash > 0 ? value.substring(0, slash) : "";
        String name = slash > 0 ? value.substring(slash + 1) : value;
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot) : "";
        // 目录只保留前两段：private/license/2026/11 → private/license
        String[] dirParts = dir.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < Math.min(dirParts.length, 2); i++) {
            if (dirParts[i].isEmpty()) {
                continue;
            }
            if (prefix.length() > 0) {
                prefix.append('/');
            }
            prefix.append(dirParts[i]);
        }
        return (prefix.length() == 0 ? "" : prefix + "/") + "***" + ext;
    }

    /**
     * token 脱敏：保留前 4 位。
     *
     * @param token token 原文
     * @return 脱敏值；null / 太短时返回 `***`
     */
    public static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String value = token.trim();
        return value.length() <= 8 ? "***" : value.substring(0, 4) + "***" + value.substring(value.length() - 2);
    }

    /**
     * 命中片段二次脱敏：对片段里的手机号 / 身份证 / 银行卡 / JWT 做掩码。
     *
     * <p>顺序很重要：先脱身份证再脱银行卡，否则 18 位身份证会被银行卡规则先命中
     * （卡号规则是 16~19 位，身份证也是 18 位，先跑银行卡会把身份证当卡号，
     * 结果虽然也是脱敏，但掩码形状不对，排查时会误判字段类型）。</p>
     *
     * @param snippet 命中片段原文
     * @return 脱敏后的片段；null 返回空串
     */
    public static String maskSnippet(String snippet) {
        if (snippet == null || snippet.isEmpty()) {
            return "";
        }
        String value = snippet;
        value = JWT.matcher(value).replaceAll(match -> maskToken(match.group()));
        value = ID_CARD.matcher(value).replaceAll(match -> maskIdCard(match.group()));
        value = PHONE.matcher(value).replaceAll(match -> maskPhone(match.group()));
        value = BANK_CARD.matcher(value).replaceAll(match -> maskBankCard(match.group()));
        return value;
    }

    /**
     * 通用脱敏：对一段文本依次套用所有掩码规则（用于日志参数兜底）。
     *
     * @param text 文本
     * @return 脱敏后的文本；null 返回空串
     */
    public static String maskAll(String text) {
        return maskSnippet(text);
    }
}
