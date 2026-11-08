/*
 * 职责：支付入参（`POST /app/orders/{id}/pay`，G2.2）。
 * 调用方：modules/trade/controller/OrderController#pay。
 * 数据来源：请求体（JSON）。
 * 注意：
 *   1. **只有支付密码，没有金额**：金额取订单已快照的 `amount_cents`，
 *      客户端传金额会被忽略（传了也不用）。
 *   2. 支付密码是 6 位数字（与段 1 的设置规则一致）；格式错误在这里就拒，
 *      不要让它走到 `PayPasswordService` 去消耗一次失败计数——
 *      用户手滑输 5 位不该算一次「密码错误」。
 *   3. **不要在这里校验「是不是本人订单」**：归属来自 token，Service 层按
 *      `user_id = ?` 查，查不到就是不存在。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 支付请求。
 */
@Data
public class PayBO {

    /** 支付密码（6 位数字，TLS 内明文传输）。 */
    @NotBlank(message = "payPassword 不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "支付密码必须是 6 位数字")
    private String payPassword;
}
