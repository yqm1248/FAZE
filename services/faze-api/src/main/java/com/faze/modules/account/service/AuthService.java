/*
 * 职责：账户域的业务逻辑——登录、刷新令牌（含单飞）、取资料。段 0 唯一有业务逻辑的 Service。
 * 调用方：modules/account/controller/AuthController、ProfileController。
 * 数据来源：MySQL faze.app_user；令牌由 JwtTokenProvider 签发、RefreshTokenStore 记录。
 * 注意：
 *   1. **Controller 不许注入 Mapper、不许写业务**（后端规范 §4），所以查库、校验、签发全在这里。
 *   2. **登录失败不区分「账号不存在」与「密码错」**，统一回 402001：区分会变成账号枚举接口
 *      （《错误码规范》§3.2 原文要求）。段 0 也不做时序侧信道防护（用户不存在时立刻返回 vs
 *      存在时跑一次 BCrypt，响应时间可区分），这是**已知且接受**的段 0 简化，记在 README 的
 *      「段 0 限制」里，段 1 接风控时补。
 *   3. **刷新单飞**：同一 refreshToken 并发刷新时只允许一个真正执行，其余等待并复用结果。
 *      不单飞会因轮换导致旧 token 失效、其余并发请求全部 401——这是客户端「多请求同时过期」
 *      场景下的必然故障（《接口规范》附录 A.1.2）。
 *   4. 段 0 的 refresh 状态在进程内，**进程重启即需重新登录**；段 1 迁 Redis 后消失。
 *   5. 时间统一按 UTC 写库（last_login_at），与 `serverTimezone=UTC` 的 JDBC URL 一致。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.faze.common.exception.BusinessException;
import com.faze.config.properties.FazeJwtProperties;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.bo.LoginBO;
import com.faze.modules.account.converter.AccountConverter;
import com.faze.modules.account.bo.RegisterBO;
import com.faze.modules.account.bo.SmsLoginBO;
import com.faze.modules.account.rule.PasswordPolicy;
import com.faze.modules.account.bo.UpdateProfileBO;
import com.faze.modules.account.vo.LoginVO;
import com.faze.modules.account.vo.TokenVO;
import com.faze.modules.account.vo.UserVO;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.security.JwtTokenProvider;
import com.faze.security.RefreshTokenStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 账户服务。
 */
@Service
@Slf4j
public class AuthService {

    /**
     * 刷新结果复用窗口（毫秒）。
     *
     * <p><b>为什么是 5 秒而不是 30 秒</b>：这个窗口既是「并发等待者复用结果」的兜底，
     * 也是「旧 refresh token 还能兑换」的安全窗口。窗口越长，攻击者在合法用户刷新后
     * 重放旧 token 的可用时间越长。5 秒只够覆盖客户端并发抖动与一次网络重试，
     * 不足以支撑重放攻击。OAuth 2.0 BCP 要求的「重放检测 + 撤销整个 token family」
     * 需要持久化状态，属段 1 接 Redis 后的工作。</p>
     */
    private static final long REFRESH_REUSE_WINDOW_MS = 5_000L;

    /** 等待单飞结果的超时时间（秒）。超时说明创建者卡死，让客户端重试而不是无限等。 */
    private static final long REFRESH_WAIT_SECONDS = 10L;

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final String DEFAULT_ROLE = "user";
    private static final String DEFAULT_LANG = "zh-Hans";
    private static final String DEFAULT_REALNAME_STATUS = "none";
    private static final String LOGIN_TYPE_PASSWORD = "password";
    private static final String LOGIN_TYPE_SMS = "sms";
    private static final String LOGIN_TYPE_REGISTER = "register";

    private final AccountMapper accountMapper;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final PasswordEncoder passwordEncoder;
    private final SmsCodeService smsCodeService;
    private final LoginGuardService loginGuardService;
    private final LoginLogService loginLogService;
    private final AccountConverter accountConverter;
    private final RealnameStatusResolver realnameStatusResolver;
    private final DeviceService deviceService;
    private final Duration refreshTtl;

    /** 单飞槽位：refreshToken → 正在执行/已完成的轮换。 */
    private final Map<String, RefreshingEntry> refreshing = new ConcurrentHashMap<>();

    /** 账号风控（第 7 段 B5 接入；旁路能力，失败不影响登录/注册）。 */
    private final RiskControlService riskControlService;

    /**
     * 地区枚举（第 7 段尾巴：注册页采集 `app_user.region`，校验白名单）。
     */
    private final RegionOptionsService regionOptionsService;

    /**
     * 构造服务。
     *
     * @param accountMapper     用户 Mapper
     * @param tokenProvider     JWT 签发器
     * @param refreshTokenStore refresh token 存储
     * @param passwordEncoder   BCrypt 编码器
     * @param smsCodeService    短信验证码（注册 / 短信登录校验）
     * @param loginGuardService 登录失败计数与锁定
     * @param loginLogService   登录日志
     * @param riskControlService 账号风控（异常注册检测 / 新设备提醒）
     * @param regionOptionsService 地区枚举（注册时的白名单校验）
     * @param accountConverter 用户实体 → 出参（手机号脱敏唯一入口）
     * @param realnameStatusResolver 实名状态派生（DRIFT-015：真相源是 realname_auth）
     * @param jwtProperties     JWT 配置（取 refresh 有效期）
     */
    public AuthService(AccountMapper accountMapper,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenStore refreshTokenStore,
                       PasswordEncoder passwordEncoder,
                       SmsCodeService smsCodeService,
                       LoginGuardService loginGuardService,
                       LoginLogService loginLogService,
                       AccountConverter accountConverter,
                       RealnameStatusResolver realnameStatusResolver,
                       DeviceService deviceService,
                       RiskControlService riskControlService,
                       RegionOptionsService regionOptionsService,
                       FazeJwtProperties jwtProperties) {
        this.accountMapper = accountMapper;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.passwordEncoder = passwordEncoder;
        this.smsCodeService = smsCodeService;
        this.loginGuardService = loginGuardService;
        this.loginLogService = loginLogService;
        this.accountConverter = accountConverter;
        this.realnameStatusResolver = realnameStatusResolver;
        this.deviceService = deviceService;
        this.riskControlService = riskControlService;
        this.regionOptionsService = regionOptionsService;
        this.refreshTtl = Duration.ofDays(jwtProperties.refreshTtlDays());
    }

    // ── 登录 ───────────────────────────────────────────────────────────────

    /**
     * 手机号 + 密码登录。
     *
     * <p>前置：账号未处于登录锁定期（连错达到阈值后锁 15 分钟）。</p>
     * <p>副作用：更新 app_user 的 last_login_at / last_login_ip；写 login_log；
     * 在 RefreshTokenStore 里登记新 refresh token。</p>
     *
     * @param request        登录请求
     * @param deviceIdHeader 请求头 X-Device-Id（可为 null）
     * @param clientIp       客户端 IP
     * @param userAgent      User-Agent
     * @return 令牌 + 用户资料
     * @throws BusinessException 401007 锁定期内 / 402001 登录失败 / 402004 已封禁 / 402008 已注销
     */
    public LoginVO login(LoginBO request, String deviceIdHeader, String clientIp, String userAgent) {
        String phone = request.getPhone();
        // 任务 1：设备标识一致性。段 0 只记日志不拦（无风控规则时拦会误伤换机用户）。
        warnIfDeviceMismatch(deviceIdHeader, request.getDeviceId());

        // 任务 2：锁定期内直接拒绝，不再跑 BCrypt——锁定期间仍允许尝试等于没锁。
        loginGuardService.assertNotLocked(phone);

        // 任务 3：按手机号取未软删用户；查不到与密码错统一回 402001，防账号枚举。
        AppUser user = accountMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getPhone, phone)
                .isNull(AppUser::getDeletedAt));
        boolean badCredential = user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        if (badCredential) {
            loginGuardService.recordFailure(phone);
            loginLogService.recordFailure(phone, LOGIN_TYPE_PASSWORD, "账号或密码错误",
                    clientIp, userAgent, request.getDeviceId());
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }

        // 任务 4：状态校验放在凭据正确之后——封禁账号若也累计失败次数，
        // 攻击者可以用一个已知的封禁账号把号刷到锁定状态。
        ensureUsable(user);

        // 任务 5：成功则清失败计数、更新最后登录、写日志、登记设备。
        loginGuardService.clearFailure(phone);
        touchLogin(user.getId(), clientIp);
        loginLogService.recordSuccess(user.getId(), phone, LOGIN_TYPE_PASSWORD,
                clientIp, userAgent, request.getDeviceId());
        deviceService.touch(user.getId(), request.getDeviceId(), userAgent);

        // 任务 5b：新设备登录提醒（第 7 段 B5）。旁路能力，内部吞异常，不影响登录。
        riskControlService.notifyNewDeviceLogin(user.getId(), request.getDeviceId(), clientIp);

        // 任务 6：签发令牌对并组装响应。
        return issue(user);
    }

    // ── 注册 ───────────────────────────────────────────────────────────────

    /**
     * 手机号 + 验证码注册，成功后直接登录。
     *
     * <p>前置：验证码有效且未被使用；协议已勾选；未成年人必须填监护人手机号；
     * 地区必须命中 `sys_config.user.region_options` 白名单。</p>
     * <p>副作用：插入 app_user、写 login_log、登记 refresh token，三者同一事务。</p>
     *
     * <p>并发说明：两个请求同时注册同一手机号时，靠 app_user 的 uk_app_user_phone 拦，
     * 后到的那个抛 DuplicateKeyException，由 GlobalExceptionHandler 转 401005。</p>
     *
     * @param request   注册请求
     * @param clientIp  客户端 IP
     * @param userAgent User-Agent
     * @return 令牌 + 用户资料
     * @throws BusinessException 402005 验证码错 / 402009 缺监护人 / 402010 密码弱 / 402006 手机号已注册 /
     *                           401001 地区不在可选列表内
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterBO request, String clientIp, String userAgent) {
        String phone = request.getPhone();

        // 任务 1：协议必须勾选（合规要求，不能默认通过）。
        if (!Boolean.TRUE.equals(request.getAgreementAccepted())) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }

        // 任务 2：未成年人联动——勾选未成年必须填监护人手机号（负责人 2026-11-01 裁决 #10）。
        boolean minor = Boolean.TRUE.equals(request.getIsMinor());
        if (minor && !StringUtils.hasText(request.getGuardianPhone())) {
            throw new BusinessException(ErrorCode.AUTH_GUARDIAN_REQUIRED);
        }

        // 任务 2.5：所在地区必须在白名单内（第 7 段尾巴：注册页采集 app_user.region）。
        //
        // 白名单来自 sys_config.user.region_options（见 RegionOptionsService），**不写死在代码里**
        // ——将来加一个县只改配置、不发版。
        //
        // **为什么放在消费验证码之前**：与协议/监护人/密码同理。非法地区被拒是「用户改一下
        // 重试」的事，不该让验证码已经被消费掉、白等 60 秒（这个坑 1-A10 已经踩过一次，
        // 见任务 5 的注释）。返回的是 trim 后的规范值，落库用它而不是 request 原文——
        // 否则 " 伊宁市 " 会带空格进库，之后按地区统计/发公告的 IN 匹配永远对不上。
        String region = regionOptionsService.assertValid(request.getRegion());

        // 任务 3：密码强度（≥8 位且含字母与数字）。
        PasswordPolicy.assertStrong(request.getPassword());

        // 任务 4：手机号唯一检查（并发兜底见方法注释）。
        AppUser existing = accountMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getPhone, phone)
                .isNull(AppUser::getDeletedAt));
        if (existing != null) {
            throw new BusinessException(ErrorCode.AUTH_PHONE_TAKEN);
        }

        // 任务 5：消费验证码（校验通过即删除，防重放）。
        //
        // **必须放在所有前置校验之后**：这段原来是第一步，于是「勾选协议/填监护人/密码强度/
        // 手机号已注册」任何一项没过，验证码都已经被消费掉了——用户改完之后要重新等 60 秒
        // 才能再发码（同号发码间隔限制）。1-A10 的端到端验收就是这么踩出来的：
        // 未成年不填监护人 → 402009 被正确拒绝，但紧接着填好监护人重试 → 402005 验证码无效。
        // 消费放在这里仍然只消费一次，防重放语义不变。
        smsCodeService.verifyAndConsume(phone, request.getSmsCode());

        // 任务 6：落库。时间统一 UTC；token_version 从 0 起。
        AppUser user = new AppUser();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(defaultNickname(phone));
        user.setRole(DEFAULT_ROLE);
        user.setLang(DEFAULT_LANG);
        user.setRealnameStatus(DEFAULT_REALNAME_STATUS);
        user.setStatus(STATUS_ACTIVE);
        user.setIsMinor(minor);
        user.setGuardianPhone(minor ? request.getGuardianPhone() : null);
        user.setGuardianConsentAt(minor ? LocalDateTime.now(ZoneOffset.UTC) : null);
        // 地区：任务 2.5 已校验并规范化，这里直接落库（第 7 段尾巴）。
        user.setRegion(region);
        user.setTokenVersion(0);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        accountMapper.insert(user);

        // 任务 7：注册即首次登录——写日志并签发令牌。
        loginLogService.recordSuccess(user.getId(), phone, LOGIN_TYPE_REGISTER,
                clientIp, userAgent, null);

        // 任务 8：异常注册检测（第 7 段 B5）。只记录不拦（同 IP 24h 超阈写 user_violation），
        // 内部吞异常：风控失败不能让注册失败。
        riskControlService.checkAbnormalRegister(user.getId(), clientIp);

        return issue(user);
    }

    // ── 短信登录 ───────────────────────────────────────────────────────────

    /**
     * 手机号 + 验证码登录（**必须已注册**）。
     *
     * <p>前置：验证码有效且未被使用；账号已存在且状态可用。</p>
     * <p>副作用：更新最后登录时间、写 login_log、登记 refresh token。</p>
     *
     * @param request   短信登录请求
     * @param clientIp  客户端 IP
     * @param userAgent User-Agent
     * @return 令牌 + 用户资料
     * @throws BusinessException 402005 验证码错 / 402001 未注册 / 402004 已封禁 / 402008 已注销
     */
    public LoginVO loginBySms(SmsLoginBO request, String clientIp, String userAgent) {
        String phone = request.getPhone();

        // 任务 1：消费验证码。
        //
        // 这里**故意保持「先消费码、再查账号」**，与 register 相反：register 是合法用户
        // 被前置校验挡住时不该白扔一个验证码；而短信登录若先查账号，未持码的人就能靠
        // 402001/402005 的差异枚举「这个手机号有没有注册」。登录路径优先挡探测。
        smsCodeService.verifyAndConsume(phone, request.getSmsCode());

        // 任务 2：必须已注册——短信登录不做「首次登录即注册」，
        // 否则会绕过注册页的协议勾选与未成年人判定。
        AppUser user = accountMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getPhone, phone)
                .isNull(AppUser::getDeletedAt));
        if (user == null) {
            // 统一回「登录失败」而不是「未注册」：后者等于给出手机号枚举接口
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        ensureUsable(user);

        // 任务 3：更新最后登录、写日志、登记设备、签发。
        touchLogin(user.getId(), clientIp);
        loginLogService.recordSuccess(user.getId(), phone, LOGIN_TYPE_SMS,
                clientIp, userAgent, request.getDeviceId());
        deviceService.touch(user.getId(), request.getDeviceId(), userAgent);
        return issue(user);
    }


    // ── 刷新（单飞）────────────────────────────────────────────────────────

    /**
     * 用 refresh token 换一对新令牌（refresh 轮换）。
     *
     * <p>单飞语义：同一 token 的并发调用只会有一个真正执行轮换，其余等待并复用它的结果；
     * 执行完成后结果保留 {@value #REFRESH_REUSE_WINDOW_MS} 毫秒供「刚好晚到」的并发请求复用，
     * 超过这个窗口再用旧 token 刷新会得到 402003（**旧 token 已失效，不许重放**）。</p>
     *
     * @param refreshToken 旧 refresh token
     * @return 新令牌对
     * @throws BusinessException 402003 无效 / 402002 等待超时（让客户端重试）/ 402004 / 402008
     */
    public TokenVO refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        // 任务 1：清掉超过复用窗口的槽位，避免旧 token 长期可兑换。
        evictStaleSlots();

        // 任务 2：占槽。占不到说明别人正在刷（或刚刷完），等它的结果、绝不自己再轮换一次。
        RefreshingEntry mine = new RefreshingEntry(new CompletableFuture<>(), System.currentTimeMillis());
        RefreshingEntry existing = refreshing.putIfAbsent(refreshToken, mine);
        if (existing != null) {
            // 别人正在刷（或刚刷完）：等它，复用同一结果，绝不自己再轮换一次
            return awaitRefreshing(existing.future());
        }

        try {
            TokenVO result = doRefresh(refreshToken);
            mine.future().complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.future().completeExceptionally(e);
            throw e;
        }
    }

    // ── 资料 ───────────────────────────────────────────────────────────────

    // 资料查询已迁到 ProfileService（需要跨表组装律师状态与余额，AuthService 只管认证）。
    // 保留本段分隔注释是为了让「资料」这块在阅读时仍有落点，不要在这里再加资料方法。

    /**
     * 修改资料（只允许昵称 / 头像 / 性别）。
     *
     * <p>前置：用户存在且未软删。</p>
     * <p>副作用：更新 app_user 的对应字段与 updated_at；未传的字段保持原值。</p>
     *
     * @param userId  当前用户
     * @param request 修改请求（字段可空，只更新传了的）
     * @throws BusinessException 402003 用户不存在或已软删
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long userId, UpdateProfileBO request) {
        AppUser user = accountMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        // 用 patch 语义：MP 的 NOT_NULL 策略会忽略 null 字段，不会把没传的字段清空
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setNickname(request.getNickname());
        patch.setAvatarUrl(request.getAvatarUrl());
        patch.setGender(request.getGender());
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        accountMapper.updateById(patch);
    }

    // ── 内部实现 ───────────────────────────────────────────────────────────

    /**
     * 真正执行一次轮换：校验旧 token → 吊销 → 发新 refresh → 发新 access。
     *
     * @param refreshToken 旧 refresh token
     * @return 新令牌对
     */
    private TokenVO doRefresh(String refreshToken) {
        // 任务 1：校验旧 token——不存在 / 已过期 / 已被上一次轮换吊销，一律「无效」。
        RefreshTokenStore.RefreshRecord record = refreshTokenStore.find(refreshToken);
        if (record == null) {
            // 不存在 / 已过期 / 已被上一次轮换吊销 —— 一律「无效」，让客户端直接登出
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        // 任务 2：重新查用户，防止 token 仍有效但人已被软删 / 封禁。
        AppUser user = accountMapper.selectById(record.userId());
        if (user == null || user.getDeletedAt() != null) {
            // 任务 3：轮换——旧 token 立刻失效，防重放。
        refreshTokenStore.revoke(refreshToken);
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        ensureUsable(user);

        // 轮换：旧 token 立刻失效，防止重放
        refreshTokenStore.revoke(refreshToken);
        String newRefresh = refreshTokenStore.issue(user.getId(), refreshTtl);
        String newAccess = tokenProvider.createAccessToken(
                user.getId(), user.getRole(), tokenVersionOf(user));

        // 任务 4：签发并返回新令牌对。
        TokenVO response = new TokenVO();
        response.setAccessToken(newAccess);
        response.setRefreshToken(newRefresh);
        response.setExpiresIn((int) tokenProvider.getAccessTtlSeconds());
        return response;
    }

    /**
     * 签发一对新令牌并组装登录响应。
     *
     * @param user 已通过校验的用户
     * @return 登录响应
     */
    private LoginVO issue(AppUser user) {
        LoginVO response = new LoginVO();
        response.setAccessToken(tokenProvider.createAccessToken(
                user.getId(), user.getRole(), tokenVersionOf(user)));
        response.setRefreshToken(refreshTokenStore.issue(user.getId(), refreshTtl));
        response.setExpiresIn((int) tokenProvider.getAccessTtlSeconds());
        response.setTokenType(TOKEN_TYPE_BEARER);
        // 实名状态走派生（DRIFT-015）：读 realname_auth，不读 app_user.realname_status 缓存列
        response.setUser(accountConverter.toUserVO(user, realnameStatusResolver.resolve(user.getId())));
        return response;
    }

    /**
     * 账号状态校验。
     *
     * @param user 用户
     * @throws BusinessException 402004 已封禁 / 402008 已注销
     */
    private void ensureUsable(AppUser user) {
        String status = user.getStatus();
        if (STATUS_BLOCKED.equals(status)) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
        }
        if (STATUS_CANCELLED.equals(status)) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_CANCELLED);
        }
    }

    /**
     * 更新最后登录时间与 IP。
     *
     * <p>用 `updateById` 且只设两个字段：MP 的 NOT_NULL 策略会忽略其余 null 字段，
     * 不会把整行覆盖掉（这是很多人误以为「updateById 会写全字段」的地方）。</p>
     *
     * @param userId 用户主键
     * @param ip     客户端 IP
     */
    private void touchLogin(Long userId, String ip) {
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setLastLoginAt(LocalDateTime.now(ZoneOffset.UTC));
        patch.setLastLoginIp(ip);
        accountMapper.updateById(patch);
    }

    /**
     * 设备标识一致性检查（不一致时只记日志，不拦）。
     *
     * <p>段 0 没有风控表，《接口规范》A.1.1 要求「不一致记风控」，此处退化为 warn 日志；
     * 段 1 起接风控后改为按策略拦截。**故意不在此处拦截**：没有风控规则时拦截会造成
     * 正常用户（换机、设备 id 重置）被锁在门外。</p>
     *
     * @param headerDeviceId 请求头里的设备 id
     * @param bodyDeviceId   请求体里的设备 id
     */
    private void warnIfDeviceMismatch(String headerDeviceId, String bodyDeviceId) {
        if (headerDeviceId != null && !headerDeviceId.equals(bodyDeviceId)) {
            log.warn("设备标识不一致（段 0 仅记录，段 1 接风控）header={} body={}",
                    abbreviate(headerDeviceId), abbreviate(bodyDeviceId));
        }
    }

    /**
     * 等待别人完成的刷新。
     *
     * @param future 单飞槽位里的结果
     * @return 复用的令牌对
     */
    private TokenVO awaitRefreshing(CompletableFuture<TokenVO> future) {
        try {
            return future.get(REFRESH_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        } catch (TimeoutException e) {
            // 等不到别人的结果：回「过期可刷新」，客户端重试即可，不要直接登出
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }
    }

    /** 清理超出复用窗口的单飞槽位。 */
    private void evictStaleSlots() {
        long now = System.currentTimeMillis();
        refreshing.entrySet().removeIf(e -> now - e.getValue().createdAtMillis() > REFRESH_REUSE_WINDOW_MS);
    }


    /**
     * 取用户的凭证版本号（库里 NOT NULL DEFAULT 0，null 按 0 保守处理）。
     *
     * @param user 用户
     * @return 版本号
     */
    private int tokenVersionOf(AppUser user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    /**
     * 默认昵称：用户 + 手机号后 4 位。
     *
     * @param phone 手机号
     * @return 形如「用户0001」
     */
    private String defaultNickname(String phone) {
        return "用户" + phone.substring(phone.length() - 4);
    }

    /**
     * 日志里截断设备 id，避免把完整设备指纹写进日志。
     *
     * @param value 原始值
     * @return 前 6 位 + 省略号
     */
    private String abbreviate(String value) {
        return value.length() <= 6 ? value : value.substring(0, 6) + "...";
    }

    /**
     * 单飞槽位。
     *
     * @param future          轮换结果
     * @param createdAtMillis 槽位创建时间（毫秒），用于过期清理
     */
    private record RefreshingEntry(CompletableFuture<TokenVO> future, long createdAtMillis) {
    }
}
