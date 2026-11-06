/*
 * 职责：PC A-12 状态流转行（G9.2；来自 order_status_log，**不是** orders 的四个时间戳）。
 * 调用方：modules/trade/admin/service/AdminOrderService → AdminOrderController#statusLog。
 * 数据来源：faze.order_status_log。
 * 注意：
 *   1. **必须从 `order_status_log` 读**：用 `orders` 的 created/paid/finished/cancelled 四个时间戳
 *      拼不出来——缺 `refunding` / `refund_rejected` 节点，`2-A6` 的退款链路会露馅
 *      （工作流 G9.2 第 2 条）。
 *   2. `operatorRole` 取值 `USER` / `LAWYER` / `SYSTEM` / `ADMIN`；`operatorId` 按角色解释
 *      （系统操作可能为 null，如超时关单）。
 *   3. 按 `created_at ASC` 正序返回：时间线从上到下是「过去 → 现在」，
 *      页面不需要再排序（排序权在服务端，避免两端各排一次）。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 订单状态流水行。
 */
@Data
public class AdminOrderStatusLogVO {

    /** 流水主键。 */
    private Long id;

    /** 订单主键。 */
    private Long orderId;

    /** 原状态（首条可能为 null）。 */
    private String fromStatus;

    /** 新状态。 */
    private String toStatus;

    /** 操作人类型：USER / LAWYER / SYSTEM / ADMIN（库内列名是 `operator_type`）。 */
    private String operatorType;

    /** 操作人 id（按角色解释；系统操作为 null）。 */
    private String operatorId;

    /** 变更原因。 */
    private String reason;

    /** 变更时间（UTC）。 */
    private LocalDateTime createdAt;
}
