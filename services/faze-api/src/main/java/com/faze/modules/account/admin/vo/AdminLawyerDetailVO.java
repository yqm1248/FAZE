/*
 * 职责：A-06 律师详情的出参（列表全字段 + 服务设置 + 关联用户摘要）。
 * 调用方：modules/account/admin/service/AdminLawyerService、AdminLawyerController。
 * 数据来源：faze.lawyer、faze.app_user、本地存储签名地址。
 * 注意：
 *   1. `withdrawBankAccountTail` 只给卡号后 4 位（L3 敏感，规范 §10.2 与契约均要求）；
 *      完整卡号**不出接口、不进日志**。
 *   2. `licenseImageUrl` 是 60 秒签名 URL，key 为 null 时给 null。
 *   3. `user.realnameStatus` 用大写枚举（与 A-03 口径一致）。
 *   4. 本段**不做**违规 / 限流 / 下架 / 清退（段 7），因此没有这些字段。
 */
package com.faze.modules.account.admin.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 律师详情。
 */
@Data
public class AdminLawyerDetailVO {

    /** 律师主键。 */
    private Long id;

    /** 关联用户主键。 */
    private Long userId;

    /** 执业姓名。 */
    private String realName;

    /** 执业证号掩码。 */
    private String licenseNoMasked;

    /** 执业律所。 */
    private String lawFirm;

    /** 执业地区。 */
    private String region;

    /** 执业年限。 */
    private Integer practiceYears;

    /** 擅长领域。 */
    private List<String> specialties;

    /** 评分。 */
    private BigDecimal rating;

    /** 评价数。 */
    private Integer reviewCount;

    /** 累计接单数。 */
    private Integer orderCount;

    /** 审核状态：PENDING / APPROVED / REJECTED。 */
    private String auditStatus;

    /** 在线状态：online / busy / offline。 */
    private String onlineStatus;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 执业证影像签名 URL（60 秒）。 */
    private String licenseImageUrl;

    /** 个人简介。 */
    private String bio;

    /** 图文咨询价格（分）。<p>原生 long：金额必须是 JSON 数字，不是字符串。</p> */
    private long consultPriceCents;

    /** 电话咨询价格（分）。 */
    private long phonePriceCents;

    /** 文书代写价格（分）。 */
    private long documentPriceCents;

    /** 图文咨询上架开关。 */
    private Boolean serviceTextOn;

    /** 电话咨询上架开关。 */
    private Boolean servicePhoneOn;

    /** 文书代写上架开关。 */
    private Boolean serviceDocOn;

    /** 自动接单开关。 */
    private Boolean autoAccept;

    /** 提现卡号后 4 位。 */
    private String withdrawBankAccountTail;

    /** 关联用户摘要。 */
    private UserBrief user;

    /**
     * 关联用户摘要。
     */
    @Data
    public static class UserBrief {

        /** 用户主键。 */
        private Long id;

        /** 昵称。 */
        private String nickname;

        /** 脱敏手机号。 */
        private String phoneMasked;

        /** 实名状态：NONE / PENDING / APPROVED / REJECTED。 */
        private String realnameStatus;
    }
}
