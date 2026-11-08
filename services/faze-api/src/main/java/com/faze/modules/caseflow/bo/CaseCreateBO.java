/*
 * 职责：在线立案（四步表单）的提交入参（`POST /app/cases`）。
 * 调用方：modules/caseflow/controller/AppCaseController → CaseService.create。
 * 数据来源：App 20-filing 页四步表单（设计稿字段原文）。
 * 注意：
 *   1. 四步字段**一次全量提交**，服务端把它们**原样冻结**进 `legal_case.form_snapshot`，
 *      之后页面怎么改都不动快照（§三 B1 第 4 条）。
 *   2. 身份证号只做格式校验（18 位），**不做校验位算法**（段 1 实名已做过，这里只收材料）。
 *   3. 证据是 `file_record` 的 key 数组，**不在本接口传文件流**（§三 B1「卡住怎么办」）。
 */
package com.faze.modules.caseflow.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 立案提交入参。
 */
@Data
public class CaseCreateBO {

    /** 第 1 步：案件类型（5 类 code：civil/criminal/administrative/labor/family）。 */
    @NotBlank(message = "案件类型不能为空")
    @Size(max = 30, message = "案件类型取值过长")
    private String caseType;

    /** 第 1 步：具体纠纷类型（设计稿 7 项，自由文本，≤50 字）。 */
    @Size(max = 50, message = "纠纷类型不能超过 50 字")
    private String disputeType;

    /** 第 1 步：事发地 / 合同履行地。 */
    @Size(max = 100, message = "事发地不能超过 100 字")
    private String location;

    /** 第 2 步：原告信息。 */
    @NotNull(message = "原告信息不能为空")
    @Valid
    private Plaintiff plaintiff;

    /** 第 2 步：被告信息。 */
    @Valid
    private Defendant defendant;

    /** 第 3 步：案情陈述（设计稿计数 0/2000）。 */
    @NotBlank(message = "案情陈述不能为空")
    @Size(max = 2000, message = "案情陈述不能超过 2000 字")
    private String statement;

    /** 第 3 步：诉讼请求。 */
    @Size(max = 2000, message = "诉讼请求不能超过 2000 字")
    private String claims;

    /** 第 3 步：争议金额（分）；不填为 null。 */
    @Min(value = 0, message = "争议金额不能为负")
    @Max(value = 999_999_999_99L, message = "争议金额超出上限")
    private Long amountCents;

    /** 第 4 步：证据 file_record 的 key 数组（最多 20 个）。 */
    @Size(max = 20, message = "证据材料最多 20 个")
    private List<@NotBlank @Size(max = 500) String> evidenceUrls;

    /**
     * 原告信息。
     */
    @Data
    public static class Plaintiff {

        /** 姓名。 */
        @NotBlank(message = "原告姓名不能为空")
        @Size(max = 50, message = "原告姓名不能超过 50 字")
        private String name;

        /** 身份证号（18 位，只校验格式）。 */
        @NotBlank(message = "身份证号不能为空")
        @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "身份证号格式不正确")
        private String idCard;

        /** 联系电话（11 位）。 */
        @NotBlank(message = "联系电话不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "联系电话格式不正确")
        private String phone;
    }

    /**
     * 被告信息。
     */
    @Data
    public static class Defendant {

        /** 姓名或单位名称。 */
        @Size(max = 100, message = "被告名称不能超过 100 字")
        private String name;

        /** 住址 / 注册地址。 */
        @Size(max = 200, message = "被告地址不能超过 200 字")
        private String address;
    }
}
