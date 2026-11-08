/*
 * 职责：充值单状态枚举（4 态），数据库存 code 小写值（recharge_record.status）。
 * 调用方：trade 的 RechargeService / RechargeStateMachine / D 组关单与补单任务 / PC A-09 Tab2。
 * 数据来源：《第 6 段工作流》§零之二 第 4 项（4 态 6 条迁移）与 §三 A2。
 * 注意：
 *   1. **三个终态一律不可再迁**（success / failed / closed）。晚到回调打到 closed 单上
 *      **不许原地改 success**，要走「新开补偿单 + 入账」（A2 第 4 条）——这是充值场景
 *      最危险的边界：钱真的到了渠道，本地单已关。
 *   2. 迁移合法性由 RechargeStateMachine 判定，**不许在业务代码里手写 if 判状态**。
 *   3. 与 WithdrawStatus 一样：实体字段存 String，枚举只做业务判定（项目既定风格，
 *      不实现 MP 的 IEnum——段 2 / 段 5 均如此）。
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
 * 充值单状态。迁移合法性由 RechargeStateMachine 判定。
 */
@Getter
public enum RechargeStatus {

    /** 待支付。下单成功后的初始态；此时 channel_txn_id 必为 NULL。 */
    PENDING("pending", "待支付"),

    /** 充值成功。回调验签通过并入账 / 主动查询确认已收款；终态。 */
    SUCCESS("success", "充值成功"),

    /** 渠道失败（用户取消、支付失败、渠道拒单）；只置状态、不入账；终态。 */
    FAILED("failed", "渠道失败"),

    /** 已关闭。超时未支付，由关单任务置位；只置状态、不入账；终态。 */
    CLOSED("closed", "已关闭");

    /** 持久化值（recharge_record.status 里存它）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    RechargeStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, RechargeStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(RechargeStatus::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code recharge_record.status 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 405015（充值单状态非法）
     */
    public static RechargeStatus of(String code) {
        RechargeStatus status = code == null ? null : BY_CODE.get(code);
        if (status == null) {
            throw new BusinessException(ErrorCode.TRADE_RECHARGE_STATE_INVALID, "未知充值状态: " + code);
        }
        return status;
    }

    /**
     * 按持久化值反查，值非法返回 null（列表筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static RechargeStatus ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否为终态。
     *
     * @return success / failed / closed 返回 true；只有 pending 不是终态
     */
    public boolean isTerminal() {
        return this != PENDING;
    }

    /**
     * 该状态中文名的 i18n key。
     *
     * @return 形如 {@code trade.recharge.status.pending}
     */
    public String i18nKey() {
        return "trade.recharge.status." + code;
    }
}
