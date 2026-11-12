/*
 * 职责：A-07 钱包概览的出参。
 * 调用方：modules/trade/admin/service/AdminWalletService、AdminWalletLedgerController。
 * 数据来源：faze.balance_flow（权威）+ faze.user_balance（仅 frozen）。
 * 注意：
 *   1. **`balanceCents` 必须来自 balance_flow 的 SUM(delta_cents)**，不是
 *      user_balance.available_cents（锚点 1-A6：手工改缓存后接口仍要返回流水汇总值）。
 *   2. `frozenCents` 只能读缓存：段 1 没有冻结流水可推，冻结额由 user_balance 承载。
 *   3. 金额字段一律**原生 long**：JacksonConfig 只把包装类 Long 转字符串，
 *      金额必须是 JSON 数字（前端按 (cents/100).toFixed(2) 展示）。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 管理端钱包概览。
 */
@Data
public class AdminWalletVO {

    /** 用户主键。 */
    private Long userId;

    /** 总余额（分，来自流水汇总）。 */
    private long balanceCents;

    /** 冻结余额（分，来自缓存；段 1 恒 0，段 2 订单占用后才有值）。 */
    private long frozenCents;

    /** 累计充值（分，biz_type=recharge 的净额）。 */
    private long totalRechargeCents;

    /** 累计消费（分，biz_type=consume 的绝对值）。 */
    private long totalConsumeCents;

    /** 最近一条流水时间（UTC）；无流水时 null。 */
    private LocalDateTime lastFlowAt;
}
