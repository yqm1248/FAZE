/*
 * 职责：realname_auth 表的实体映射（实名认证，第 1 段 C1）。
 * 调用方：modules/account/mapper/RealnameAuthMapper、RealnameService。
 * 数据来源：V3__account_auth.sql 建表 + V9 加的 id_card_masked。
 * 注意：
 *   1. `idCardNumber` 存**密文**（CryptoService 加密），**不是明文**；
 *      `idCardMasked` 存掩码供列表与详情展示。任何地方**不许**把密文当明文用。
 *   2. `idCardFront` / `idCardBack` 存的是**对象 key**（如 private/realname/...jpg），
 *      不是 URL；展示时要换短时签名 URL（UploadService.downloadUrl）。
 *   3. 该表 A 类（有 created_at / updated_at），但没有 deleted_at——实名记录不做软删，
 *      换绑身份属另一段的事。
 */
package com.faze.modules.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 实名认证记录。
 */
@Data
@TableName("realname_auth")
public class RealnameAuth {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户主键；一对一（uk_realname_user）。 */
    private Long userId;

    /** 真实姓名（L1：出参脱敏，日志不打）。 */
    private String realName;

    /** 身份证号**密文**（AES-256-GCM，Base64）。 */
    private String idCardNumber;

    /** 身份证号掩码（110***********1234），供展示。 */
    private String idCardMasked;

    /** 身份证正面对象 key（私有）。 */
    private String idCardFront;

    /** 身份证反面对象 key（私有）。 */
    private String idCardBack;

    /** pending / approved / rejected。 */
    private String status;

    /** 驳回原因。 */
    private String rejectReason;

    /** 审核管理员 ID（faze_admin.sys_user.id）。 */
    private Long auditedBy;

    /** 审核时间（UTC）。 */
    private LocalDateTime auditedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
