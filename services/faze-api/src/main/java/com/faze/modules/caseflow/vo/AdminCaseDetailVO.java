/*
 * 职责：PC 案件详情（`GET /admin/cases/{id}`），供 A-19 抽屉三个 Tab 使用。
 * 调用方：modules/caseflow/admin/controller/AdminCaseController。
 * 数据来源：legal_case（含 form_snapshot 原文）+ case_progress（独立接口取）。
 * 注意：
 *   1. `formSnapshot` **原样返回 JSON 字符串**，由前端按四步分组渲染；服务端不做二次加工。
 *   2. 证据图片给 `file_record` 的签名 URL（60 秒），**不返回 ossKey**。
 */
package com.faze.modules.caseflow.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * PC 案件详情。
 */
@Data
public class AdminCaseDetailVO {

    /** 案件 id。 */
    private Long id;

    /** 平台单号。 */
    private String caseNo;

    /** 案件类型 code。 */
    private String caseType;

    /** 状态 code。 */
    private String status;

    /** 案件标题。 */
    private String title;

    /** 四步表单快照（JSON 字符串原样）。 */
    private String formSnapshot;

    /** 证据 file_record key 数组（JSON 字符串原样）。 */
    private String evidenceUrls;

    /**
     * 证据图片的**短时签名地址**（段 5 B2：PC 抽屉「立案材料」Tab 直接可渲染）。
     *
     * <p>由服务端把 `evidence_urls` 里的对象键现签得到——既避免把 OSS key 暴露给前端，
     * 也避免前端拿 key 拼不出可访问地址。没有证据时为空数组。</p>
     */
    private java.util.List<String> evidenceImageUrls;

    /** 管辖法院。 */
    private String courtName;

    /** 法院案号。 */
    private String courtCaseNo;

    /** 驳回理由。 */
    private String rejectReason;

    /** 争议金额（分）。 */
    private Long amountCents;

    /** 承办律师 id。 */
    private Long lawyerId;

    /** 承办律师姓名。 */
    private String lawyerName;

    /** 提交时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
