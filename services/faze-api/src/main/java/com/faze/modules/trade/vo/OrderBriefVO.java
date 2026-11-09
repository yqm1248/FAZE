/*
 * 职责：订单的**跨域只读最小投影**（会话准入与订单通知消息用）。
 * 调用方：modules/conversation/service/OrderNoticeService（经 trade 域 OrderQueryService 返回）。
 * 数据来源：orders 表，只取「有效订单」（status = paid / in_service）。
 * 注意：
 *   1. 不返回 `Orders` 实体：跨域返回整行会让调用方有机会「顺手改字段」（后端代码规范 §2.3），
 *      而会话域只需要「订单号 / 服务类型 / 金额」三个展示字段。
 *   2. `amountCents` 是整数分；「¥100.00」的展示由客户端按 i18n 规则做，服务端不格式化金额。
 *   3. 本类型只承载有效订单，不代表用户对该律师的全部历史订单。
 */
package com.faze.modules.trade.vo;

/**
 * 有效订单快照。
 *
 * @param orderId     订单主键（幂等键 `order-notice-{orderId}` 用它）
 * @param orderNo     业务单号（如 OR20261109001213）
 * @param serviceType 服务类型 code（consult_text / consult_phone / document / case）
 * @param amountCents 订单金额（分）
 */
public record OrderBriefVO(Long orderId, String orderNo, String serviceType, Long amountCents) {
}
