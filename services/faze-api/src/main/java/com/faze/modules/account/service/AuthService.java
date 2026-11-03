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
import com.faze.common.BusinessException;
import com.faze.common.ErrorCode;
import com.faze.modules.account.dto.LoginRequest;
import com.faze.modules.account.dto.LoginResponse;
import com.faze.modules.account.dto.TokenResponse;
import com.faze.modules.account.dto.UserVO;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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

    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final String DEFAULT_ROLE = "user";
    private static final String DEFAULT_REALNAME_STATUS = "none";

    private final AccountMapper accountMapper;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final PasswordEncoder passwordEncoder;
    private final Duration refreshTtl;

    /** 单飞槽位：refreshToken → 正在执行/已完成的轮换。 */
    private final Map<String, RefreshingEntry> refreshing = new ConcurrentHashMap<>();

    /**
     * 构造服务。
     *
     * @param accountMapper     用户 Mapper
     * @param tokenProvider     JWT 签发器
     * @param refreshTokenStore refresh token 存储
     * @param passwordEncoder   BCrypt 编码器
     * @param refreshTtlDays    refresh token 有效期（天），来自 {@code faze.jwt.refresh-ttl-days}
     */
    public AuthService(AccountMapper accountMapper,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenStore refreshTokenStore,
                       PasswordEncoder passwordEncoder,
                       @Value("${faze.jwt.refresh-ttl-days:30}") long refreshTtlDays) {
        this.accountMapper = accountMapper;
        this.tokenProvider = tokenProvider;
        this.refreshTokenStore = refreshTokenStore;
        this.passwordEncoder = passwordEncoder;
        this.refreshTtl = Duration.ofDays(refreshTtlDays);
    }

    // ── 登录 ───────────────────────────────────────────────────────────────

    /**
     * 手机号 + 密码登录。
     *
     * <p>副作用：更新 app_user 的 last_login_at / last_login_ip；在 RefreshTokenStore 里登记新
     * refresh token。</p>
     *
     * @param request        登录请求
     * @param deviceIdHeader 请求头 X-Device-Id（可为 null）
     * @param clientIp       客户端 IP
     * @return 令牌 + 用户资料
     * @throws BusinessException 402001 登录失败 / 402004 已封禁 / 402008 已注销
     */
    public LoginResponse login(LoginRequest request, String deviceIdHeader, String clientIp) {
        warnIfDeviceMismatch(deviceIdHeader, request.getDeviceId());

        AppUser user = accountMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getPhone, request.getPhone())
                .isNull(AppUser::getDeletedAt));

        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        ensureUsable(user);
        touchLogin(user.getId(), clientIp);
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
    public TokenResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        evictStaleSlots();

        RefreshingEntry mine = new RefreshingEntry(new CompletableFuture<>(), System.currentTimeMillis());
        RefreshingEntry existing = refreshing.putIfAbsent(refreshToken, mine);
        if (existing != null) {
            // 别人正在刷（或刚刷完）：等它，复用同一结果，绝不自己再轮换一次
            return awaitRefreshing(existing.future());
        }

        try {
            TokenResponse result = doRefresh(refreshToken);
            mine.future().complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.future().completeExceptionally(e);
            throw e;
        }
    }

    // ── 资料 ───────────────────────────────────────────────────────────────

    /**
     * 取当前用户资料。
     *
     * @param userId 用户主键（来自 JWT）
     * @return 资料出参
     * @throws BusinessException 402003 用户不存在或已软删（token 仍有效但人已没）
     */
    public UserVO profile(Long userId) {
        AppUser user = accountMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return toUserVO(user);
    }

    // ── 内部实现 ───────────────────────────────────────────────────────────

    /**
     * 真正执行一次轮换：校验旧 token → 吊销 → 发新 refresh → 发新 access。
     *
     * @param refreshToken 旧 refresh token
     * @return 新令牌对
     */
    private TokenResponse doRefresh(String refreshToken) {
        RefreshTokenStore.RefreshRecord record = refreshTokenStore.find(refreshToken);
        if (record == null) {
            // 不存在 / 已过期 / 已被上一次轮换吊销 —— 一律「无效」，让客户端直接登出
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        AppUser user = accountMapper.selectById(record.userId());
        if (user == null || user.getDeletedAt() != null) {
            refreshTokenStore.revoke(refreshToken);
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        ensureUsable(user);

        // 轮换：旧 token 立刻失效，防止重放
        refreshTokenStore.revoke(refreshToken);
        String newRefresh = refreshTokenStore.issue(user.getId(), refreshTtl);
        String newAccess = tokenProvider.createAccessToken(user.getId(), user.getRole());

        TokenResponse response = new TokenResponse();
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
    private LoginResponse issue(AppUser user) {
        LoginResponse response = new LoginResponse();
        response.setAccessToken(tokenProvider.createAccessToken(user.getId(), user.getRole()));
        response.setRefreshToken(refreshTokenStore.issue(user.getId(), refreshTtl));
        response.setExpiresIn((int) tokenProvider.getAccessTtlSeconds());
        response.setTokenType(TOKEN_TYPE_BEARER);
        response.setUser(toUserVO(user));
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
    private TokenResponse awaitRefreshing(CompletableFuture<TokenResponse> future) {
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
     * 实体 → 出参。
     *
     * @param user 用户实体
     * @return 资料出参（不含任何敏感字段）
     */
    private UserVO toUserVO(AppUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setPhoneMasked(maskPhone(user.getPhone()));
        vo.setRealnameStatus(toRealnameStatus(user.getRealnameStatus()));
        vo.setIsMinor(Boolean.TRUE.equals(user.getIsMinor()));
        vo.setRoles(List.of(user.getRole() == null ? DEFAULT_ROLE : user.getRole()));
        // 段 0 没有支付密码表（属段 1），恒 false；不要写成 true 骗客户端
        vo.setPayPasswordSet(false);
        return vo;
    }

    /**
     * 手机号脱敏。
     *
     * @param phone 手机号原文
     * @return 形如 138****8888；不足 7 位时返回空串（不外泄原始值）
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 库内小写实名状态 → 契约要求的大写枚举。
     *
     * @param raw 库内值（none/pending/approved/rejected，可为 null）
     * @return NONE/PENDING/APPROVED/REJECTED
     */
    private String toRealnameStatus(String raw) {
        String value = raw == null ? DEFAULT_REALNAME_STATUS : raw;
        return value.toUpperCase(Locale.ROOT);
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
    private record RefreshingEntry(CompletableFuture<TokenResponse> future, long createdAtMillis) {
    }
}
