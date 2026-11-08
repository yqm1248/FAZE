/*
 * 职责：手工充值入参（`POST /admin/wallet/recharge`，第 1 段 D4）。
 * 调用方：modules/trade/controller/AdminWalletController。
 * 数据来源：PC A-07 余额与充值页。
 * 注意：
 *   1. `bizNo` 是**线下转账凭证号**，必填——没有凭证号的充值等于凭空造钱，
 *      对账时说不清这笔钱从哪来。
 *   2. `reason` 必填（《接口规范》A.5）：谁批准、为什么充，必须留文字。
 *   3. `amountCents` 是**分**，正整数；上限由 sys_config 定（本段先服务层校验 > 0）。
 *   4. 幂等键走 HTTP 头 `Idempotency-Key`，不在 body 里——body 是业务内容，
 *      头是传输语义，混在一起会让「重放」看起来像「改内容」。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手工充值请求。
 */
@Data
public class RechargeBO {

    /** 目标用户主键。 */
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    /** 充值金额（分），必须 > 0。 */
    @NotNull(message = "金额不能为空")
    @Min(value = 1, message = "金额必须大于 0")
    private Long amountCents;

    /** 充值原因（必填，写清为什么充）。 */
    @NotBlank(message = "充值原因不能为空")
    @Size(max = 200, message = "原因过长")
    private String reason;

    /** 线下转账凭证号/流水号（必填，用于对账）。 */
    @NotBlank(message = "凭证号不能为空")
    @Size(max = 64, message = "凭证号过长")
    private String bizNo;

    /** 备注（可空）。 */
    @Size(max = 500, message = "备注过长")
    private String remark;
}
