/*
 * 职责：A-03 用户管理（列表 / 详情 / 封禁 / 解封）的业务实现。
 * 调用方：modules/account/admin/controller/AdminUserController。
 * 数据来源：faze.app_user、faze.user_device、faze.realname_auth。
 * 注意：
 *   1. **每个 app_user / user_device 查询都显式带 `deleted_at IS NULL`**
 *      （段 1 没有 MP 逻辑删除拦截器，漏了就查出已注销用户）。
 *   2. 封禁 / 解封是**状态机 + 权限变更**：必须 CAS（`eq(status, 旧值)`）判断是否真的
 *      迁移成功，不能「先查后改」——并发下两个请求都会过校验。
 *      封禁还要把 `token_version + 1`，否则旧 access token 在 2 小时有效期内还能用
 *      （锚点要求「封禁后 App 端立刻被拒」）。
 *   3. 审计：`@OperationLog` 标在本类的写方法上，detail 存 before/after + 理由 +
 *      到期时间（until 本段不落库，只入审计）。
 *   4. `until` 只做记录：app_user 没有封禁到期列，自动解封属段 7 的处罚分级。
 *   5. 出参不含完整手机号、不含密码哈希、不含身份证密文（脱敏在 Converter）。
 */
package com.faze.modules.account.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.util.Pages;
import com.faze.modules.account.admin.bo.AdminUserBanBO;
import com.faze.modules.account.admin.bo.AdminUserQueryBO;
import com.faze.modules.account.admin.bo.AdminUserUnbanBO;
import com.faze.modules.account.admin.converter.AdminAccountConverter;
import com.faze.modules.account.admin.vo.AdminUserDetailVO;
import com.faze.modules.account.admin.vo.AdminUserRowVO;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.RealnameAuth;
import com.faze.modules.account.entity.UserDevice;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.RealnameAuthMapper;
import com.faze.modules.account.mapper.UserDeviceMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理端用户服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private static final String MODULE = "account";
    private static final String TARGET_TYPE = "APP_USER";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_CANCELLED = "cancelled";
    /** 完整手机号长度（11 位）；其它长度按后 4 位后缀匹配。 */
    private static final int PHONE_LENGTH_FULL = 11;
    private static final String ORDER_CREATED = "createdAt";
    private static final String ORDER_LAST_LOGIN = "lastLoginAt";
    private static final Set<String> REALNAME_STATUS = Set.of("NONE", "PENDING", "APPROVED", "REJECTED");
    private static final Set<String> ACCOUNT_STATUS = Set.of("NORMAL", "BANNED", "CANCELLED");

    private final AccountMapper accountMapper;
    private final UserDeviceMapper userDeviceMapper;
    private final RealnameAuthMapper realnameAuthMapper;
    private final AdminAccountConverter converter;
    private final JsonMapper jsonMapper;

    /**
     * 分页查询用户。
     *
     * <p>条件：昵称模糊、手机号（11 位精确 / 其它按后 4 位）、实名状态、账号状态、
     * 注册时间区间；排序键走白名单，非法值 401001。</p>
     *
     * @param query 查询条件
     * @return 分页结果（行对象已脱敏）
     * @throws BusinessException 401001 状态 / 排序键不在枚举白名单内
     */
    public IPage<AdminUserRowVO> list(AdminUserQueryBO query) {
        LambdaQueryWrapper<AppUser> wrapper = buildListWrapper(query);
        IPage<AppUser> page = accountMapper.selectPage(Pages.of(query.getPageNum(), query.getPageSize()), wrapper);
        return page.convert(converter::toUserRow);
    }

    /**
     * 查询用户详情（含设备与实名摘要）。
     *
     * @param userId 用户主键
     * @return 详情
     * @throws BusinessException 401004 用户不存在或已注销
     */
    public AdminUserDetailVO detail(Long userId) {
        AppUser user = accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getId, userId)
                .isNull(AppUser::getDeletedAt));
        if (user == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        List<UserDevice> devices = userDeviceMapper.selectList(Wrappers.<UserDevice>lambdaQuery()
                .eq(UserDevice::getUserId, userId)
                .isNull(UserDevice::getDeletedAt)
                .orderByDesc(UserDevice::getLastActiveAt));
        RealnameAuth realname = realnameAuthMapper.selectOne(Wrappers.<RealnameAuth>lambdaQuery()
                .eq(RealnameAuth::getUserId, userId));
        return converter.toUserDetail(user, devices, realname);
    }

    /**
     * 封禁用户。
     *
     * <p>前置：用户存在且未注销、当前状态 = active（否则 401006）。</p>
     * <p>副作用：`status='blocked'`、`token_version+1`（旧 token 立即失效）、
     * 写 operation_log（action=USER_BANNED，detail 含 before/after + 理由 + 到期时间）。</p>
     *
     * @param userId 用户主键
     * @param request 封禁请求（理由必填、until 可空）
     * @throws BusinessException 401004 用户不存在/已注销 / 401006 当前状态不允许封禁
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "USER_BANNED", targetType = TARGET_TYPE)
    public void ban(Long userId, AdminUserBanBO request) {
        // 任务 1：存在性 + 已注销判定（已注销按「不存在」处理，避免泄露注销账号信息）。
        AppUser user = requireUser(userId);
        if (STATUS_CANCELLED.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        // 任务 2：CAS 迁移 active → blocked，同时使旧 token 失效。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = accountMapper.update(null, Wrappers.<AppUser>lambdaUpdate()
                .set(AppUser::getStatus, STATUS_BLOCKED)
                .setSql("token_version = token_version + 1")
                .set(AppUser::getUpdatedAt, now)
                .eq(AppUser::getId, userId)
                .eq(AppUser::getStatus, STATUS_ACTIVE)
                .isNull(AppUser::getDeletedAt));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        // 任务 3：审计 before/after（until 只入审计，不落库）。
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", user.getStatus());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", STATUS_BLOCKED);
        after.put("tokenVersionInvalidated", true);
        after.put("reason", request.getReason());
        after.put("until", request.getUntil() == null ? null : request.getUntil().toString());
        AuditContext.mark(MODULE, "USER_BANNED", TARGET_TYPE, String.valueOf(userId),
                detailJson(before, after));
        log.info("用户已封禁 userId={}（操作人见 operation_log）", userId);
    }

    /**
     * 解封用户。
     *
     * <p>前置：用户存在且未注销、当前状态 = blocked（否则 401006）。</p>
     * <p>副作用：`status='active'`、写 operation_log（action=USER_UNBANNED）。</p>
     *
     * @param userId 用户主键
     * @param request 解封请求（理由必填）
     * @throws BusinessException 401004 用户不存在/已注销 / 401006 当前状态不允许解封
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "USER_UNBANNED", targetType = TARGET_TYPE)
    public void unban(Long userId, AdminUserUnbanBO request) {
        AppUser user = requireUser(userId);
        if (STATUS_CANCELLED.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        int rows = accountMapper.update(null, Wrappers.<AppUser>lambdaUpdate()
                .set(AppUser::getStatus, STATUS_ACTIVE)
                .set(AppUser::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(AppUser::getId, userId)
                .eq(AppUser::getStatus, STATUS_BLOCKED)
                .isNull(AppUser::getDeletedAt));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", user.getStatus());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", STATUS_ACTIVE);
        after.put("reason", request.getReason());
        AuditContext.mark(MODULE, "USER_UNBANNED", TARGET_TYPE, String.valueOf(userId),
                detailJson(before, after));
        log.info("用户已解封 userId={}", userId);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 取未注销用户，取不到就 401004。
     *
     * @param userId 用户主键
     * @return 用户实体
     * @throws BusinessException 401004
     */
    private AppUser requireUser(Long userId) {
        AppUser user = accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getId, userId)
                .isNull(AppUser::getDeletedAt));
        if (user == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return user;
    }

    /**
     * 构造列表查询条件（含枚举白名单与排序白名单）。
     *
     * @param query 查询条件
     * @return Wrapper
     * @throws BusinessException 401001 状态 / 排序键非法
     */
    private LambdaQueryWrapper<AppUser> buildListWrapper(AdminUserQueryBO query) {
        String realnameStatus = normalizeEnum(query.getRealnameStatus(), REALNAME_STATUS);
        String accountStatus = normalizeEnum(query.getAccountStatus(), ACCOUNT_STATUS);
        LambdaQueryWrapper<AppUser> wrapper = Wrappers.<AppUser>lambdaQuery()
                .isNull(AppUser::getDeletedAt)
                .like(StringUtils.hasText(query.getNickname()), AppUser::getNickname, query.getNickname());
        applyPhoneFilter(wrapper, query.getPhone());
        wrapper.eq(realnameStatus != null, AppUser::getRealnameStatus, realnameStatus)
                .eq(accountStatus != null, AppUser::getStatus, toDbAccountStatus(accountStatus))
                .ge(query.getBeginTime() != null, AppUser::getCreatedAt, query.getBeginTime())
                .le(query.getEndTime() != null, AppUser::getCreatedAt, query.getEndTime());
        applyOrder(wrapper, query.getOrderBy());
        return wrapper;
    }

    /**
     * 手机号过滤：11 位精确匹配，其它按后 4 位后缀匹配。
     *
     * @param wrapper Wrapper
     * @param phone   手机号或后 4 位
     */
    private void applyPhoneFilter(LambdaQueryWrapper<AppUser> wrapper, String phone) {
        if (!StringUtils.hasText(phone)) {
            return;
        }
        String value = phone.trim();
        if (value.length() == PHONE_LENGTH_FULL) {
            wrapper.eq(AppUser::getPhone, value);
        } else {
            wrapper.likeLeft(AppUser::getPhone, value);
        }
    }

    /**
     * 应用排序白名单（非法值直接 401001，绝不把用户输入拼进 SQL）。
     *
     * @param wrapper Wrapper
     * @param orderBy 排序键
     * @throws BusinessException 401001 白名单外
     */
    private void applyOrder(LambdaQueryWrapper<AppUser> wrapper, String orderBy) {
        String key = StringUtils.hasText(orderBy) ? orderBy.trim() : ORDER_CREATED;
        if (ORDER_CREATED.equals(key)) {
            wrapper.orderByDesc(AppUser::getCreatedAt).orderByDesc(AppUser::getId);
        } else if (ORDER_LAST_LOGIN.equals(key)) {
            wrapper.orderByDesc(AppUser::getLastLoginAt).orderByDesc(AppUser::getId);
        } else {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
    }

    /**
     * 校验并规范化枚举入参（大写，忽略大小写）。
     *
     * @param raw    原值
     * @param allows 允许的大写值集合
     * @return 规范化后的大写值；空值返回 null
     * @throws BusinessException 401001 不在白名单
     */
    private String normalizeEnum(String raw, Set<String> allows) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allows.contains(value)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return value;
    }

    /**
     * 账号状态大写 → 库内小写。
     *
     * @param status NORMAL / BANNED / CANCELLED（可空）
     * @return active / blocked / cancelled（可空）
     */
    private String toDbAccountStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "NORMAL" -> STATUS_ACTIVE;
            case "BANNED" -> STATUS_BLOCKED;
            default -> STATUS_CANCELLED;
        };
    }

    /**
     * 组装审计 detail JSON（用 Jackson 而不是手拼，避免引号 / 换行把 JSON 写坏）。
     *
     * @param before 变更前
     * @param after  变更后
     * @return JSON 字符串
     */
    private String detailJson(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        return jsonMapper.writeValueAsString(detail);
    }
}
