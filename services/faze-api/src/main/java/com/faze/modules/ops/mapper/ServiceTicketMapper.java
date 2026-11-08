/*
 * 职责：service_ticket 的数据访问。
 * 调用方：modules/ops 的 TicketService（创建 / 流转 / 列表）。
 * 数据来源：faze.service_ticket。
 * 注意：
 *   1. 单表查询用 Wrapper；「处理中」用 `status IN ('waiting','processing')` 命中 `idx_ticket_status`。
 *   2. 超时判定不加 `overtime` 列，查询用 `sla_due_at < ? AND status IN (...)` 命中 `idx_ticket_sla`。
 *   3. 状态流转用 CAS（`WHERE id=? AND status=?`）防并发双改——两个管理员同时点「关闭」时
 *      只有一个成功，另一个影响行数 0。
 */
package com.faze.modules.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ops.entity.ServiceTicket;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客服工单 Mapper。
 */
@Mapper
public interface ServiceTicketMapper extends BaseMapper<ServiceTicket> {
}
