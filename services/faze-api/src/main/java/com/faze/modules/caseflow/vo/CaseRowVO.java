/*
 * 职责：我的案件列表行（`GET /app/cases`）。
 * 调用方：modules/caseflow/controller/AppCaseController、App 53-my-cases 页。
 * 数据来源：legal_case + case_progress（最近事件）+ lawyer（承办律师）。
 * 注意：
 *   1. 设计稿明确卡片**只有**：案由 + 平台单号 + 状态 + 律师 + 最近事件时间。
 *      **没有金额、没有受理法院、没有立案日期**（§三 B3 第 1 条），不要加。
 *   2. `latestEventAt` 取 case_progress 最大 step 的 created_at，不是 updated_at。
 */
package com.faze.modules.caseflow.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的案件列表行。
 */
@Data
public class CaseRowVO {

    /** 案件 id。 */
    private Long id;

    /** 平台单号（CS 开头）。 */
    private String caseNo;

    /** 案由（标题）。 */
    private String title;

    /** 状态 code（前端映射 pill 文案）。 */
    private String status;

    /** 承办律师姓名；未分配为 null。 */
    private String lawyerName;

    /** 承办律师头像 URL；未分配为 null。 */
    private String lawyerAvatarUrl;

    /** 最近事件时间（UTC）。 */
    private LocalDateTime latestEventAt;
}
