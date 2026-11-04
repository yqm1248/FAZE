/*
 * 职责：enterprise_auth 表的实体映射（企业认证，第 1 段 C2）。
 * 调用方：modules/account/mapper/EnterpriseAuthMapper、EnterpriseService。
 * 数据来源：V3__account_auth.sql 建的 enterprise_auth（13 字段）。
 * 注意：
 *   1. `licenseImage` 存**对象 key**（private/license/...），不是 URL；展示时换短时签名 URL。
 *   2. 统一社会信用代码不属 L1 加密字段（企业公开信息），但仍只做掩码展示，
 *      列表页见 PC A-05 的脱敏口径。
 *   3. 一对一（uk_enterprise_user），重复提交是 update。
 */
package com.faze.modules.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 企业认证记录。
 */
@Data
@TableName("enterprise_auth")
public class EnterpriseAuth {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户主键；一对一。 */
    private Long userId;

    /** 企业名称。 */
    private String companyName;

    /** 统一社会信用代码。 */
    private String creditCode;

    /** 法定代表人姓名。 */
    private String legalPerson;

    /** 营业执照对象 key（私有）。 */
    private String licenseImage;

    /** 联系电话。 */
    private String contactPhone;

    /** pending / approved / rejected。 */
    private String status;

    /** 驳回原因。 */
    private String rejectReason;

    /** 审核管理员 ID。 */
    private Long auditedBy;

    /** 审核时间（UTC）。 */
    private LocalDateTime auditedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
