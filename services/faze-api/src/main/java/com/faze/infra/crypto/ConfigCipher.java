/*
 * 职责：sys_config 里敏感配置值的加解密（支付渠道密钥用）。
 * 调用方：infra/pay 的三个网关（读 api_v3_key / private_key 时解密）、
 *         运维脚本（把明文加密成 ENC(...) 再写库）。
 * 数据来源：密钥来自环境变量 FAZE_CONFIG_SECRET（dev 有本地默认值）。
 * 注意：
 *   1. **为什么不直接用 Jasypt**：《第 6 段工作流》§三 A3 写「走 Jasypt 加密」，
 *      但 Jasypt 只处理 application.yml 的 PropertySource，**管不到 DB 里的值**；
 *      而本项目段 1 已有 AES-256-GCM 的 CryptoService。再引一个加密库 = 两套密钥体系
 *      + 一个 SB4 兼容风险。**复用同一算法（AES-256-GCM）、独立密钥**，等价且无新依赖。
 *   2. **支付密钥与身份证密钥必须分开**：泄漏面不同（一个进审计日志、一个进渠道报文），
 *      共用一把等于把两处风险耦合。所以这里是 `faze.crypto.config-key`，
 *      不是 CryptoService 的 `id-card-key`。
 *   3. 密文格式 `ENC(Base64(iv||ciphertext||tag))`——保留 Jasypt 的 `ENC()` 包裹语义，
 *      库里能一眼看出哪些值被加密过；解密入口只看前缀，非 ENC( 的原样返回（兼容旧明文键）。
 *   4. **解密后的值只进网关对象**，不进 DTO / 日志 / 响应（A3 第 6 条）。
 *      本类不打任何日志，异常也不回显明文。
 */
package com.faze.infra.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 配置值加解密（ENC 包裹）。
 */
@Component
public class ConfigCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** 密文包裹前缀。与 Jasypt 同语义，方便运维识别。 */
    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    /**
     * 构造配置加解密服务。
     *
     * @param rawKey 32 字节密钥（环境变量 FAZE_CONFIG_SECRET）
     * @throws IllegalStateException 密钥长度不是 32 字节时抛出（启动即失败）
     */
    public ConfigCipher(@Value("${faze.crypto.config-key}") String rawKey) {
        byte[] bytes = rawKey == null ? new byte[0] : rawKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "faze.crypto.config-key 必须是 " + KEY_BYTES + " 字节（AES-256），当前 "
                            + bytes.length + " 字节；生产由 FAZE_CONFIG_SECRET 提供");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    /**
     * 加密并包成 {@code ENC(...)}。
     *
     * @param plain 明文
     * @return {@code ENC(Base64(iv||密文||tag))}；明文为空返回 null
     */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(out) + ENC_SUFFIX;
        } catch (Exception e) {
            throw new IllegalStateException("配置加密失败", e);
        }
    }

    /**
     * 解密（仅当值被 {@code ENC(...)} 包裹时）。
     *
     * <p>非加密值原样返回：兼容「同一个键在不同环境有明文/密文两种写法」，
     * 也避免读到旧数据直接炸。</p>
     *
     * @param value sys_config.config_value 的原始值
     * @return 明文；输入为空返回 null
     * @throws IllegalStateException 密文被篡改或密钥不匹配
     */
    public String decryptIfEncrypted(String value) {
        if (!isEncrypted(value)) {
            return value;
        }
        String base64 = value.substring(ENC_PREFIX.length(), value.length() - ENC_SUFFIX.length());
        try {
            byte[] all = Base64.getDecoder().decode(base64);
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("密文长度非法");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] cipherText = new byte[all.length - IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            System.arraycopy(all, IV_BYTES, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("配置解密失败（密文可能被篡改或密钥不匹配）", e);
        }
    }

    /**
     * 值是否是 ENC(...) 包裹的密文。
     *
     * @param value 配置值
     * @return 是密文返回 true
     */
    public boolean isEncrypted(String value) {
        return value != null
                && value.startsWith(ENC_PREFIX)
                && value.endsWith(ENC_SUFFIX)
                && value.length() > ENC_PREFIX.length() + ENC_SUFFIX.length();
    }
}
