/*
 * 职责：立案提交成功后的返回（`POST /app/cases`）。
 * 调用方：modules/caseflow/controller/AppCaseController、App 21-filing-done 页。
 * 数据来源：legal_case。
 * 注意：
 *   1. 页面必须显示「此为平台业务单号，**非人民法院案号**」提示（§三 B2 第 2 条），
 *      所以返回体只给 caseNo，**不给任何法院侧编号**。
 *   2. 幂等：同一 Idempotency-Key 重复提交返回**首次**的同一份结果。
 */
package com.faze.modules.caseflow.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 立案提交结果。
 */
@Data
public class CaseSubmitVO {

    /** 案件 id。 */
    private Long id;

    /** 平台单号（CS 开头）。 */
    private String caseNo;

    /** 提交时间（UTC）。 */
    private LocalDateTime submittedAt;
}
