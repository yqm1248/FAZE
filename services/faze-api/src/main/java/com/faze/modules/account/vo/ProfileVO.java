/*
 * 职责：资料接口 `GET /app/account/profile` 的出参——在登录响应 user 对象之上多两个字段。
 * 调用方：modules/account/controller/ProfileController。
 * 数据来源：app_user + （段 1 D 组起）余额流水汇总。
 * 注意：
 *   1. **继承 UserVO 而不是改 UserVO**：《接口规范》附录 A.1.1 的 user 对象是登录响应的冻结契约，
 *      往里加字段会改变登录响应。资料接口单独加可选字段属于契约允许的「新增可选字段」。
 *   2. `balanceCents` 段 1 B4 先固定 0，**必须到 D 组接完流水汇总才可信**；
 *      在接上之前不许让客户端把它当成真实余额展示（注释与 TODO 已写在字段上）。
 *   3. `lawyerStatus` 段 1 C 组才有律师数据，之前恒为 NONE。
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

    /** 律师状态：NONE / PENDING / APPROVED / REJECTED；段 1 C 组前恒为 NONE。 */
    private String lawyerStatus;

    /**
     * 账户余额（分）。
     *
     * <p>TODO(owner: D 组, scope: trade)：段 1 B4 先固定 0，D 组接余额时改为
     * 「SUM(balance_flow.delta_cents)」汇总，**不许直接读 user_balance 缓存**（锚点 1-A6）。</p>
     */
    private Long balanceCents;
}
