/*
 * 职责：结算批次状态枚举（3 态），数据库存 code 小写值。
 * 调用方：trade 的 SettlementService、PC A-13 律师结算页。
 * 数据来源：《第 5 段工作流》§零之二 第 1 项与 §三 A4 第 5 条。
 * 注意：
 *   1. **结算不碰钱**：`settled` 只表示「这批账已确认」，不代表发生过转账；
 *      动钱的唯一链路是提现（WithdrawStatus）。
 *   2. 合法迁移只有 2 条：pending→settled / pending→rejected；
 *      **settled 与 rejected 都是终态**，不许 reopen（要重算只能重新生成一个新批次）。
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
 * 结算批次状态。只描述批次记录，不描述资金。
 */
@Getter
public enum SettlementStatus {

    /** 待结算。批次生成后的初始态。 */
    PENDING("pending", "待结算"),

    /** 已结算。终态；**只改状态，不动余额、不写流水**。 */
    SETTLED("settled", "已结算"),

    /** 已驳回。终态；需说明原因，重新结算要生成新批次。 */
    REJECTED("rejected", "已驳回");

    /** 持久化值（lawyer_income_settlement.status 里存它）。 */
    private final String code;

    /** 给开发者/后台看的中文名；前端展示走 i18n。 */
    private final String label;

    SettlementStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, SettlementStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(SettlementStatus::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code lawyer_income_settlement.status 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 405025（状态非法；结算与提现共用「状态非法」语义）
     */
    public static SettlementStatus of(String code) {
        SettlementStatus status = code == null ? null : BY_CODE.get(code);
        if (status == null) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID, "未知结算状态: " + code);
        }
        return status;
    }

    /**
     * 按持久化值反查，值非法返回 null（筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static SettlementStatus ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否为终态。
     *
     * @return settled / rejected 返回 true
     */
    public boolean isTerminal() {
        return this == SETTLED || this == REJECTED;
    }

    /**
     * 该状态对应的 i18n key。
     *
     * @return 形如 {@code trade.settlement.status.settled}
     */
    public String i18nKey() {
        return "trade.settlement.status." + code;
    }
}
