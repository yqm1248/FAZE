/*
 * 职责：敏感字段的加解密（第 1 段 C1，身份证号等 L1 字段）。
 * 调用方：modules/account/service/RealnameService（加密写入、审核详情解密）。
 * 数据来源：密钥来自环境变量 FAZE_IDCARD_SECRET（dev 有本地默认值）。
 * 注意：
 *   1. **AES-256-GCM 而不是 AES-CBC**：GCM 自带完整性校验，密文被篡改会解密失败，
 *      而 CBC 改一位只影响一位明文、攻击者可以在不知道密钥的情况下篡改数据。
 *   2. **每次加密用新的随机 IV**（12 字节），IV 与密文一起存（`Base64(iv + cipher)）`。
 *      IV 复用是 GCM 最致命的误用，会导致明文可被恢复。
 *   3. 密文格式 `Base64(iv(12) || ciphertext || tag(16))`，Java 的 GCM 已把 tag 附在末尾。
 *   4. **解密入口越少越安全**：当前只有「审核详情」需要解密，列表一律用掩码列。
 *      不要在 VO 转换、日志、toString 里调用 decrypt。
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
 * 敏感字段加解密。
 */
@Component
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    /**
     * 构造加解密服务。
     *
     * @param rawKey 32 字节密钥（环境变量 FAZE_IDCARD_SECRET）
     * @throws IllegalStateException 密钥长度不是 32 字节时抛出（启动即失败）
     */
    public CryptoService(@Value("${faze.crypto.id-card-key}") String rawKey) {
        byte[] bytes = rawKey == null ? new byte[0] : rawKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "faze.crypto.id-card-key 必须是 " + KEY_BYTES + " 字节（AES-256），当前 "
                            + bytes.length + " 字节；生产由 FAZE_IDCARD_SECRET 提供");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    /**
     * 加密。
     *
     * @param plain 明文
     * @return Base64(iv + 密文 + tag)；明文为空时返回 null
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
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密。
     *
     * @param encoded Base64(iv + 密文 + tag)
     * @return 明文；输入为空时返回 null
     * @throws IllegalStateException 密文被篡改或密钥不匹配
     */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
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
            throw new IllegalStateException("解密失败（密文可能被篡改或密钥不匹配）", e);
        }
    }

    /**
     * 身份证号掩码：保留前 3 后 4，中间打星。
     *
     * @param idCard 身份证号明文
     * @return 形如 110***********1234；长度不足时返回全星
     */
    public String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return "****";
        }
        int keep = 4;
        StringBuilder sb = new StringBuilder(idCard.length());
        sb.append(idCard, 0, 3);
        for (int i = 3; i < idCard.length() - keep; i++) {
            sb.append('*');
        }
        sb.append(idCard, idCard.length() - keep, idCard.length());
        return sb.toString();
    }
}
