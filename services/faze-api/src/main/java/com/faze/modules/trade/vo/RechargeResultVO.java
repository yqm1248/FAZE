/*
 * 职责：手工充值/调账的结果出参（第 1 段 D4，接口规范 A.5 的冻结契约）。
 * 调用方：modules/trade/controller/AdminWalletController。
 * 数据来源：balance_flow + user_balance。
 * 注意：
 *   1. `balanceCents` 是**充值后总余额**（分）——PC 页面要立刻显示「充完还剩多少」。
 *   2. `bizNo` 原样回传：PC 用它和线下凭证核对，不能改格式。
 *   3. `duplicated=true` 表示这次请求命中了幂等，没有重复入账。
 *      这是成功语义，PC 页面应提示「该凭证已处理」而不是报错。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 充值结果。
 */
@Data
public class RechargeResultVO {

    /** 流水主键（序列化为字符串）。 */
    private Long flowId;

    /** 入账后总余额（分）。 */
    private Long balanceCents;

    /** 线下凭证号。 */
    private String bizNo;

    /** 入账时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 是否幂等命中（未重复入账）。 */
    private Boolean duplicated;
}
