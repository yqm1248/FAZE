/*
 * 职责：refresh token 的进程内存储与轮换状态（段 0 不引 Redis，段 1 迁 Redis 时只换实现）。
 * 调用方：modules/account/AuthService（签发 / 校验 / 轮换）。
 * 数据来源：本进程内存（ConcurrentHashMap）。
 * 注意：
 *   1. **refresh token 用随机串而不是 JWT**：它需要「轮换后旧 token 立刻失效」的语义，
 *      JWT 天生自包含、签发后无法吊销，要用它就得再存一份吊销名单——那等于两套状态。
 *      随机串 + 服务端记录是更少的状态，也更贴合段 1 迁 Redis 的方向。
 *   2. **进程重启即全员重新登录**：这是段 0 的已知限制，写进 services/faze-api/README.md，
 *      段 1 迁 Redis 后消失。不许用「把 token 写进本地文件」来绕过——那是把会话状态落盘。
 *   3. 惰性清理过期项：每次读写在顺手清，避免长跑进程内存只增不减。
 *   4. 必须用 SecureRandom：`Math.random`/`UUID.randomUUID` 都不保证密码学强度，refresh token
 *      一旦可预测就等于永久登录凭证。
 */
package com.faze.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * refresh token 存储（进程内，段 1 迁 Redis）。
 */
@Component
public class RefreshTokenStore {

    /** 随机串字节数：32 字节 = 256 bit，Base64URL 后 43 字符。 */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** token → 记录。 */
    private final Map<String, RefreshRecord> store = new ConcurrentHashMap<>();

    /**
     * 登录时签发一个新的 refresh token。
     *
     * @param userId 用户主键
     * @param ttl    有效期（如 30 天）
     * @return 新 token（43 字符的 Base64URL 串）
     */
    public String issue(Long userId, Duration ttl) {
        cleanup();
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        store.put(token, new RefreshRecord(userId, Instant.now().plus(ttl)));
        return token;
    }

    /**
     * 查 token 对应的用户。
     *
     * @param token refresh token
     * @return 未过期时返回记录；不存在或已过期返回 null
     */
    public RefreshRecord find(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        cleanup();
        RefreshRecord record = store.get(token);
        if (record == null) {
            return null;
        }
        if (record.expiresAt().isBefore(Instant.now())) {
            store.remove(token);
            return null;
        }
        return record;
    }

    /**
     * 吊销一个 token（轮换或登出时调用）。
     *
     * @param token refresh token；不存在时无副作用
     */
    public void revoke(String token) {
        if (token != null) {
            store.remove(token);
        }
    }

    /**
     * 吊销某个用户的**全部** refresh token（改密 / 换绑 / 注销时调用）。
     *
     * <p>只加 token_version 不够：旧 refresh token 仍然能换出新 access token，
     * 而换出来的新 access 会带**最新**的 ver，等于绕过了失效机制。
     * 两者必须一起做。</p>
     *
     * @param userId 用户主键
     */
    public void revokeAllByUser(Long userId) {
        if (userId == null) {
            return;
        }
        store.entrySet().removeIf(e -> userId.equals(e.getValue().userId()));
    }

    /**
     * 当前有效 token 数量。仅用于冒烟测试与运维排查，不参与业务判断。
     *
     * @return 记录数
     */
    public int size() {
        return store.size();
    }

    /** 惰性清理：移除所有已过期记录。 */
    private void cleanup() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    /**
     * refresh token 的服务端记录。
     *
     * @param userId    所属用户主键
     * @param expiresAt 过期时间（UTC）
     */
    public record RefreshRecord(Long userId, Instant expiresAt) {
    }
}
