/*
 * 职责：app_user 表的实体映射（C 端用户主表）。
 * 调用方：modules/account/mapper/AccountMapper、AuthService。
 * 数据来源：Flyway V1__baseline.sql 建的 app_user（19 个字段，逐字段对应，不增不减）。
 * 注意：
 *   1. **PC 后台管理员不在这张表**（那是 RuoYi 的 sys_user），两者不许混用。
 *   2. `deleted_at` 是软删除时间（NULL=未删）。段 0 没有配 MP 的逻辑删除拦截器，
 *      所以**每个查询都必须自己带 `isNull(deletedAt)`**——漏掉就会把已注销用户查出来。
 *      段 1 统一接 `@TableLogic` 时再改，改之前不许依赖「MP 会自动过滤」。
 *   3. `created_at` / `updated_at` 是 NOT NULL 且本段不做自动填充（没有插入业务数据的场景），
 *      段 1 起由 MetaObjectHandler 统一填，届时不要在每个 Service 里手工 set。
 *   4. 密码字段叫 `passwordHash`，**永远不许出现在任何 VO / 日志 / 响应里**。
 */
package com.faze.modules.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * app_user 表实体（19 字段，与表一一对应）。
 */
@Data
@TableName("app_user")
public class AppUser {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 手机号，登录账号，唯一。 */
    private String phone;

    /** 注销前的原手机号；注销时 phone 改为占位值释放唯一键，此处留原值供审计。 */
    private String originalPhone;

    /** BCrypt 密码哈希（60 字符）；第三方登录用户可为 null。**禁止外泄**。 */
    private String passwordHash;

    /**
     * 支付密码 BCrypt 哈希（6 位数字的 BCrypt 结果）；null 表示未设置。
     *
     * <p>**与登录密码分属两个字段**：共用一个哈希时改一个会静默改掉另一个，
     * 且两者的强度策略与锁定策略完全不同（见 V11 迁移注释）。</p>
     */
    private String payPassword;

    /** 昵称。 */
    private String nickname;

    /** 头像地址。 */
    private String avatarUrl;

    /** 0 未知 / 1 男 / 2 女。 */
    private Integer gender;

    /** 出生日期，用于未成年人识别。 */
    private LocalDate birthDate;

    /** user / lawyer / customer_service / admin。 */
    private String role;

    /** 语言偏好：zh-Hans / bo / ug-Arab-CN / kk-Arab-CN。 */
    private String lang;

    /** none / pending / approved / rejected（**小写**存库，出参转大写枚举）。 */
    private String realnameStatus;

    /** 不满 18 周岁即 true。 */
    private Boolean isMinor;

    /** 监护人手机号，未成年人必填。 */
    private String guardianPhone;

    /** 监护人同意时间。 */
    private LocalDateTime guardianConsentAt;

    /** active / blocked / cancelled。 */
    private String status;

    /** 凭证版本号；改密/换绑/注销时 +1，旧 token 立即失效（V7 新增）。 */
    private Integer tokenVersion;

    /** 最后登录时间（写 UTC）。 */
    private LocalDateTime lastLoginAt;

    /** 最后登录 IP。 */
    private String lastLoginIp;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /** 软删除时间；NULL 表示未删除。 */
    private LocalDateTime deletedAt;
}
