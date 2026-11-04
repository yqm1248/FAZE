/*
 * 职责：A-03 用户详情的出参（含设备列表与实名摘要）。
 * 调用方：modules/account/admin/service/AdminUserService、AdminUserController。
 * 数据来源：faze.app_user / user_device / realname_auth。
 * 注意：
 *   1. **不含完整手机号、不含身份证密文**：手机号只给 phoneMasked，身份证只给
 *      idCardMasked。契约明确「本段不提供完整手机号」。
 *   2. `realname` 为 null 表示该用户从未提交实名认证（不是「已提交但被删」）。
 *   3. `devices` 只给 deviceId / deviceName / lastActiveAt，**不给 pushCid**
 *      （推送标识属设备凭证，管理端展示用不到）。
 */
package com.faze.modules.account.admin.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 用户详情。
 */
@Data
public class AdminUserDetailVO {

    /** 用户主键。 */
    private Long id;

    /** 昵称。 */
    private String nickname;

    /** 脱敏手机号。 */
    private String phoneMasked;

    /** 头像地址。 */
    private String avatarUrl;

    /** 性别：0 未知 / 1 男 / 2 女。 */
    private Integer gender;

    /** 出生日期。 */
    private LocalDate birthDate;

    /** 是否未成年人。 */
    private Boolean isMinor;

    /** 语言偏好。 */
    private String lang;

    /** 角色：user / lawyer / customer_service / admin。 */
    private String role;

    /** 实名状态：NONE / PENDING / APPROVED / REJECTED。 */
    private String realnameStatus;

    /** 账号状态：NORMAL / BANNED / CANCELLED。 */
    private String accountStatus;

    /** 注册时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 最后登录时间（UTC）。 */
    private LocalDateTime lastLoginAt;

    /** 最后登录 IP。 */
    private String lastLoginIp;

    /** 有效设备列表（最后活跃时间倒序）。 */
    private List<DeviceBrief> devices;

    /** 实名摘要；从未提交时为 null。 */
    private RealnameBrief realname;

    /**
     * 设备摘要。
     */
    @Data
    public static class DeviceBrief {

        /** 客户端设备标识。 */
        private String deviceId;

        /** 设备名称（机型优先，回退平台）。 */
        private String deviceName;

        /** 最后活跃时间（UTC）。 */
        private LocalDateTime lastActiveAt;
    }

    /**
     * 实名摘要。
     */
    @Data
    public static class RealnameBrief {

        /** 真实姓名（审核需要，管理员可见）。 */
        private String realname;

        /** 姓名掩码，如 张*。 */
        private String realnameMasked;

        /** 身份证掩码。 */
        private String idCardMasked;

        /** 状态：PENDING / APPROVED / REJECTED。 */
        private String status;

        /** 驳回原因。 */
        private String rejectReason;
    }
}
