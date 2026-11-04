/*
 * 职责：A-04 认证队列的行出参（两张表合并后的一行）。
 * 调用方：modules/account/mapper/AdminVerificationMapper（SQL 结果映射）、
 *         modules/account/admin/service/AdminVerificationService、AdminVerificationController。
 * 数据来源：faze.realname_auth ∪ faze.enterprise_auth。
 * 注意：
 *   1. **`id` 必须与 `type` 一起才能唯一定位**：两张表主键各自自增，值会重复。
 *   2. SQL 里已把 `type` 输出为大写 REALNAME/ENTERPRISE、`status` 用 UPPER() 输出，
 *      出参不再做大小写转换（避免两处口径）。
 *   3. `realnameMasked` 的语义按类型不同：实名=真实姓名，企业=企业名称；
 *      `idCardMasked` 同理：实名=身份证掩码，企业=统一社会信用代码掩码。
 *      这是契约 A.3 的合并队列口径，前端按 type 解释。
 *   4. `userNickname` 由 Service 批量补齐（不在 UNION SQL 里 join app_user：
 *      两张表各自 join 会让 SQL 与分页复杂度翻倍，收益为零）。
 */
package com.faze.modules.account.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 认证队列行。
 */
@Data
public class AdminVerificationRowVO {

    /** 认证记录主键（需配合 type 才能唯一定位）。 */
    private Long id;

    /** 认证类型：REALNAME / ENTERPRISE。 */
    private String type;

    /** 用户主键。 */
    private Long userId;

    /** 用户昵称（Service 补）。 */
    private String userNickname;

    /** 实名=真实姓名；企业=企业名称。 */
    private String realnameMasked;

    /** 实名=身份证掩码；企业=信用代码掩码。 */
    private String idCardMasked;

    /** 提交时间（UTC）。 */
    private LocalDateTime submittedAt;

    /** 状态：PENDING / APPROVED / REJECTED。 */
    private String status;

    /** 审核管理员 ID。 */
    private Long reviewedBy;

    /** 审核时间（UTC）。 */
    private LocalDateTime reviewedAt;

    /** 驳回原因。 */
    private String rejectReason;
}
