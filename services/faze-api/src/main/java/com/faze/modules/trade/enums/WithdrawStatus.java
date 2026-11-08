/*
 * 职责：提现单状态枚举（4 态），数据库存 code 小写值。
 * 调用方：trade 的 WithdrawService、PC A-10 提现审核页、App 66-income 提现记录。
 * 数据来源：《第 5 段工作流》§零之二 第 5 项（4 态裁决）与 §三 A4 第 4 条。
 * 注意：
 *   1. **4 态不合并**：「通过」与「出款完成」分开（applying → approved → paid）。
 *      真实打款由人工在网银操作后回来点「已出款」，平台不接企业付款接口（§八.2）。
 *   2. 合法迁移只有 4 条：applying→approved / applying→rejected / approved→paid；
 *      **rejected 与 paid 都是终态**。非法迁移抛 405025。
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
 * 提现单状态。迁移合法性由 WithdrawStateMachine 判定。
 */
@Getter
public enum WithdrawStatus {

    /** 待审核。律师提交提现申请后的初始态。 */
    APPLYING("applying", "待审核"),

    /** 已通过。审核通过，等待出款。 */
    APPROVED("approved", "已通过"),

    /** 已驳回。终态；驳回原因必填。 */
    REJECTED("rejected", "已驳回"),

    /** 已出款。终态；必须在点「已出款」前上传出款凭证。 */
    PAID("paid", "已出款");

    /** 持久化值（withdraw_record.status 里存它）。 */
    private final String code;

    /** 给开发者/后台看的中文名；前端展示走 i18n。 */
    private final String label;

    WithdrawStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, WithdrawStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(WithdrawStatus::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code withdraw_record.status 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 405025
     */
    public static WithdrawStatus of(String code) {
        WithdrawStatus status = code == null ? null : BY_CODE.get(code);
        if (status == null) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID, "未知提现状态: " + code);
        }
        return status;
    }

    /**
     * 按持久化值反查，值非法返回 null（筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static WithdrawStatus ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否为终态。
     *
     * @return rejected / paid 返回 true
     */
    public boolean isTerminal() {
        return this == REJECTED || this == PAID;
    }

    /**
     * 该状态对应的 i18n key。
     *
     * @return 形如 {@code trade.withdraw.status.paid}
     */
    public String i18nKey() {
        return "trade.withdraw.status." + code;
    }
}
