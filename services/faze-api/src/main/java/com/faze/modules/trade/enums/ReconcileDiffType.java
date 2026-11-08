/*
 * 职责：对账差错类型枚举（4 种），数据库存 code 下划线值（reconciliation_diff.diff_type）。
 * 调用方：D 组的渠道对账（比较对账单与本地充值单）、E 组的恒等式巡检、PC A-09 Tab2。
 * 数据来源：《第 6 段工作流》§三 A4 第 3 条。
 * 注意：
 *   1. **只有 channel_only 允许自动补单**：渠道账单有、本地无对应充值单——这是「掉单」，
 *      钱已到渠道，补单是恢复用户余额。其余三种都**不自动处理**：
 *      · local_only 疑似伪造回调（本地有 success、渠道没有），必须先人工核；
 *      · amount_mismatch 是两边金额不等，自动补会补错钱；
 *      · identity_mismatch 是账务恒等式不平（E1 巡检产出），不是渠道对账产出。
 *   2. 本枚举的 autoRecoverable 是**唯一判据**，D 组不许再写一份「哪些类型能自动补」。
 */
package com.faze.modules.trade.enums;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对账差错类型。与 {@code reconciliation_diff.diff_type} 的取值一一对应。
 */
@Getter
public enum ReconcileDiffType {

    /** 平台有、渠道无。疑似伪造回调，**不自动补**，必须先人工核。 */
    LOCAL_ONLY("local_only", "平台有渠道无", false),

    /** 渠道有、平台无。掉单，**允许自动补单**（建新充值单 + 入账）。 */
    CHANNEL_ONLY("channel_only", "渠道有平台无", true),

    /** 两边都有但金额不等。**不自动处理**，落差异等人工。 */
    AMOUNT_MISMATCH("amount_mismatch", "金额不一致", false),

    /** 账务恒等式不平（恒等式巡检产出，不是渠道对账产出）。**不自动处理**，告警等人工。 */
    IDENTITY_MISMATCH("identity_mismatch", "账务恒等式不平", false);

    /** 持久化值（reconciliation_diff.diff_type 里存它）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    /** 是否允许自动补单。**唯一判据**，D 组直接用它，不另写判断。 */
    private final boolean autoRecoverable;

    ReconcileDiffType(String code, String label, boolean autoRecoverable) {
        this.code = code;
        this.label = label;
        this.autoRecoverable = autoRecoverable;
    }

    private static final Map<String, ReconcileDiffType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ReconcileDiffType::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code reconciliation_diff.diff_type 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 401001（参数错误）
     */
    public static ReconcileDiffType of(String code) {
        ReconcileDiffType type = code == null ? null : BY_CODE.get(code);
        if (type == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "未知对账差错类型: " + code);
        }
        return type;
    }

    /**
     * 按持久化值反查，值非法返回 null（筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static ReconcileDiffType ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 该类型中文名的 i18n key。
     *
     * <p>code 是下划线（`channel_only`），i18n key 用 camelCase（`channelOnly`）——
     * 与《i18n 规范》§3「key 段一律 camelCase」一致。</p>
     *
     * @return 形如 {@code trade.reconcile.diffType.channelOnly}
     */
    public String i18nKey() {
        StringBuilder camel = new StringBuilder();
        for (String part : code.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            camel.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        String camelCase = camel.isEmpty() ? code : Character.toLowerCase(camel.charAt(0)) + camel.substring(1);
        return "trade.reconcile.diffType." + camelCase;
    }
}
