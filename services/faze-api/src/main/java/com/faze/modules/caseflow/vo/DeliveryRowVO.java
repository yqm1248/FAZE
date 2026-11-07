/*
 * 职责：送达记录的行/详情出参（App 送达页与 PC 送达 Tab 共用）。
 * 调用方：modules/caseflow/controller/AppDeliveryController、admin/controller/AdminCaseController。
 * 数据来源：case_delivery + legal_case（案号/案由）+ document（文书名）+ lawyer（承办律师名）。
 * 注意：
 *   1. **同一个 VO 既做列表行也做回执**：回执要的 8 个字段（回执号 / 文书名 / 案由 / 承办律师 /
 *      送达方式 / 送达时间 / 签收时间 / 签收设备）全部在这个 VO 里，不另造 `DeliveryReceiptVO`
 *      ——多一个几乎重合的 VO 只会让两处字段慢慢漂移。
 *   2. 时间一律 UTC，前端按 +8 展示（接口规范 §5.5）。
 *   3. `documentTitle` 为 null 时前端显示占位，不在这里兜底成中文（i18n 归前端）。
 */
package com.faze.modules.caseflow.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 送达记录 VO。
 */
@Data
public class DeliveryRowVO {

    /** 送达记录 id。 */
    private Long id;

    /** 案件 id。 */
    private Long caseId;

    /** 平台案件单号（CS 开头）。 */
    private String caseNo;

    /** 案由。 */
    private String caseTitle;

    /** 文书 id。 */
    private Long documentId;

    /** 文书标题。 */
    private String documentTitle;

    /** 送达方式 code：platform / sms_pickup / mail。 */
    private String method;

    /** 状态：pending / signed。 */
    private String status;

    /** 回执号（SD 开头，发起送达时即生成）。 */
    private String receiptNo;

    /** 发起送达时间（UTC）。 */
    private LocalDateTime sentAt;

    /** 签收时间（UTC）；未签收为 null。 */
    private LocalDateTime signedAt;

    /** 签收设备（型号 + OS）；未签收为 null。 */
    private String signDevice;

    /** 承办律师姓名；未分配为 null。 */
    private String lawyerName;
}
