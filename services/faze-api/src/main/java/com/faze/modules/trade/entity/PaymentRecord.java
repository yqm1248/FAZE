/*
 * 职责：payment_record 表的实体映射（支付记录，B 追加型）。
 * 调用方：modules/trade/mapper/PaymentRecordMapper、OrderPayService（G2.2 支付）、
 *         OrderRefundService（G2.4/G4.3 退款负行）。
 * 数据来源：V200__seg2_tables.sql 建的 payment_record（8 字段）。
 * 注意：
 *   1. **正数=收款、负数=退款**，使 `SUM(amount_cents) = 净收款`（段 2 拍板第 5 项）。
 *      退款写成一条独立的负数行，**不去改原来的正数行**——改行会毁掉证据链。
 *   2. 段 2 只有 `balance` 一种渠道；`transactionId = BAL-<balance_flow.id>`。
 *   3. 唯一键 `(order_id, channel, transaction_id)` 是支付/退款的幂等兜底：
 *      同一笔流水不可能写两条记录。
 *   4. 本表只追加：退款是加行不是改行。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 支付记录。
 */
@Data
@TableName("payment_record")
public class PaymentRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单主键。 */
    private Long orderId;

    /** 渠道：balance（段 2）/ wechat / alipay / mock（段 6 充值）。 */
    private String channel;

    /** 渠道流水号；余额支付记 BAL-&lt;balance_flow.id&gt;。 */
    private String transactionId;

    /** 金额（分）：正数=收款，负数=退款。 */
    private Long amountCents;

    /** 状态：success / failed / pending。 */
    private String status;

    /** 渠道原始回调报文（脱敏后）。 */
    private String rawPayload;

    /** 支付/退款完成时间（UTC）。 */
    private LocalDateTime paidAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
