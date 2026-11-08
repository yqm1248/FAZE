/*
 * 职责：ConfigCipher（sys_config 敏感值加解密）的往返与边界单测。
 * 调用方：mvn test（surefire）。
 * 数据来源：《第 6 段工作流》§三 A3 第 5 条（加密字段 ENC 包裹）、第 6 条（密钥读取红线）。
 * 注意：
 *   1. 本测试**直接 new** ConfigCipher（不启 Spring）：加解密是纯函数，
 *      启动上下文只会让测试变慢并引入无关失败。
 *   2. 必须验「篡改密文抛异常」：AES-GCM 的价值就在这里，CBC 改一位只影响一位明文。
 *   3. 必须验「非 ENC( 的值原样返回」：生产可能先把明文写进库再改密文，
 *      读到明文时不能炸——这是兼容旧数据的唯一入口。
 *   4. 本测试不打印真实密钥，只打印一条**演示用** ENC 值（内容是无意义的 dev 占位），
 *      供 A3-config.md 的「库里以 ENC( 开头」验收使用。
 */
package com.faze.infra.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置值加解密单测。
 */
class ConfigCipherTest {

    /** 与 application-dev.yml 的 dev 默认值一致（32 字节），**不是生产密钥**。 */
    private static final String DEV_KEY = "faze-local-config-key-32-bytes!!";

    private final ConfigCipher cipher = new ConfigCipher(DEV_KEY);

    /**
     * 加密 → 解密往返。
     */
    @Test
    @DisplayName("往返：encrypt 输出 ENC(...)，decryptIfEncrypted 还原明文")
    void roundTrip() {
        String plain = "dev-only-wechat-apiv3-key";
        String enc = cipher.encrypt(plain);
        assertThat(enc).startsWith("ENC(").endsWith(")");
        assertThat(cipher.isEncrypted(enc)).isTrue();
        assertThat(cipher.decryptIfEncrypted(enc)).isEqualTo(plain);
        // 演示值：每次 IV 随机，所以只断言格式，不写死
        System.out.println("A3-DEMO-ENC=" + enc);
    }

    /**
     * 非密文原样返回（兼容旧明文数据）。
     */
    @Test
    @DisplayName("非 ENC(...) 的值：原样返回，不抛异常")
    void plainPassThrough() {
        assertThat(cipher.decryptIfEncrypted("plain-value")).isEqualTo("plain-value");
        assertThat(cipher.decryptIfEncrypted("")).isEmpty();
        assertThat(cipher.decryptIfEncrypted(null)).isNull();
        assertThat(cipher.isEncrypted(null)).isFalse();
        assertThat(cipher.isEncrypted("ENC()")).as("空壳不算密文").isFalse();
        assertThat(cipher.isEncrypted("ENC(abc")).as("后缀不匹配不算密文").isFalse();
    }

    /**
     * 两次加密同一明文，密文不同（IV 随机）。
     */
    @Test
    @DisplayName("同一明文两次加密：密文不同（随机 IV）")
    void randomIv() {
        String plain = "same-plain";
        assertThat(cipher.encrypt(plain)).isNotEqualTo(cipher.encrypt(plain));
    }

    /**
     * 篡改密文必须解密失败（GCM 完整性校验）。
     */
    @Test
    @DisplayName("篡改密文：解密抛异常（GCM 完整性）")
    void tamperedFails() {
        String enc = cipher.encrypt("tamper-me");
        // 把 Base64 中段改一个字符，破坏密文/标签
        String body = enc.substring("ENC(".length(), enc.length() - 1);
        char flipped = body.charAt(body.length() - 2) == 'A' ? 'B' : 'A';
        String tampered = "ENC(" + body.substring(0, body.length() - 2) + flipped
                + body.charAt(body.length() - 1) + ")";
        assertThatThrownBy(() -> cipher.decryptIfEncrypted(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 密钥长度不是 32 字节：构造即失败（启动即拦）。
     */
    @Test
    @DisplayName("密钥长度非法：构造抛 IllegalStateException")
    void badKeyLength() {
        assertThatThrownBy(() -> new ConfigCipher("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
