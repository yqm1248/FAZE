/*
 * 职责：payment_record 的数据访问（只追加）。
 * 调用方：modules/trade/service/OrderPayService（G2.2）、OrderRefundService（G2.4/G4.3）。
 * 数据来源：faze.payment_record。
 * 注意：
 *   1. **不提供 update / delete**：退款是加一条负数行，不是改原来的正数行。
 *   2. 幂等靠唯一键 `uk_payment_order_channel_txn`，插入冲突时调用方按"已写过"处理。
 *   3. 净收款口径：`SUM(amount_cents)`，正数收款 + 负数退款。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.PaymentRecord;

/**
 * 支付记录 Mapper。
 */
public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {
}
