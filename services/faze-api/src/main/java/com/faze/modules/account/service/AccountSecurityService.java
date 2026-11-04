/*
 * 职责：凭证变更（B3）——修改密码、忘记密码重置、换绑手机。
 * 调用方：modules/account/controller/AccountSecurityController（已登录的两个）、
 *         modules/account/controller/AuthController（忘记密码，未登录）。
 * 数据来源：faze.app_user；refresh token 存储；短信验证码。
 * 注意：
 *   1. **凭证变更必须让 token_version +1，并同时吊销该用户的全部 refresh token**。
 *      只做版本号：旧 refresh 仍能换出带新版本号的 access，等于没失效；
 *      只吊销 refresh：已签发的 access 还能用到过期。
 *   2. 改密后**给当前客户端签发新令牌**（B3 要求保留当前会话），其他设备被踢。
 *   3. 忘记密码是匿名操作，写不了 operation_log（operator_id NOT NULL），
 *      改用 login_log 留痕；已登录的改密/换绑走 @OperationLog 切面。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.properties.FazeJwtProperties;
import com.faze.modules.account.bo.ChangePhoneBO;
import com.faze.modules.account.bo.ResetPasswordBO;
import com.faze.modules.account.converter.AccountConverter;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.rule.PasswordPolicy;
import com.faze.modules.account.vo.TokenVO;
import com.faze.modules.trade.service.BalanceQueryService;
import com.faze.security.JwtTokenProvider;
import com.faze.security.RefreshTokenStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 凭证变更服务。
 */
@Service
@Slf4j
public class AccountSecurityService {

    private static final String LOGIN_TYPE_RESET = "reset";
    private static final String MODULE_ACCOUNT = "account";
    private static final String STATUS_CANCELLED = "cancelled";
    /** 注销占位号的最大长度，与 V8 后的 phone VARCHAR(40) 一致。 */
    private static final int PHONE_MAX_LENGTH = 40;

    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtTokenProvider tokenProvider;
    private final SmsCodeService smsCodeService;
    private final LoginLogService loginLogService;
    private final AccountConverter accountConverter;
    private final BalanceQueryService balanceQueryService;
    private final Duration refreshTtl;

    /**
     * 构造服务。
     *
     * @param accountMapper       用户 Mapper
     * @param passwordEncoder     BCrypt 编码器
     * @param refreshTokenStore   refresh token 存储
     * @param tokenProvider       JWT 签发器
     * @param smsCodeService      短信验证码
     * @param loginLogService     登录日志（忘记密码留痕）
     * @param accountConverter    脱敏转换
     * @param balanceQueryService 余额只读查询（注销前判断）
     * @param jwtProperties       JWT 配置
     */
    public AccountSecurityService(AccountMapper accountMapper,
                                  PasswordEncoder passwordEncoder,
                                  RefreshTokenStore refreshTokenStore,
                                  JwtTokenProvider tokenProvider,
                                  SmsCodeService smsCodeService,
                                  LoginLogService loginLogService,
                                  AccountConverter accountConverter,
                                  BalanceQueryService balanceQueryService,
                                  FazeJwtProperties jwtProperties) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenStore = refreshTokenStore;
        this.tokenProvider = tokenProvider;
        this.smsCodeService = smsCodeService;
        this.loginLogService = loginLogService;
        this.accountConverter = accountConverter;
        this.balanceQueryService = balanceQueryService;
        this.refreshTtl = Duration.ofDays(jwtProperties.refreshTtlDays());
    }

    /**
     * 修改密码（已登录）。
     *
     * <p>前置：旧密码正确、新密码满足强度且与旧密码不同。</p>
     * <p>副作用：更新 password_hash、token_version+1、吊销全部 refresh，
     * 并为当前客户端签发一对新令牌。</p>
     *
     * @param userId      当前用户
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     * @return 新令牌对
     * @throws BusinessException 402003 用户不存在 / 402001 旧密码错 / 402010 新密码弱 / 401001 新旧相同
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE_ACCOUNT, action = "CHANGE_PASSWORD", targetType = "APP_USER")
    public TokenVO changePassword(Long userId, String oldPassword, String newPassword) {
        // 任务 1：取用户（含软删判断）。
        AppUser user = requireUser(userId);
        // 任务 2：旧密码校验。已登录用户不是爆破入口，失败不累计登录锁。
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        // 任务 3：新密码强度，且不许与旧密码相同（否则等于没改）。
        PasswordPolicy.assertStrong(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        // 任务 4：更新哈希并递增凭证版本。
        int nextVersion = tokenVersionOf(user) + 1;
        applyPasswordAndVersion(userId, newPassword, nextVersion);
        // 任务 5：吊销全部 refresh（只加版本号不够，见类注释第 1 条）。
        refreshTokenStore.revokeAllByUser(userId);
        user.setTokenVersion(nextVersion);
        // 任务 6：给当前客户端换新令牌，旧 access 因版本不符立即失效。
        return issueTokenPair(user, nextVersion);
    }

    /**
     * 忘记密码（未登录）。
     *
     * <p>前置：手机号已注册、验证码有效。</p>
     * <p>副作用：更新 password_hash、token_version+1、吊销全部 refresh、写 login_log。
     * **不返回令牌**，用户需重新登录。</p>
     *
     * @param request 重置请求
     * @throws BusinessException 402005 验证码错 / 402010 密码弱 / 402001 未注册
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(ResetPasswordBO request) {
        String phone = request.getPhone();
        // 任务 1：消费验证码（校验通过即删除）。
        smsCodeService.verifyAndConsume(phone, request.getSmsCode());
        // 任务 2：密码强度。
        PasswordPolicy.assertStrong(request.getNewPassword());
        // 任务 3：必须已注册；未注册统一回「登录失败」，不给号码枚举接口。
        AppUser user = accountMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getPhone, phone)
                .isNull(AppUser::getDeletedAt));
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        // 任务 4：更新哈希、版本 +1、吊销 refresh。
        int nextVersion = tokenVersionOf(user) + 1;
        applyPasswordAndVersion(user.getId(), request.getNewPassword(), nextVersion);
        refreshTokenStore.revokeAllByUser(user.getId());
        // 任务 5：匿名操作写不了 operation_log（operator_id NOT NULL），写 login_log 留痕。
        loginLogService.recordSuccess(user.getId(), phone, LOGIN_TYPE_RESET, null, null, null);
    }

    /**
     * 换绑手机（已登录）。
     *
     * <p>前置：旧手机号验证码正确、新手机号验证码正确、新手机号未被占用。</p>
     * <p>副作用：更新 phone、token_version+1、吊销全部 refresh；写 operation_log。</p>
     *
     * @param userId  当前用户
     * @param request 换绑请求
     * @throws BusinessException 402003 用户不存在 / 402005 验证码错 / 402006 新号已占用 / 401001 新旧相同
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE_ACCOUNT, action = "CHANGE_PHONE", targetType = "APP_USER")
    public void changePhone(Long userId, ChangePhoneBO request) {
        AppUser user = requireUser(userId);
        String oldPhone = user.getPhone();
        String newPhone = request.getNewPhone();
        if (newPhone.equals(oldPhone)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        // 任务 1：旧号验证码——证明是本人操作；新号验证码——证明新号可用。
        // 两个都要，缺一个就能被「拿到登录态后换号」或「换成别人的号」。
        smsCodeService.verifyAndConsume(oldPhone, request.getOldSmsCode());
        smsCodeService.verifyAndConsume(newPhone, request.getNewSmsCode());
        // 任务 2：新号不能已被别人占用。
        AppUser occupied = accountMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getPhone, newPhone)
                .isNull(AppUser::getDeletedAt));
        if (occupied != null) {
            throw new BusinessException(ErrorCode.AUTH_PHONE_TAKEN);
        }
        // 任务 3：更新手机号与凭证版本，吊销全部 refresh。
        int nextVersion = tokenVersionOf(user) + 1;
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setPhone(newPhone);
        patch.setTokenVersion(nextVersion);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        accountMapper.updateById(patch);
        refreshTokenStore.revokeAllByUser(userId);
        // 任务 4：审计明细里手机号必须脱敏（L2 字段不许进日志/审计原文）。
        AuditContext.mark(MODULE_ACCOUNT, "CHANGE_PHONE", "APP_USER", String.valueOf(userId),
                "{\"before\":\"" + accountConverter.maskPhone(oldPhone)
                        + "\",\"after\":\"" + accountConverter.maskPhone(newPhone) + "\"}");
        log.info("换绑手机成功 userId={} {}", userId,
                accountConverter.maskPhone(oldPhone));
    }

    // ── 登出与注销（B5）───────────────────────────────────────────────────

    /**
     * 登出。
     *
     * <p>副作用：token_version +1、吊销该用户全部 refresh token，**所有设备下线**。</p>
     *
     * <p>为什么不是「只退当前设备」：access token 是无状态的，要做到「只废当前这张」
     * 必须维护黑名单（会无限增长）。B5 的验收口径是「登出后旧 token 失效」，
     * 用版本号一步到位；若将来产品要求单端登出，再引入带 TTL 的黑名单。</p>
     *
     * @param userId 当前用户
     * @throws BusinessException 402003 用户不存在或已软删
     */
    @Transactional(rollbackFor = Exception.class)
    public void logout(Long userId) {
        AppUser user = requireUser(userId);
        int nextVersion = tokenVersionOf(user) + 1;
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setTokenVersion(nextVersion);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        accountMapper.updateById(patch);
        refreshTokenStore.revokeAllByUser(userId);
        log.info("用户登出 userId={}", userId);
    }

    /**
     * 注销账号（立即生效，无冷静期）。
     *
     * <p>前置：账号存在且余额（含冻结）为 0。</p>
     * <p>副作用：status 置 cancelled、手机号改写为占位值释放唯一键（原号进 original_phone）、
     * token_version +1、吊销全部 refresh、写 operation_log。</p>
     *
     * <p>**不物理删除**：balance_flow、login_log 有财务与审计留痕要求，
     * 删行会让「这个号曾经发生过什么」永远查不到。</p>
     *
     * @param userId 当前用户
     * @throws BusinessException 402003 用户不存在 / 402008 已注销 / 401006 有余额不允许注销
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE_ACCOUNT, action = "CANCEL_ACCOUNT", targetType = "APP_USER")
    public void cancelAccount(Long userId) {
        AppUser user = requireUser(userId);
        if (STATUS_CANCELLED.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_CANCELLED);
        }
        // 任务 1：有余额（或冻结）不允许注销——注销后钱没地方退，用户会直接投诉。
        long available = balanceQueryService.getAvailableCents(userId);
        long frozen = balanceQueryService.getFrozenCents(userId);
        if (available > 0 || frozen > 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        // 任务 2：改写手机号为占位值，释放唯一键（裁决 #9：注销后号码可立即重新注册）。
        int nextVersion = tokenVersionOf(user) + 1;
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setOriginalPhone(user.getPhone());
        patch.setPhone(placeholderPhone(userId, user.getPhone()));
        patch.setStatus(STATUS_CANCELLED);
        patch.setTokenVersion(nextVersion);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        accountMapper.updateById(patch);
        refreshTokenStore.revokeAllByUser(userId);
        // 任务 3：审计明细只留脱敏号。
        AuditContext.mark(MODULE_ACCOUNT, "CANCEL_ACCOUNT", "APP_USER", String.valueOf(userId),
                "{\"phone\":\"" + accountConverter.maskPhone(user.getPhone()) + "\"}");
        log.info("账号已注销 userId={} {}", userId, accountConverter.maskPhone(user.getPhone()));
    }

    // ── 内部 ───────────────────────────────────────────────────────────────
    /**
     * 取用户并校验存在。
     *
     * @param userId 用户主键
     * @return 用户实体
     * @throws BusinessException 402003 不存在或已软删
     */
    private AppUser requireUser(Long userId) {
        AppUser user = accountMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return user;
    }

    /**
     * 写入新密码哈希与凭证版本。
     *
     * @param userId      用户主键
     * @param rawPassword 新密码明文
     * @param nextVersion 新版本号
     */
    private void applyPasswordAndVersion(Long userId, String rawPassword, int nextVersion) {
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setPasswordHash(passwordEncoder.encode(rawPassword));
        patch.setTokenVersion(nextVersion);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        accountMapper.updateById(patch);
    }

    /**
     * 签发一对新令牌。
     *
     * @param user   用户（角色取自实体）
     * @param version 新凭证版本号
     * @return 令牌对
     */
    private TokenVO issueTokenPair(AppUser user, int version) {
        TokenVO fresh = new TokenVO();
        fresh.setAccessToken(tokenProvider.createAccessToken(user.getId(), user.getRole(), version));
        fresh.setRefreshToken(refreshTokenStore.issue(user.getId(), refreshTtl));
        fresh.setExpiresIn((int) tokenProvider.getAccessTtlSeconds());
        return fresh;
    }

    /**
     * 生成注销占位手机号：`cancelled_<userId>_<原号>`。
     *
     * <p>三元组保证唯一（userId 本身唯一），且保留原号便于客服肉眼追溯。
     * 超长时截断——V8 已把列放宽到 40，正常 id 不会触发。</p>
     *
     * @param userId 用户主键
     * @param phone  原手机号
     * @return 占位手机号
     */
    private String placeholderPhone(Long userId, String phone) {
        String value = "cancelled_" + userId + "_" + phone;
        return value.length() <= PHONE_MAX_LENGTH ? value : value.substring(0, PHONE_MAX_LENGTH);
    }

    /**
     * 取凭证版本号（null 按 0）。
     *
     * @param user 用户
     * @return 版本号
     */
    private int tokenVersionOf(AppUser user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }
}
