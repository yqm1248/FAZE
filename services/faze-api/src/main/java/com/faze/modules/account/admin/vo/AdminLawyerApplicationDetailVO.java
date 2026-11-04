/*
 * 职责：A-05 律师入驻详情的出参（四步表单 + 审核流水）。
 * 调用方：modules/account/admin/service/AdminLawyerApplicationService、AdminLawyerApplicationController。
 * 数据来源：faze.lawyer_application（平铺列 + form_data 快照）、lawyer_application_log、本地存储签名地址。
 * 注意：
 *   1. 四步映射按 C3 定稿（LawyerApplicationService 注释第 1 条）：
 *      step1 个人资质 → realName / licenseNumber；step2 执业信息 → licenseImage / lawFirm；
 *      step3 擅长领域 → specialties；step4 服务说明 → bio。
 *   2. `licenseImageUrl` 是 **60 秒签名 URL**，key 为 null 时给 null。
 *   3. `logs` 按时间**正序**（从提交到最近一次审核），页面按流水线展示。
 */
package com.faze.modules.account.admin.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 律师入驻详情。
 */
@Data
public class AdminLawyerApplicationDetailVO {

    /** 申请主键。 */
    private Long id;

    /** 申请人用户主键。 */
    private Long applicantUserId;

    /** 申请人姓名。 */
    private String applicantName;

    /** 申请人脱敏手机号。 */
    private String phoneMasked;

    /** 状态：PENDING / APPROVED / REJECTED。 */
    private String status;

    /** 提交时间（UTC）。 */
    private LocalDateTime submittedAt;

    /** 审核时间（UTC）。 */
    private LocalDateTime reviewedAt;

    /** 驳回原因。 */
    private String rejectReason;

    /** 四步表单内容。 */
    private Form form;

    /** 审核流水（时间正序）。 */
    private List<LogItem> logs;

    /**
     * 四步表单内容。
     */
    @Data
    public static class Form {

        /** 真实姓名（step1）。 */
        private String realName;

        /** 执业证号（step1）。 */
        private String licenseNumber;

        /** 执业证影像签名 URL（step2，60 秒）。 */
        private String licenseImageUrl;

        /** 执业律所（step2）。 */
        private String lawFirm;

        /** 擅长领域（step3）。 */
        private List<String> specialties;

        /** 个人简介 / 服务说明（step4）。 */
        private String bio;
    }

    /**
     * 审核流水项。
     */
    @Data
    public static class LogItem {

        /** 动作：submit / approve / reject / resubmit / save_draft。 */
        private String action;

        /** 变更前状态。 */
        private String fromStatus;

        /** 变更后状态。 */
        private String toStatus;

        /** 操作人类型：user / admin / system。 */
        private String operatorType;

        /** 操作人主键。 */
        private Long operatorId;

        /** 理由 / 备注。 */
        private String reason;

        /** 发生时间（UTC）。 */
        private LocalDateTime createdAt;
    }
}
