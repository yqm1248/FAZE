/*
 * 职责：余额支付结果（G1.4 BalancePayAdapter 的出参）。
 * 调用方：trade 域的 OrderService（支付接口）。
 * 数据来源：DeductService.deduct 的返回值。
 * 注意：`duplicated=true` 是**成功**语义（重复支付未重复扣款），调用方按成功处理。
 */
package com.faze.modules.trade.vo;

/**
 * 余额支付结果。
 *
 * @param flowId            扣款流水主键
 * @param duplicated        是否幂等命中（本次没有重复扣款）
 * @param balanceAfterCents 变动后总余额（分）
 */
public record PayFromBalanceResult(Long flowId, boolean duplicated, long balanceAfterCents) {
}
