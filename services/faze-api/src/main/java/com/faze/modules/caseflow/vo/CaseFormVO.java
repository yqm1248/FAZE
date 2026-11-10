/*
 * 职责：补正预填视图（`GET /app/cases/{id}/form`）——把立案四步表单的原样快照回吐给 App 预填。
 * 调用方：modules/caseflow/controller/AppCaseController → CaseService.form。
 * 数据来源：legal_case.form_snapshot（JSON 字符串，Jackson 3 反序列化）+ legal_case.reject_reason。
 * 注意：
 *   1. **只给委托人本人**（归属条件进 SQL，不是本人查不到即 407001）。本 VO 含身份证号与手机号
 *      原文，不得返回给承办律师或管理端之外的角色；管理端走 AdminCaseDetailVO，不复用本类。
 *   2. 为什么再建一套 VO 而不复用 `CaseCreateBO.Plaintiff`：BO 是入参（带 Bean Validation 约束），
 *      VO 是出参（不带校验、字段允许为空），两者生命周期与演进方向不同（后端规范 §3.5）。
 *   3. `amountCents` 声明为包装类 Long：全局 Jackson 配置会把它序列化成字符串（接口规范 §5.3），
 *      前端按字符串接；未填金额时为 null。
 *   4. `rejectReason` 不在快照里，由 Service 从 legal_case.reject_reason 单独塞入——快照是首次提交
 *      的冻结件，驳回理由只存在于主表。
 */
package com.faze.modules.caseflow.vo;

import lombok.Data;

import java.util.List;

/**
 * 补正预填视图。
 */
@Data
public class CaseFormVO {

    /** 案件类型 code（civil/criminal/administrative/labor/family）。 */
    private String caseType;

    /** 具体纠纷类型；未填为 null。 */
    private String disputeType;

    /** 事发地 / 合同履行地；未填为 null。 */
    private String location;

    /** 原告信息。 */
    private Plaintiff plaintiff;

    /** 被告信息；整个对象可为 null（原表单没填被告时快照里就没有它）。 */
    private Defendant defendant;

    /** 案情陈述。 */
    private String statement;

    /** 诉讼请求；未填为 null。 */
    private String claims;

    /** 争议金额（分）；未填为 null。序列化为字符串。 */
    private Long amountCents;

    /** 证据 file_record 的 key 数组；历史快照里可能没有该键，视为空列表由前端兜底。 */
    private List<String> evidenceUrls;

    /** 驳回理由（status=rejected 时有值）；前端补正页顶部提示条用。 */
    private String rejectReason;

    /**
     * 原告信息（只做出参，不带校验注解）。
     */
    @Data
    public static class Plaintiff {

        /** 姓名。 */
        private String name;

        /** 身份证号（18 位原文）。 */
        private String idCard;

        /** 联系电话（11 位原文）。 */
        private String phone;
    }

    /**
     * 被告信息（只做出参，不带校验注解）。
     */
    @Data
    public static class Defendant {

        /** 姓名或单位名称。 */
        private String name;

        /** 住址 / 注册地址。 */
        private String address;
    }
}
