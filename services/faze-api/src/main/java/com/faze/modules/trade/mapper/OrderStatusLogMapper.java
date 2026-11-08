/*
 * 职责：order_status_log 的数据访问（只追加）。
 * 调用方：modules/trade/service/OrderStatusLogService、OrderServiceImpl、PC 后台状态流转列表（G9.2）。
 * 数据来源：faze.order_status_log。
 * 注意：
 *   1. **本 Mapper 只暴露 insert 与 select**：继承 BaseMapper 带来了 update/delete 的能力，
 *      业务代码一律不许调用（《数据表规范》B 类表只追加；库里该表的 DB 账号也应有
 *      无 UPDATE/DELETE 权限的约束，见 deploy 授权脚本）。
 *   2. 查历史按 (order_id, created_at) 走 `idx_osl_order`，不要按 order_no 查（表里没有单号列）。
 *   3. 本表没有软删字段，不存在 deleted_at 条件。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.OrderStatusLog;

/**
 * 订单状态迁移流水 Mapper。
 */
public interface OrderStatusLogMapper extends BaseMapper<OrderStatusLog> {
}
