/*
 * 职责：资料接口 `GET /app/account/profile` 的出参——在登录响应 user 对象之上多两个字段。
 * 调用方：modules/account/controller/ProfileController（由 ProfileService 组装）。
 * 数据来源：app_user + lawyer_application 最新一条 + balance_flow 汇总。
 * 注意：
 *   1. **继承 UserVO 而不是改 UserVO**：《接口规范》附录 A.1.1 的 user 对象是登录响应的冻结契约，
 *      往里加字段会改变登录响应。资料接口单独加可选字段属于契约允许的「新增可选字段」。
 *   2. `balanceCents` 走 `SUM(balance_flow.delta_cents)`（锚点 1-A6），**不读 user_balance 缓存**。
 *   3. `lawyerStatus` 取最新一条入驻申请的状态，从段 1 C 组起是真实值（不再是恒 NONE 的占位）。
 */
package com.faze.modules.account.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资料出参（比登录响应的 user 对象多律师状态与余额）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProfileVO extends UserVO {

    /** 律师入驻状态：NONE / DRAFT / PENDING / APPROVED / REJECTED。 */
    private String lawyerStatus;

    /**
     * 账户余额（分），= SUM(balance_flow.delta_cents)。
     *
     * <p>与 `GET /app/wallet/balance` 的 `totalCents` 同口径。展示给用户的余额一律用这个口径，
     * 不读 `user_balance` 缓存字段（锚点 1-A6：缓存与流水不一致时，以流水为准）。</p>
     */
    private Long balanceCents;
}
