/*
 * 职责：refund_record 的数据访问。
 * 调用方：modules/trade/service/OrderRefundService、PC 后台退款审批（G4.2）。
 * 数据来源：faze.refund_record。
 * 注意：
 *   1. 继承 BaseMapper 即可，查询用 LambdaQueryWrapper（单表条件）。
 *   2. **审批只更新状态与审批字段**，不许改 `amount_cents`：退款金额一经申请就是事实，
 *      审批环节改金额等于绕过用户确认。
 *   3. 一单在同一时刻最多一条「进行中」的退款单（`applying` / `refunding` 阶段），
 *      历史驳回单保留（用户可以看驳回原因）。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.RefundRecord;

/**
 * 退款单 Mapper。
 */
public interface RefundRecordMapper extends BaseMapper<RefundRecord> {
}
