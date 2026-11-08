/*
 * 职责：A-05 律师入驻队列的行出参。
 * 调用方：modules/account/admin/service/AdminLawyerApplicationService、AdminLawyerApplicationController。
 * 数据来源：faze.lawyer_application + faze.app_user（昵称/手机号）。
 * 注意：
 *   1. `practiceYears` 本段**恒为 null**：`lawyer_application` 表没有执业年限列
 *      （契约明确「段 2 补」）。不要用别的字段冒充，也不要假装是 0。
 *   2. `licenseNoMasked` 只给掩码（前 4 + **** + 后 4），完整证号在详情里也不给
 *      ——本段审核只需要看影像与掩码核对，完整证号进 lawyer 表后才需要。
 *   3. 状态出参大写 PENDING / APPROVED / REJECTED（库内小写）。
 */
package com.faze.modules.account.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 律师入驻队列行。
 */
@Data
public class AdminLawyerApplicationRowVO {

    /** 申请主键。 */
    private Long id;

    /** 申请人用户主键。 */
    private Long applicantUserId;

    /** 申请人姓名。 */
    private String applicantName;

    /** 申请人脱敏手机号。 */
    private String phoneMasked;

    /** 执业证号掩码。 */
    private String licenseNoMasked;

    /** 执业律所。 */
    private String lawFirm;

    /** 执业年限；本段恒 null（表无此列，段 2 补）。 */
    private Integer practiceYears;

    /** 提交时间（UTC）。 */
    private LocalDateTime submittedAt;

    /** 状态：PENDING / APPROVED / REJECTED。 */
    private String status;

    /** 审核时间（UTC）。 */
    private LocalDateTime reviewedAt;

    /** 驳回原因。 */
    private String rejectReason;
}
