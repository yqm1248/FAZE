/*
 * 职责：A-07 充值 / 调账的结果出参（`{flowId, balanceCents, bizNo, createdAt, duplicated}`）。
 * 调用方：modules/trade/admin/service/AdminWalletService、AdminWalletOpsController。
 * 数据来源：RechargeService 的入账结果。
 * 注意：
 *   1. `balanceCents` 是**审计口径的总余额**（可用 + 冻结），原生 long。
 *   2. `duplicated=true` 表示命中了幂等键、本次没有入账——这是成功语义，
 *      PC 页面应提示「该凭证已处理」，不是报错（契约与 D4 注释一致）。
 *   3. 不复用 RechargeResultVO：后者把 balanceCents 声明成包装类 Long，
 *      序列化后是字符串；本接口契约要求金额为数字，故单独建 VO（不改 D4 的既有出参）。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 管理端充值 / 调账结果。
 */
@Data
public class AdminRechargeResultVO {

    /** 流水主键（序列化为字符串）。 */
    private Long flowId;

    /** 入账后总余额（分，原生 long）。 */
    private long balanceCents;

    /** 线下凭证号；调账时为 null。 */
    private String bizNo;

    /** 入账时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 是否幂等命中（未重复入账）。 */
    private Boolean duplicated;
}
