/*
 * 职责：下单入参（`POST /app/orders`，G2.1）。
 * 调用方：modules/trade/controller/OrderController#create。
 * 数据来源：请求体（JSON）。
 * 注意：
 *   1. **没有金额字段，也不许加**（工作流 G2.1 第 1 条）：金额由服务端按律师价格取，
 *      客户端传金额是最常见的篡改面。加一个 `amountCents` 字段就等于把改价权交给客户端。
 *   2. 幂等键走 HTTP 头 `Idempotency-Key`，不在 body 里（与段 1 充值的口径一致）。
 *   3. `remark` 长度上限 500 与 `orders.remark VARCHAR(500)` 对齐；超长在这里就拒，
 *      不要靠数据库截断（严格模式下直接报 500）。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 下单请求。
 */
@Data
public class OrderCreateBO {

    /** 律师主键（lawyer.id）。 */
    @NotNull(message = "lawyerId 不能为空")
    private Long lawyerId;

    /** 服务类型：consult_text / consult_phone / document。 */
    @NotBlank(message = "serviceType 不能为空")
    private String serviceType;

    /** 用户备注，可空。 */
    @Size(max = 500, message = "remark 最长 500 字")
    private String remark;
}
