/*
 * 职责：管理端 token 校验的回归测试——固定住「RuoYi 的密钥派生 + HS512 验签」这条跨服务契约。
 * 调用方：`mvn -B test`。
 * 数据来源：真实登录拿到的 token 快照（2026-11-05 取自本机 RuoYi 登录态，仅用于本地与 CI）。
 * 注意：
 *   1. **这是跨服务耦合点，必须有测试钉死**（接口规范 §1.3.1 第 6 条要求登记耦合点）。
 *      RuoYi 一旦换 jjwt 版本或改 `token.secret` 的编码方式，这里会红，
 *      而不是等到线上一片 401 才发现。
 *   2. 快照 token 里没有 `exp`（RuoYi 不写），所以这个用例**不会随时间失效**，可以长期留着。
 *   3. 密钥派生是「非标准 Base64」：26 字符 secret 解出 18 字节。见 AdminTokenVerifier 类注释第 2 条。
 */
package com.faze.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.faze.config.properties.FazeAdminAuthProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理端 token 校验器测试。
 */
class AdminTokenVerifierTest {

    /** 本机 RuoYi（3.9.2 / jjwt 0.9.1）真实签发的 token 快照。 */
    private static final String REAL_TOKEN = "eyJhbGciOiJIUzUxMiJ9."
            + "eyJzdWIiOiJhZG1pbiIsImxvZ2luX3VzZXJfa2V5IjoiNTNhMDIwOWQtNjc1Zi00OWQ2LTk5MTYtYTI3MGQ0NDkzNDM5In0."
            + "4SLVvH3kgWPiRKiAjcuoUOy2tN1vMQYus6LUc0xZr8pVcCk4aKstSHqufMhwt4YXjdH8uRgxdbzKNey_ssFeHA";

    /** 上面 token 里 `login_user_key` 的值。 */
    private static final String EXPECTED_LOGIN_KEY = "53a0209d-675f-49d6-9916-a270d4493439";

    /** RuoYi 发行版出厂的 `token.secret`。 */
    private static final String RUOYI_DEFAULT_SECRET = "abcdefghijklmnopqrstuvwxyz";

    @Test
    @DisplayName("密钥派生遵循 DatatypeConverter 语义：26 字符 secret → 18 字节，尾部余数丢弃")
    void deriveKeyDropsIncompleteTrailingGroup() {
        byte[] key = AdminTokenVerifier.deriveKey(RUOYI_DEFAULT_SECRET);
        assertEquals(18, key.length, "尾部的 'yz' 两个字符必须被丢弃（4 字符一组，余数不解）");
        assertEquals("69b71d79f8218a39259a7a29aabb2dbafc31", toHex(key));
    }

    @Test
    @DisplayName("真实 RuoYi token 验签通过并能取出 login_user_key")
    void verifiesRealRuoYiToken() {
        AdminTokenVerifier verifier = newVerifier(RUOYI_DEFAULT_SECRET);
        assertEquals(EXPECTED_LOGIN_KEY, verifier.extractLoginUserKey(REAL_TOKEN));
    }

    @Test
    @DisplayName("载荷被篡改后验签失败")
    void rejectsTamperedPayload() {
        AdminTokenVerifier verifier = newVerifier(RUOYI_DEFAULT_SECRET);
        String[] parts = REAL_TOKEN.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"admin\",\"login_user_key\":\"forged\"}"
                        .getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        assertNull(verifier.extractLoginUserKey(forged));
    }

    @Test
    @DisplayName("密钥不一致时验签失败（防止两边 secret 漂移）")
    void rejectsTokenSignedByAnotherSecret() {
        AdminTokenVerifier verifier = newVerifier("another-shared-secret-value-000000");
        assertNull(verifier.extractLoginUserKey(REAL_TOKEN));
    }

    @Test
    @DisplayName("算法被改成 HS256 也拒绝（防降级）")
    void rejectsDowngradedAlgorithm() {
        AdminTokenVerifier verifier = newVerifier(RUOYI_DEFAULT_SECRET);
        String forgedHeader = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String[] parts = REAL_TOKEN.split("\\.");
        assertNull(verifier.extractLoginUserKey(forgedHeader + "." + parts[1] + "." + parts[2]));
    }

    @Test
    @DisplayName("格式不对的 token 一律返回 null，不抛异常")
    void rejectsMalformedToken() {
        AdminTokenVerifier verifier = newVerifier(RUOYI_DEFAULT_SECRET);
        assertNull(verifier.extractLoginUserKey(""));
        assertNull(verifier.extractLoginUserKey("abc"));
        assertNull(verifier.extractLoginUserKey("a.b"));
        assertNull(verifier.extractLoginUserKey("a.b.c"));
        assertNull(verifier.extractLoginUserKey(null));
    }

    @Test
    @DisplayName("密钥未配置时启动即失败（默认拒绝）")
    void failsFastWhenSecretMissing() {
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> AdminTokenVerifier.deriveKey("  "));
        assertNotNull(e.getMessage());
    }

    /**
     * 造一个配置对象。
     *
     * @param secret 管理端 token 密钥
     * @return 配置
     */
    private static FazeAdminAuthProperties newVerifierArgs(String secret) {
        return new FazeAdminAuthProperties(true, secret, "127.0.0.1", 6379, null, 0, "login_tokens:");
    }

    /**
     * 造一个校验器。
     *
     * @param secret 管理端 token 密钥
     * @return 校验器
     */
    private static AdminTokenVerifier newVerifier(String secret) {
        return new AdminTokenVerifier(newVerifierArgs(secret), JsonMapper.builder().build());
    }

    /**
     * 字节数组转十六进制。
     *
     * @param bytes 字节
     * @return 小写十六进制串
     */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
