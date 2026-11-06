/*
 * 职责：订单列表行（`GET /app/orders` 的 list 元素，G2.1）。
 * 调用方：modules/trade/controller/OrderController#list → APP「我的订单」页（29-orders.html）。
 * 数据来源：faze.orders 单行 + faze.lawyer 的姓名/头像（批量查，见 LawyerQueryService#mapByIds）。
 * 注意：
 *   1. **不给中文 label**（statusLabel / serviceTypeLabel）：状态文案与类型文案由前端按 code
 *      走 i18n，四语言都要翻译；服务端塞中文等于把 UI 文案硬编码进后端，RTL 语言直接没法翻。
 *   2. `amountCents` 是整数分，前端负责分转元展示（AGENTS.md 第 4 节第 5 条）。
 *   3. `payExpireAt` 由服务端算（`createdAt + 支付超时`），前端只做倒计时显示。
 *      前端自己拿 createdAt 加 15 分钟会让「超时时长改了但前端没跟」变成静默不一致。
 *   4. 仅 `PENDING` 状态时 `payExpireAt` 有值；其它状态为 null（客户端不显示倒计时）。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 订单列表行。
 */
@Data
public class OrderVO {

    /** 订单主键（Long 全局序列化为字符串）。 */
    private Long id;

    /** 业务单号。 */
    private String orderNo;

    /** 服务类型 code。 */
    private String serviceType;

    /** 订单状态 code。 */
    private String status;

    /** 订单金额（分）。 */
    private Long amountCents;

    /** 律师主键。 */
    private Long lawyerId;

    /** 律师姓名（订单快照上没有，按 lawyer.id 回查；律师已清退时为空）。 */
    private String lawyerName;

    /** 律师头像地址。 */
    private String lawyerAvatarUrl;

    /** 律师执业律所（列表卡片第二行「姓名 · 律所」）。 */
    private String lawyerFirm;

    /** 用户备注。 */
    private String remark;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 支付截止时间（UTC）；仅 PENDING 有值。 */
    private LocalDateTime payExpireAt;
}
