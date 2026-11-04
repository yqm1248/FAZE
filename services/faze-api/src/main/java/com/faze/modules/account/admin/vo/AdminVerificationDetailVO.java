/*
 * 职责：A-04 认证详情的出参。
 * 调用方：modules/account/admin/service/AdminVerificationService、AdminVerificationController。
 * 数据来源：faze.realname_auth / faze.enterprise_auth + 本地存储签名地址。
 * 注意：
 *   1. `frontUrl` / `backUrl` / `licenseUrl` 是**60 秒签名 URL**，由 FileStorage 签发；
 *      对象 key 为 null 时给 null（草稿未上传）。**绝不**把对象 key 拼进响应或日志。
 *   2. `idCardMasked` 只给掩码：本段不提供「查看完整身份证号」能力
 *      （契约明确本段掩码，完整号需单独权限 + 审计，属后续段）。
 *   3. 字段按类型择一有值：实名有 realname/idCardMasked/frontUrl/backUrl，
 *      企业有 companyName/creditCode/legalPerson/licenseUrl/contactPhone。
 */
package com.faze.modules.account.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 认证详情。
 */
@Data
public class AdminVerificationDetailVO {

    /** 认证记录主键。 */
    private Long id;

    /** 用户主键。 */
    private Long userId;

    /** 用户昵称。 */
    private String userNickname;

    /** 认证类型：REALNAME / ENTERPRISE。 */
    private String type;

    /** 真实姓名（实名）。 */
    private String realname;

    /** 身份证掩码（实名）。 */
    private String idCardMasked;

    /** 身份证正面签名 URL（实名，60 秒）。 */
    private String frontUrl;

    /** 身份证反面签名 URL（实名，60 秒）。 */
    private String backUrl;

    /** 企业名称（企业）。 */
    private String companyName;

    /** 统一社会信用代码（企业）。 */
    private String creditCode;

    /** 法定代表人（企业）。 */
    private String legalPerson;

    /** 营业执照签名 URL（企业，60 秒）。 */
    private String licenseUrl;

    /** 联系电话（企业）。 */
    private String contactPhone;

    /** 状态：PENDING / APPROVED / REJECTED。 */
    private String status;

    /** 驳回原因。 */
    private String rejectReason;

    /** 提交时间（UTC）。 */
    private LocalDateTime submittedAt;
}
