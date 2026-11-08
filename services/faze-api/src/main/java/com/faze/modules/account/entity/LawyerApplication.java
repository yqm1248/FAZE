/*
 * 职责：lawyer_application 表的实体映射（律师入驻申请，第 1 段 C3）。
 * 调用方：modules/account/mapper/LawyerApplicationMapper、LawyerApplicationService。
 * 数据来源：V4__lawyer.sql 建的 lawyer_application（16 字段）。
 * 注意：
 *   1. **四步映射**（文档缺口，此处补定并作为前后端唯一口径）：
 *      step1 个人资质 → real_name、license_number
 *      step2 执业信息 → license_image、law_firm
 *      step3 擅长领域 → specialties（JSON 数组）
 *      step4 服务说明 → bio
 *      另有 `form_data` 存**四步完整快照**，用于回显与审核留证；
 *      它与上面的平铺列冗余，是有意的：平铺列供查询/审核队列，form_data 供还原。
 *   2. `audit_status` 取值：`draft`（草稿）/ `pending`（已提交待审）/ `approved` / `rejected`。
 *      DDL 注释只列了后三个；`draft` 是本任务补的草稿态——没有它，草稿也会进 PC 待审队列。
 *   3. `step` 记录当前填到第几步（1~4），草稿态靠它回显。
 */
package com.faze.modules.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 律师入驻申请。
 */
@Data
@TableName("lawyer_application")
public class LawyerApplication {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人主键。 */
    private Long userId;

    /** 当前填写步骤 1~4。 */
    private Integer step;

    /** 四步表单完整快照（JSON）。 */
    private String formData;

    /** 真实姓名（step1）。 */
    private String realName;

    /** 律师执业证号（step1）。 */
    private String licenseNumber;

    /** 执业证影像对象 key（step2）。 */
    private String licenseImage;

    /** 执业律所（step2）。 */
    private String lawFirm;

    /** 擅长领域 JSON 数组（step3）。 */
    private String specialties;

    /** 个人简介/服务说明（step4）。 */
    private String bio;

    /** draft / pending / approved / rejected。 */
    private String auditStatus;

    /** 驳回原因。 */
    private String rejectReason;

    /** 审核管理员 ID。 */
    private Long auditedBy;

    /** 审核时间（UTC）。 */
    private LocalDateTime auditedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
