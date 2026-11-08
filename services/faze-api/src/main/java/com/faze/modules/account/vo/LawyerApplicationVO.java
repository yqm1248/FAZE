/*
 * 职责：律师入驻申请查询出参（`GET /app/lawyer-applications/current`）。
 * 调用方：modules/account/controller/LawyerApplicationController。
 * 数据来源：lawyer_application。
 * 注意：
 *   1. `status` 出参是大写枚举（DRAFT/PENDING/APPROVED/REJECTED/NONE），库内存小写。
 *   2. 四步字段全部回显，草稿态靠它「回显上次填到哪」。
 *   3. `licenseImage` 回的是 **key**，不是 URL；客户端要预览需再调 `/app/upload/url`。
 */
package com.faze.modules.account.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 律师入驻申请出参。
 */
@Data
public class LawyerApplicationVO {

    /** 申请主键（序列化为字符串）。 */
    private Long id;

    /** NONE / DRAFT / PENDING / APPROVED / REJECTED。 */
    private String status;

    /** 当前步骤 1~4。 */
    private Integer step;

    /** 真实姓名。 */
    private String realName;

    /** 执业证号。 */
    private String licenseNumber;

    /** 执业证影像 key。 */
    private String licenseImage;

    /** 执业律所。 */
    private String lawFirm;

    /** 擅长领域。 */
    private List<String> specialties;

    /** 个人简介。 */
    private String bio;

    /** 驳回原因。 */
    private String rejectReason;

    /** 提交时间（UTC）。 */
    private LocalDateTime submittedAt;

    /** 审核时间（UTC）。 */
    private LocalDateTime reviewedAt;
}
