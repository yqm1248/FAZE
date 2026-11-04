/*
 * 职责：校验 RuoYi 签发的 HS512 管理端 token，取出 `login_user_key`（登录态 UUID）。
 * 调用方：security/AdminAuthenticationFilter。
 * 数据来源：`faze.admin-auth.token-secret`（必须等于 RuoYi 的 `token.secret`）。
 * 注意：
 *   1. **不能用 jjwt 的 `Keys.hmacShaKeyFor` / `verifyWith`**：RuoYi 的密钥只有 18 字节
 *      （见第 2 条），jjwt 0.12.x 的强类型密钥入口强制 ≥ 256 bit，会直接抛 WeakKeyException。
 *      所以这里按 RuoYi 的实际算法手写：HMAC-SHA512 + `SecretKeySpec(bytes, "HmacSHA512")`，
 *      这正是 jjwt 0.9.1 内部 `signWith(SignatureAlgorithm.HS512, String)` 的做法。
 *   2. **密钥派生不是标准 Base64**（2026-11-05 实测）。RuoYi 用 jjwt 0.9.1，
 *      其 `TextCodec.BASE64.decode` 走 `javax.xml.bind.DatatypeConverter.parseBase64Binary`：
 *      **只解完整的 4 字符组，尾部的 2~3 个字符直接丢弃**。
 *      实测：`token.secret = abcdefghijklmnopqrstuvwxyz`（26 字符）
 *      → DatatypeConverter 解出 **18 字节** `69b71d79f8218a39259a7a29aabb2dbafc31`，
 *      而 Node 的 `Buffer.from(s,'base64')` / 常规 Base64 解码器会解出 **19 字节**（多一个 `cb`），
 *      两者算出的签名不同，用后者校验会**永远失败**。
 *      证据：`docs/acceptance/seg1/F1-admin-auth-bridge.md`。
 *   3. RuoYi 的 token **不含 `exp` 声明**（`TokenService.createToken` 只写了
 *      `login_user_key` 与 `sub`）。所以有效期只能靠 Redis key 的 TTL，
 *      本类只负责「签名对、格式对」，不代表「还登录着」。
 *   4. 校验失败一律返回 null，**不抛异常**：调用方要按「未认证」处理（走 401 出口），
 *      抛异常会变成 500，PC 端会被弹「系统接口500异常」而不是跳登录页。
 */
package com.faze.security;

import com.faze.config.properties.FazeAdminAuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RuoYi 管理端 token 校验器。
 */
@Component
@Slf4j
public class AdminTokenVerifier {

    /** RuoYi `Constants.LOGIN_USER_KEY` 的值。 */
    public static final String CLAIM_LOGIN_USER_KEY = "login_user_key";

    /** 签名算法名（JCA）。 */
    private static final String HMAC_ALGORITHM = "HmacSHA512";
    /** JWT 头里的算法名，必须与之一致，防止降级攻击（把 HS512 改成 none / HS256）。 */
    private static final String EXPECTED_JWT_ALG = "HS512";
    /** 非 Base64 字符（含空白），派生密钥前先剔掉。 */
    private static final Pattern NON_BASE64 = Pattern.compile("[^A-Za-z0-9+/=]");

    private final SecretKeySpec signingKey;
    private final JsonMapper jsonMapper;

    /**
     * 构造校验器，并完成密钥派生（失败即启动失败，属预期）。
     *
     * @param properties 管理端鉴权配置
     * @param jsonMapper Jackson 3 的 Mapper（解析 JWT 头与载荷）
     * @throws IllegalStateException 密钥未配置或派生不出密钥时抛出
     */
    public AdminTokenVerifier(FazeAdminAuthProperties properties, JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        if (!properties.enabled()) {
            this.signingKey = null;
            log.info("管理端鉴权桥已禁用（faze.admin-auth.enabled=false），/api/v1/admin/** 将恒回 401");
            return;
        }
        byte[] keyBytes = deriveKey(properties.tokenSecret());
        this.signingKey = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
        log.info("管理端 token 校验密钥已派生：{} 字节（RuoYi token.secret 的 DatatypeConverter 派生结果）",
                keyBytes.length);
    }

    /**
     * 校验 token 并取出登录态 UUID。
     *
     * <p>无副作用，纯计算。</p>
     *
     * @param token 紧凑格式 JWT（不含 `Bearer ` 前缀）
     * @return 登录态 UUID；签名不符、格式不对、算法不是 HS512、缺 `login_user_key` 时返回 null
     */
    public String extractLoginUserKey(String token) {
        if (signingKey == null || token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        if (!verifySignature(parts)) {
            return null;
        }
        return readLoginUserKey(parts[1]);
    }

    /**
     * 校验 HS512 签名。
     *
     * @param parts JWT 的三段（头 / 载荷 / 签名）
     * @return 签名是否正确
     */
    private boolean verifySignature(String[] parts) {
        try {
            // 先确认 alg，避免 attacker 把算法改成 none 或 HS256 后绕过
            String alg = readAlg(parts[0]);
            if (!EXPECTED_JWT_ALG.equals(alg)) {
                log.debug("管理端 token 的 alg 不是 HS512，拒绝：{}", alg);
                return false;
            }
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            // 定长比较，避免时序侧信道
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            log.debug("管理端 token 签名校验异常", e);
            return false;
        }
    }

    /**
     * 读 JWT 头里的 `alg`。
     *
     * @param headerPart Base64URL 的头部
     * @return 算法名；解析失败时返回空串
     */
    private String readAlg(String headerPart) {
        JsonNode header = readJsonPart(headerPart);
        if (header == null) {
            return "";
        }
        JsonNode alg = header.get("alg");
        return alg != null && alg.isTextual() ? alg.asText() : "";
    }

    /**
     * 从载荷里取 `login_user_key`。
     *
     * @param payloadPart Base64URL 的载荷
     * @return 登录态 UUID；缺失时返回 null
     */
    private String readLoginUserKey(String payloadPart) {
        JsonNode payload = readJsonPart(payloadPart);
        if (payload == null) {
            return null;
        }
        JsonNode key = payload.get(CLAIM_LOGIN_USER_KEY);
        if (key == null || !key.isTextual()) {
            return null;
        }
        String value = key.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * 把 Base64URL 段解析成 JSON 树。
     *
     * @param part Base64URL 段
     * @return JSON 树；解不开时返回 null
     */
    private JsonNode readJsonPart(String part) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(part);
            return jsonMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 按 RuoYi（jjwt 0.9.1 + DatatypeConverter）的语义派生密钥字节。
     *
     * <p>规则：剔除非法字符 → **只取完整的 4 字符组**（尾部余数丢弃）→ 标准 Base64 解码。
     * 详见类注释第 2 条。</p>
     *
     * @param secret 配置里的 `token.secret` 原文
     * @return 用于 HMAC-SHA512 的密钥字节
     * @throws IllegalStateException 未配置、或解不出任何字节时抛出
     */
    static byte[] deriveKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "faze.admin-auth.token-secret 未配置；必须与 RuoYi 的 token.secret 一致，"
                            + "生产由环境变量 FAZE_ADMIN_TOKEN_SECRET 提供");
        }
        String compact = NON_BASE64.matcher(secret).replaceAll("");
        int usable = compact.length() - (compact.length() % 4);
        if (usable < 4) {
            throw new IllegalStateException(
                    "faze.admin-auth.token-secret 少于 4 个 Base64 字符，RuoYi 侧解不出密钥");
        }
        byte[] decoded = Base64.getDecoder().decode(compact.substring(0, usable));
        if (decoded.length == 0) {
            throw new IllegalStateException("faze.admin-auth.token-secret 派生出的密钥长度为 0");
        }
        return decoded;
    }
}
