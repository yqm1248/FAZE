/*
 * 职责：Mock 账单的「差异注入」规格（第 6 段 D3/D4 验收专用）。
 * 调用方：infra/pay/reconcile/MockBillGenerator（读 sys_config 的 `reconcile.mock.inject` 反序列化）。
 * 数据来源：sys_config 的 JSON 文本；**生产永远为空**（真实渠道的账单不会被人为改动）。
 * 注意：
 *   1. **这是测试能力，不是业务能力**：真实渠道的账单是渠道给的，平台不生成也不改。
 *      这三个注入项只是为了在 Mock 通道下**可复现地**造出 D4 要求的三类差异，
 *      否则「差异识别」只能靠手工改数据库，验收不可重复。
 *   2. 三项分别对应三类差异：
 *      · `extraRows`   → 账单里多一条平台没有的交易 = `channel_only`；
 *      · `dropOrderNos`→ 账单里少一条平台有的成功单 = `local_only`；
 *      · `amountShifts`→ 账单金额与平台不等 = `amount_mismatch`。
 *   3. 所有字段可空：空对象等价于「不注入」，账单与平台数据完全一致。
 *   4. 注入只在 `MockBillGenerator` 里生效，**D4 的比对逻辑不知道注入的存在**——
 *      它读到的就是一份普通账单文件，这样验的才是真实比对链路。
 *   5. JSON 示例（写进 sys_config）：
 *      {"extraRows":[{"channelTxnId":"MOCKONLY001","orderNo":"RCMOCKONLY001",
 *        "amountCents":10000,"paidAt":"2026-11-07T02:00:00","status":"SUCCESS"}],
 *       "dropOrderNos":["RC20261107000001"],
 *       "amountShifts":{"RC20261107000002":-500}}
 *      `paidAt` 是 **UTC 的 ISO-8601**（与 `BillRow.paidAt` 同口径）。
 */
package com.faze.infra.pay.reconcile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * Mock 账单差异注入规格。
 */
@Data
public class BillInjection {

    /** 追加到账单的「平台没有」的行（造 channel_only）。 */
    private List<BillRow> extraRows;

    /** 从账单里剔除的商户单号（造 local_only）。 */
    private Set<String> dropOrderNos;

    /** 商户单号 → 金额偏移（分，可正可负），造 amount_mismatch。 */
    private Map<String, Long> amountShifts;

    /**
     * 是否为空（不注入任何差异）。
     *
     * @return true 表示三项全空
     */
    public boolean isEmpty() {
        return (extraRows == null || extraRows.isEmpty())
                && (dropOrderNos == null || dropOrderNos.isEmpty())
                && (amountShifts == null || amountShifts.isEmpty());
    }
}
