/*
 * 职责：案件详情（`GET /app/cases/{id}`），供 54-case-progress 页主卡 + 底栏使用。
 * 调用方：modules/caseflow/controller/AppCaseController。
 * 数据来源：legal_case + lawyer（承办律师）+ case_progress（列表单独接口取）。
 * 注意：
 *   1. 主卡字段：单号 + 状态 + 信息行（案由 / 受理法院 / 立案日期 / 承办律师）。
 *      设计稿有「受理法院」，未分配律师时为空。
 *   2. **不返回 form_snapshot 全文**：那是 PC 审核用的材料细节，App 用户看自己的数据另开接口。
 *   3. 底部说明文案由前端固定写死：「FAZE- 开头的编号是平台业务单号，不是法院案号」。
 */
package com.faze.modules.caseflow.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 案件详情。
 */
@Data
public class CaseDetailVO {

    /** 案件 id。 */
    private Long id;

    /** 平台单号。 */
    private String caseNo;

    /** 案件标题（案由）。 */
    private String title;

    /** 案件类型 code（5 类）。 */
    private String caseType;

    /** 状态 code。 */
    private String status;

    /** 管辖法院。 */
    private String courtName;

    /** 法院案号（可能为空）。 */
    private String courtCaseNo;

    /** 承办律师姓名。 */
    private String lawyerName;

    /** 承办律师头像 URL。 */
    private String lawyerAvatarUrl;

    /** 承办律师 id（前端「联系律师」用）。 */
    private Long lawyerId;

    /** 立案提交时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 驳回理由（status=rejected 时有值）。 */
    private String rejectReason;

    /** 当前用户是否可推进节点（该案承办律师本人）。 */
    private Boolean canAdvance;

    /** 已补正次数（首提交不算）。供 54-case-progress 显示「还可补正 N 次」。 */
    private Integer resubmitCount;

    /**
     * 当前用户能否发起补正（重新提交）。
     *
     * <p>为 true 的条件同时满足三条：当前用户是委托人本人、状态为 rejected、补正次数未到上限。
     * **必须带「本人」条件**：本接口允许承办律师访问他人案件，只判 rejected 会让律师也拿到 true，
     * 而补正接口只接受本人（P06-TASKS §1.3）。</p>
     */
    private Boolean canResubmit;
}
