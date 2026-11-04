/*
 * 职责：access token 的签发与解析（HS256）。只做密码学与 claims，不碰数据库、不碰存储。
 * 调用方：modules/account/AuthService（签发）、security/JwtAuthenticationFilter（解析）。
 * 数据来源：`faze.jwt.*` 配置；密钥来自环境变量 FAZE_TOKEN_SECRET（生产）或 dev 默认值。
 * 注意：
 *   1. **密钥长度必须 ≥ 32 字节**：HS256 的密钥短于 256 bit 时 jjwt 会直接抛异常。
 *      本类在构造期就校验，让配置错误在启动时暴露，而不是等到第一次登录才炸。
 *   2. 生产 profile 的密钥**没有默认值**（application-prod.yml 写的是 `${FAZE_TOKEN_SECRET}`），
 *      未设置即启动失败——这是刻意的「默认拒绝」，避免带着示例密钥上线。
 *   3. token 里的信息不可信：解析成功只证明「签发者是我们」，业务状态（封禁、注销）
 *      必须每次查库或查缓存，不能只看 claims。
 *   4. 过期与无效必须能被调用方区分（ExpiredJwtException vs 其他 JwtException），
 *      这是客户端「刷新 / 登出」两条路的依据（《错误码规范》§4.3）。
 */
package com.faze.security;

import com.faze.config.properties.FazeJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与解析（HS256）。只做密码学与 claims，不碰数据库。
 */
@Component
public class JwtTokenProvider {

    /** claim 名：令牌类型。用来防止「拿 refresh token 当 access token 用」。 */
    public static final String CLAIM_TYPE = "type";
    /** 类型值：access token。 */
    public static final String TYPE_ACCESS = "access";
    /** claim 名：角色。 */
    public static final String CLAIM_ROLE = "role";
    /** claim 名：凭证版本号（对应 app_user.token_version）。改密/换绑/注销时 +1，旧 token 立即失效。 */
    public static final String CLAIM_VER = "ver";

    /** HS256 要求的最小密钥字节数（256 bit）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTtl;

    /**
     * 构造签发器，并校验密钥强度。
     *
     * @param properties JWT 配置（密钥与有效期），绑定自 `faze.jwt.*`
     * @throws IllegalStateException 密钥未配置或不足 32 字节时抛出（启动即失败，属预期）
     */
    public JwtTokenProvider(FazeJwtProperties properties) {
        String secret = properties.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "faze.jwt.secret 未配置或不足 " + MIN_SECRET_BYTES + " 字节；"
                            + "生产必须由环境变量 FAZE_TOKEN_SECRET 提供（见 application-prod.yml）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(properties.accessTtlMinutes());
    }

    /**
     * 签发 access token。
     *
     * @param userId       用户主键，写入 sub
     * @param role         角色，写入 role claim
     * @param tokenVersion 凭证版本号，写入 ver claim；与库中值不一致即视为失效
     * @return 紧凑格式的 JWT 字符串
     */
    public String createAccessToken(Long userId, String role, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_VER, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .id(UUID.randomUUID().toString())
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 access token。
     *
     * @param token 紧凑格式的 JWT
     * @return 认证主体
     * @throws ExpiredJwtException 已过期（调用方应回 402002，让客户端去刷新）
     * @throws JwtException        签名错误、格式错误、类型不是 access（调用方应回 402003）
     */
    public LoginUser parseAccessToken(String token) throws ExpiredJwtException, JwtException {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            // 典型攻击：把 30 天的 refresh token 当 access token 用，必须拒绝
            throw new JwtException("不是 access token");
        }
        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new JwtException("缺少 sub");
        }
        Long userId;
        try {
            userId = Long.valueOf(sub);
        } catch (NumberFormatException e) {
            throw new JwtException("sub 不是合法的用户主键");
        }
        String role = claims.get(CLAIM_ROLE, String.class);
        Integer tokenVersion = claims.get(CLAIM_VER, Integer.class);
        return new LoginUser(userId, role == null ? "user" : role, tokenVersion == null ? 0 : tokenVersion);
    }

    /**
     * access token 的有效秒数，用于登录/刷新响应里的 {@code expiresIn}。
     *
     * @return 有效秒数
     */
    public long getAccessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
