/*
 * 职责：渠道对账单的一行（D3 解析器的统一输出，D4 比对的输入）。
 * 调用方：infra/pay/reconcile 的三个 ReconcileBillParser、modules/trade/service/ReconcileCompareService。
 * 数据来源：各渠道账单文件（Mock 是本地生成的 CSV；微信/支付宝是渠道下载的文件）。
 * 注意：
 *   1. **金额一律整数分**：解析器负责把账单里的「元」转成分（`BigDecimal`，禁 `double`，
 *      见 D3 第 6 条与 C2 第 4 条同一个坑）。
 *   2. `paidAt` 统一存 **UTC**：账单文件里是渠道当地时区（微信/支付宝都是东八区），
 *      解析器负责换算。Mock 账单也按东八区写、按东八区读回，与真实渠道同一口径。
 *   3. `orderNo` 是**商户单号**（我们传给渠道的 `recharge_record.order_no`），它是 D4
 *      逐笔比对的**主键**；`channelTxnId` 只做辅助与留痕。
 *   4. 渠道账单里没有用户信息（没有 `user_id`）。所以「本地完全无单」的差异行
 *      **不能自动补单**——不知道该给谁入账（见 ReconcileCompareService 的注释）。
 */
package com.faze.infra.pay.reconcile;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 渠道账单行。
 */
@Data
public class BillRow {

    /** 渠道交易号（渠道侧唯一流水号，如微信的 transaction_id）。 */
    private String channelTxnId;

    /** 商户单号（平台 `recharge_record.order_no`）。 */
    private String orderNo;

    /** 交易金额（分）。 */
    private long amountCents;

    /** 交易时间（UTC）。 */
    private LocalDateTime paidAt;

    /** 渠道侧状态：SUCCESS / FAILED / REFUND（统一大写，解析器负责映射）。 */
    private String status;

    /**
     * 该行是否代表「渠道确认收到钱」。
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }
}
