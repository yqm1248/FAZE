/*
 * 职责：A-03 用户列表的行出参。
 * 调用方：modules/account/admin/service/AdminUserService（构造）、AdminUserController（TableDataInfo）。
 * 数据来源：faze.app_user。
 * 注意：
 *   1. **只有脱敏手机号**：列表页不提供完整号（接口规范附录 A.2 明文规定，
 *      要查完整号走详情 + 审计留痕）。
 *   2. `totalConsumeCents` / `orderCount` 本段固定 0：段 1 没有 `legal_order` 表，
 *      订单消费数据段 2 接入后再填。**不要**在这里假装从别处汇总。
 *   3. `accountStatus` 出参是**大写枚举** NORMAL / BANNED / CANCELLED（库内小写）。
 */
package com.faze.modules.account.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户列表行。
 */
@Data
public class AdminUserRowVO {

    /** 用户主键（序列化为字符串）。 */
    private Long id;

    /** 昵称。 */
    private String nickname;

    /** 脱敏手机号。 */
    private String phoneMasked;

    /** 头像地址。 */
    private String avatarUrl;

    /** 实名状态：NONE / PENDING / APPROVED / REJECTED。 */
    private String realnameStatus;

    /** 账号状态：NORMAL / BANNED / CANCELLED。 */
    private String accountStatus;

    /** 累计消费（分）；段 1 固定 0（无 legal_order），段 2 接入。
     * <p>用原生 long 而不是 Long：JacksonConfig 只把包装类 Long 转字符串，
     * 金额必须保持 JSON 数字（契约标注为 long）。</p> */
    private long totalConsumeCents;

    /** 累计订单数；段 1 固定 0（无 legal_order），段 2 接入。 */
    private Integer orderCount;

    /** 注册时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 最后登录时间（UTC）。 */
    private LocalDateTime lastLoginAt;
}
