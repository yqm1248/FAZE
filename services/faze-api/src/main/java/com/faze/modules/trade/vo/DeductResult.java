/*
 * 职责：扣费结果（第 1 段 D3）。
 * 调用方：modules/trade/service/DeductService 的调用方。
 * 数据来源：扣费引擎内部。
 * 注意：
 *   1. `duplicated=true` 表示「这笔业务之前已经扣过，本次没有重复扣」——
 *      幂等命中是**成功**语义，不是错误，调用方应当按成功处理。
 *   2. `balanceAfterCents` 是**总余额**快照，与流水口径一致。
 */
package com.faze.modules.trade.vo;

/**
 * 扣费结果。
 *
 * @param duplicated       是否幂等命中（未重复扣费）
 * @param flowId           流水主键（首次或幂等命中对应的那条）
 * @param balanceAfterCents 变动后总余额（分）
 */
public record DeductResult(boolean duplicated, Long flowId, long balanceAfterCents) {
}
