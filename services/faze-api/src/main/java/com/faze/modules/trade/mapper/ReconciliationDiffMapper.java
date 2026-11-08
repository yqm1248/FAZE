/*
 * 职责：reconciliation_diff 的数据访问。
 * 调用方：trade 的 AdminReconciliationService。
 * 数据来源：faze.reconciliation_diff（V507 + V510）。
 * 注意：
 *   1. **没有 update / delete 的业务入口**：V1 只记录不处置（H1 第 4 条）。
 *      本 Mapper 只继承 BaseMapper 的 insert / select，调用方不许用来改 `status`。
 *   2. 去重靠调用方的「先查后插」：`uk_diff_day_channel_txn_type` 在 `channel_txn_id`
 *      为 NULL 时不拦重复（MySQL 唯一索引允许多行 NULL），恒等式差异正好落在这一类。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.ReconciliationDiff;

/**
 * 对账差错 Mapper。
 */
public interface ReconciliationDiffMapper extends BaseMapper<ReconciliationDiff> {
}
