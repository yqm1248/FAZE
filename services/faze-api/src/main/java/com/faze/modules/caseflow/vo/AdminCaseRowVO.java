/*
 * 职责：PC 案件列表行（`GET /admin/cases`）。
 * 调用方：modules/caseflow/admin/controller/AdminCaseController。
 * 数据来源：legal_case + case_progress（当前节点）+ lawyer。
 * 注意：
 *   1. 表格 8 列（§三 B6 第 3 条）：案件号 / 类型 / 当事人 / 承办律师 / 当前节点 / 立案时间 / 状态 / 操作。
 *   2. 「当前节点」取 case_progress 最大 step 的 title，**不在 legal_case 上加冗余字段**。
 *   3. 当事人姓名来自 `form_snapshot` 解析，不在主表冗余（隐私字段不进列表主表）。
 */
package com.faze.modules.caseflow.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * PC 案件列表行。
 */
@Data
public class AdminCaseRowVO {

    /** 案件 id。 */
    private Long id;

    /** 平台单号。 */
    private String caseNo;

    /** 案件类型 code。 */
    private String caseType;

    /** 原告姓名（来自快照）。 */
    private String plaintiffName;

    /** 被告姓名（来自快照）。 */
    private String defendantName;

    /** 承办律师姓名。 */
    private String lawyerName;

    /** 当前节点标题。 */
    private String currentNode;

    /** 立案提交时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 状态 code。 */
    private String status ;
}
