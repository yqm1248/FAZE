/*
 * 职责：account 管理端（A-03 / A-04）的实体 → 出参转换，以及脱敏 / 枚举映射的唯一入口。
 * 调用方：modules/account/admin/service/AdminUserService、AdminVerificationService。
 * 数据来源：无（纯转换；签名 URL 由 Service 生成后传入，Converter 不碰存储、不查库）。
 * 注意：
 *   1. **脱敏只在这里做一次**：手机号复用 C 端 AccountConverter.maskPhone（同一入口），
 *      姓名 / 信用代码 / 执业证号 / 银行卡尾号的掩码只在本类实现；别处再写一遍
 *      substring 就一定有一处漏（规范 §3.6）。
 *   2. **出参枚举一律大写**：库里 active/blocked/cancelled、none/pending/… 的小写
 *      到本类为止；VO 里不允许出现小写状态值。
 *   3. 不做校验、不查库、不抛业务异常——需要数据库信息的转换说明分层错了。
 *   4. 掩码只用于「展示脱敏」，**不用于安全判断**：真实值仍在库内，任何权限判断
 *      必须用原始字段（在 Service 里），不能拿掩码结果去比较。
 */
package com.faze.modules.account.admin.converter;

import com.faze.modules.account.admin.vo.AdminUserDetailVO;
import com.faze.modules.account.admin.vo.AdminUserRowVO;
import com.faze.modules.account.admin.vo.AdminVerificationDetailVO;
import com.faze.modules.account.converter.AccountConverter;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.EnterpriseAuth;
import com.faze.modules.account.entity.RealnameAuth;
import com.faze.modules.account.entity.UserDevice;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 账号域管理端转换器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminAccountConverter {

    private static final String ACCOUNT_ACTIVE = "active";
    private static final String ACCOUNT_BLOCKED = "blocked";
    private static final String ACCOUNT_CANCELLED = "cancelled";
    private static final String STATUS_NORMAL = "NORMAL";
    private static final String STATUS_BANNED = "BANNED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_NONE = "NONE";
    private static final String TYPE_REALNAME = "REALNAME";

    /** 手机号脱敏的唯一入口（C 端同一份实现）。 */
    private final AccountConverter accountConverter;

    /**
     * 用户实体 → 列表行。
     *
     * @param user 用户实体
     * @return 列表行；消费类字段本段固定 0（无 legal_order，段 2 接入）
     */
    public AdminUserRowVO toUserRow(AppUser user) {
        AdminUserRowVO vo = new AdminUserRowVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setPhoneMasked(accountConverter.maskPhone(user.getPhone()));
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setRealnameStatus(toRealnameStatus(user.getRealnameStatus()));
        vo.setAccountStatus(toAccountStatus(user.getStatus()));
        // 段 1 没有 legal_order 表（契约明确固定 0，段 2 接入）
        vo.setTotalConsumeCents(0L);
        vo.setOrderCount(0);
        vo.setCreatedAt(user.getCreatedAt());
        vo.setLastLoginAt(user.getLastLoginAt());
        return vo;
    }

    /**
     * 用户实体 + 设备 + 实名 → 详情。
     *
     * @param user     用户实体
     * @param devices  有效设备列表（已按最后活跃时间倒序）
     * @param realname 实名记录；从未提交时为 null
     * @return 详情
     */
    public AdminUserDetailVO toUserDetail(AppUser user, List<UserDevice> devices, RealnameAuth realname) {
        AdminUserDetailVO vo = new AdminUserDetailVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setPhoneMasked(accountConverter.maskPhone(user.getPhone()));
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setGender(user.getGender());
        vo.setBirthDate(user.getBirthDate());
        vo.setIsMinor(Boolean.TRUE.equals(user.getIsMinor()));
        vo.setLang(user.getLang());
        vo.setRole(user.getRole());
        vo.setRealnameStatus(toRealnameStatus(user.getRealnameStatus()));
        vo.setAccountStatus(toAccountStatus(user.getStatus()));
        vo.setCreatedAt(user.getCreatedAt());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setLastLoginIp(user.getLastLoginIp());
        vo.setDevices(devices == null ? List.of() : devices.stream().map(this::toDeviceBrief).toList());
        vo.setRealname(realname == null ? null : toRealnameBrief(realname));
        return vo;
    }

    /**
     * 实名实体 → 详情（A-04 realname 分支）。
     *
     * @param auth      实名记录
     * @param nickname  用户昵称
     * @param frontUrl  正面签名 URL（60 秒，可为 null）
     * @param backUrl   反面签名 URL（60 秒，可为 null）
     * @return 认证详情
     */
    public AdminVerificationDetailVO toRealnameDetail(RealnameAuth auth, String nickname,
                                                      String frontUrl, String backUrl) {
        AdminVerificationDetailVO vo = new AdminVerificationDetailVO();
        vo.setId(auth.getId());
        vo.setUserId(auth.getUserId());
        vo.setUserNickname(nickname);
        vo.setType(TYPE_REALNAME);
        vo.setRealname(auth.getRealName());
        vo.setIdCardMasked(auth.getIdCardMasked());
        vo.setFrontUrl(frontUrl);
        vo.setBackUrl(backUrl);
        vo.setStatus(toRealnameStatus(auth.getStatus()));
        vo.setRejectReason(auth.getRejectReason());
        vo.setSubmittedAt(auth.getCreatedAt());
        return vo;
    }

    /**
     * 企业实体 → 详情（A-04 enterprise 分支）。
     *
     * @param auth       企业认证记录
     * @param nickname   用户昵称
     * @param licenseUrl 营业执照签名 URL（60 秒，可为 null）
     * @return 认证详情
     */
    public AdminVerificationDetailVO toEnterpriseDetail(EnterpriseAuth auth, String nickname,
                                                        String licenseUrl) {
        AdminVerificationDetailVO vo = new AdminVerificationDetailVO();
        vo.setId(auth.getId());
        vo.setUserId(auth.getUserId());
        vo.setUserNickname(nickname);
        vo.setType("ENTERPRISE");
        vo.setCompanyName(auth.getCompanyName());
        vo.setCreditCode(maskCode(auth.getCreditCode()));
        vo.setLegalPerson(auth.getLegalPerson());
        vo.setLicenseUrl(licenseUrl);
        vo.setContactPhone(accountConverter.maskPhone(auth.getContactPhone()));
        vo.setStatus(toRealnameStatus(auth.getStatus()));
        vo.setRejectReason(auth.getRejectReason());
        vo.setSubmittedAt(auth.getCreatedAt());
        return vo;
    }

    /**
     * 账号状态：库内小写 → 契约大写枚举。
     *
     * @param raw active / blocked / cancelled（可为 null）
     * @return NORMAL / BANNED / CANCELLED；未知值原样大写（不静默变成 NORMAL）
     */
    public String toAccountStatus(String raw) {
        if (raw == null) {
            return STATUS_NORMAL;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case ACCOUNT_ACTIVE -> STATUS_NORMAL;
            case ACCOUNT_BLOCKED -> STATUS_BANNED;
            case ACCOUNT_CANCELLED -> STATUS_CANCELLED;
            default -> raw.toUpperCase(Locale.ROOT);
        };
    }

    /**
     * 实名状态：库内小写 → 契约大写枚举。
     *
     * @param raw none / pending / approved / rejected（可为 null）
     * @return NONE / PENDING / APPROVED / REJECTED
     */
    public String toRealnameStatus(String raw) {
        return raw == null ? STATUS_NONE : raw.toUpperCase(Locale.ROOT);
    }

    /**
     * 手机号脱敏（委托 C 端 AccountConverter，保证全项目一个实现）。
     *
     * @param phone 手机号原文
     * @return 形如 138****8888；不足 7 位返回空串
     */
    public String maskPhone(String phone) {
        return accountConverter.maskPhone(phone);
    }

    /**
     * 姓名掩码：保留首字符，其余打星。
     *
     * @param name 真实姓名
     * @return 形如 张**；null 或空白时返回空串
     */
    public String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        if (name.length() == 1) {
            return "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /**
     * 证照类编码掩码：保留前 4 与后 4，中间打星。
     *
     * @param code 信用代码 / 执业证号
     * @return 掩码；长度不足 8 时整体打星，null 时返回 null
     */
    public String maskCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (code.length() <= 8) {
            return "*".repeat(code.length());
        }
        return code.substring(0, 4) + "****" + code.substring(code.length() - 4);
    }

    /**
     * 银行卡号只留后 4 位。
     *
     * @param account 完整卡号
     * @return 后 4 位；空值返回 null
     */
    public String bankTail(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        return account.length() <= 4 ? account : account.substring(account.length() - 4);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 设备实体 → 摘要。
     *
     * @param device 设备实体
     * @return 摘要；deviceName 优先机型，回退平台
     */
    private AdminUserDetailVO.DeviceBrief toDeviceBrief(UserDevice device) {
        AdminUserDetailVO.DeviceBrief brief = new AdminUserDetailVO.DeviceBrief();
        brief.setDeviceId(device.getDeviceId());
        brief.setDeviceName(device.getDeviceModel() != null && !device.getDeviceModel().isBlank()
                ? device.getDeviceModel() : device.getPlatform());
        brief.setLastActiveAt(device.getLastActiveAt());
        return brief;
    }

    /**
     * 实名实体 → 摘要。
     *
     * @param auth 实名记录
     * @return 摘要
     */
    private AdminUserDetailVO.RealnameBrief toRealnameBrief(RealnameAuth auth) {
        AdminUserDetailVO.RealnameBrief brief = new AdminUserDetailVO.RealnameBrief();
        brief.setRealname(auth.getRealName());
        brief.setRealnameMasked(maskName(auth.getRealName()));
        brief.setIdCardMasked(auth.getIdCardMasked());
        brief.setStatus(toRealnameStatus(auth.getStatus()));
        brief.setRejectReason(auth.getRejectReason());
        return brief;
    }
}
