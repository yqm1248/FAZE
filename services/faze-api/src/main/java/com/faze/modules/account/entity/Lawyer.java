/*
 * 职责：lawyer 表的实体映射（律师主数据，PC A-05 通过后写入、A-06 查询）。
 * 调用方：modules/account/mapper/LawyerMapper、modules/account/admin/service/AdminLawyer*Service。
 * 数据来源：V4__lawyer.sql 建的 lawyer（29 字段，逐字段对应，不增不减）。
 * 注意：
 *   1. **`balanceCents` 是历史缓存字段，不是律师余额的权威**：权威是 `lawyer_balance`
 *      再往上追到 `balance_flow`（见《数据表规范》与 Class LawyerBalance 注释）。
 *      本段（A-06）只做展示，不写这个字段。
 *   2. `deletedAt` 是软删除时间（NULL=未删）。段 1 没有配 MP 逻辑删除拦截器，
 *      所以**所有查询必须自己带 `isNull(deletedAt)`**。
 *   3. `specialties` 在库里是 JSON 数组，实体里按字符串承载；解析集中在
 *      AdminAccountConverter，Service 不直接读字符串。
 *   4. `serviceTextOn` / `servicePhoneOn` / `serviceDocOn` / `autoAccept` 是 tinyint(1)，
 *      用 Boolean 承载（取值 0/1）。插入时 null 字段会被 MP 省略，落库取 DEFAULT，符合预期。
 *   5. `withdrawBankAccount` 是完整卡号（L3 敏感）：出参只允许给后 4 位，
 *      本段不做提现业务，也不允许在日志里打印完整卡号。
 */
package com.faze.modules.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 律师主数据。
 */
@Data
@TableName("lawyer")
public class Lawyer {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的 C 端用户 app_user.id；一个用户最多一个律师（uk_lawyer_user）。 */
    private Long userId;

    /** 执业姓名。 */
    private String realName;

    /** 律师执业证号（uk_lawyer_license 唯一）。 */
    private String licenseNumber;

    /** 执业证影像对象 key（私有桶）。 */
    private String licenseImage;

    /** 执业律所。 */
    private String lawFirm;

    /** 执业年限。 */
    private Integer practiceYears;

    /** 执业地区。 */
    private String region;

    /** 擅长领域 JSON 数组，如 ["婚姻家事","合同纠纷"]。 */
    private String specialties;

    /** 个人简介。 */
    private String bio;

    /** 头像地址。 */
    private String avatarUrl;

    /** 图文咨询价格（分）。 */
    private Long consultPriceCents;

    /** 电话咨询价格（分）。 */
    private Long phonePriceCents;

    /** 文书代写价格（分）。 */
    private Long documentPriceCents;

    /** 图文咨询上架开关：1 上架 0 下架。 */
    private Boolean serviceTextOn;

    /** 电话咨询上架开关：1 上架 0 下架。 */
    private Boolean servicePhoneOn;

    /** 文书代写上架开关：1 上架 0 下架。 */
    private Boolean serviceDocOn;

    /** 自动接单开关：1 开 0 关。 */
    private Boolean autoAccept;

    /** 评分 0.0~5.0。 */
    private BigDecimal rating;

    /** 评价数。 */
    private Integer reviewCount;

    /** 累计接单数。 */
    private Integer orderCount;

    /** 在线状态：online / busy / offline。 */
    private String onlineStatus;

    /** 审核状态：pending / approved / rejected。 */
    private String auditStatus;

    /** 提现收款开户行。 */
    private String withdrawBankName;

    /** 提现收款卡号（L3：只允许出后 4 位）。 */
    private String withdrawBankAccount;

    /** 历史缓存余额（分），**不是权威**（权威见 lawyer_balance / balance_flow）。 */
    private Long balanceCents;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删除时间（UTC）；NULL 表示未删除。 */
    private LocalDateTime deletedAt;
}
