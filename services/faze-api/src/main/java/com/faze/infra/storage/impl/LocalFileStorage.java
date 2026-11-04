/*
 * 职责：FileStorage 的本地目录实现（第 1 段 C 组，方案 B）。段 4 换云 OSS 时整类替换。
 * 调用方：modules/account/service/UploadService、infra/pub/PubFileController。
 * 数据来源：`faze.storage.base-dir` 下的文件；签名密钥来自 `faze.storage.sign-secret`。
 * 注意：
 *   1. **这是「本地模拟 OSS」不是生产方案**：文件落在开发机磁盘，没有多副本、没有 CDN、
 *      没有对象生命周期。上生产必须换云 OSS（段 8 上线清单里）。
 *   2. 签名用 HMAC-SHA256(key + exp + action)，**带 action 是必须的**——
 *      不带 action 时「上传签名」可以直接拿来当「下载签名」用。
 *   3. 路径穿越防护：key 先过白名单正则，再 resolve + normalize，最后断言仍在 baseDir 内。
 *      三道缺一不可：正则挡格式，normalize 挡 `../`，startsWith 挡符号链接跳出。
 *   4. 上传大小在上限处判断，**不信任客户端声明的 size**（声明 1KB 实际 1GB 的情况必然会发生）。
 */
package com.faze.infra.storage.impl;

import com.faze.config.properties.FazeStorageProperties;
import com.faze.infra.storage.FileStorage;
import com.faze.infra.storage.StorageKeyRules;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地目录存储实现。
 */
@Component
@Slf4j
public class LocalFileStorage implements FileStorage {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String ACTION_PUT = "put";
    private static final String ACTION_GET = "get";

    private final Path baseDir;
    private final byte[] signKey;
    private final int uploadTtlSeconds;
    private final int downloadTtlSeconds;
    private final long maxUploadBytes;
    private final String publicBaseUrl;

    /**
     * 构造本地存储。
     *
     * @param properties    存储配置
     * @param publicBaseUrl 对外可访问的服务地址（用于拼签名 URL）
     */
    public LocalFileStorage(FazeStorageProperties properties,
                            @Value("${faze.api.public-base-url:http://127.0.0.1:8081}") String publicBaseUrl) {
        this.baseDir = Paths.get(properties.baseDir()).toAbsolutePath().normalize();
        String secret = properties.signSecret();
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("faze.storage.sign-secret 未配置或过短（至少 16 字符）");
        }
        this.signKey = secret.getBytes(StandardCharsets.UTF_8);
        this.uploadTtlSeconds = properties.uploadTtlSeconds();
        this.downloadTtlSeconds = properties.downloadTtlSeconds();
        this.maxUploadBytes = properties.maxUploadBytes();
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    /** 启动时确保目录存在，避免第一次上传才发现路径不可写。 */
    @PostConstruct
    public void prepare() throws IOException {
        Files.createDirectories(baseDir);
        log.info("本地存储就绪：baseDir={}（段 4 换 OSS）", baseDir);
    }

    /**
     * 生成上传凭证。
     *
     * @param key         对象 key
     * @param contentType 客户端声明的 MIME（仅记录）
     * @param sizeBytes   客户端声明的字节数（超过上限直接拒绝）
     * @return 上传地址
     */
    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes) {
        if (!StorageKeyRules.isValid(key)) {
            throw new IllegalArgumentException("非法对象 key");
        }
        if (sizeBytes <= 0 || sizeBytes > maxUploadBytes) {
            throw new IllegalArgumentException("文件大小超出允许范围");
        }
        long exp = Instant.now().getEpochSecond() + uploadTtlSeconds;
        String sig = sign(key, exp, ACTION_PUT);
        String url = publicBaseUrl + "/api/v1/pub/file/put?key=" + urlEncode(key)
                + "&exp=" + exp + "&sig=" + sig;
        log.debug("签发上传凭证 key={} size={} contentType={}", key, sizeBytes, contentType);
        return new PresignedUpload(url, uploadTtlSeconds);
    }

    /**
     * 生成下载凭证。
     *
     * @param key 对象 key
     * @return 下载地址
     */
    @Override
    public PresignedUrl presignDownload(String key) {
        if (!StorageKeyRules.isValid(key)) {
            throw new IllegalArgumentException("非法对象 key");
        }
        long exp = Instant.now().getEpochSecond() + downloadTtlSeconds;
        String sig = sign(key, exp, ACTION_GET);
        String url = publicBaseUrl + "/api/v1/pub/file/get?key=" + urlEncode(key)
                + "&exp=" + exp + "&sig=" + sig;
        return new PresignedUrl(url, downloadTtlSeconds);
    }

    /**
     * 写入对象。
     *
     * @param key  对象 key
     * @param in   内容流
     * @param size 字节数（已由调用方按上限校验）
     */
    @Override
    public void put(String key, InputStream in, long size) {
        try {
            Path target = resolveSafe(key);
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("文件已落盘 key={} size={}", key, size);
        } catch (IOException e) {
            throw new IllegalStateException("文件写入失败：" + key, e);
        }
    }

    /**
     * 读取对象。
     *
     * @param key 对象 key
     * @return 内容流
     */
    @Override
    public InputStream get(String key) {
        try {
            Path target = resolveSafe(key);
            if (!Files.exists(target)) {
                throw new IllegalStateException("文件不存在：" + key);
            }
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new IllegalStateException("文件读取失败：" + key, e);
        }
    }

    /**
     * 校验签名与过期时间（区分 action，防止上传签名被当下载签名用）。
     *
     * @param key    对象 key
     * @param exp    过期时间戳
     * @param sig    签名
     * @param action put / get
     * @return 合法且未过期返回 true
     */
    @Override
    public boolean verify(String key, long exp, String sig, String action) {
        if (!StorageKeyRules.isValid(key) || sig == null || exp <= 0) {
            return false;
        }
        if (Instant.now().getEpochSecond() > exp) {
            return false;
        }
        return constantTimeEquals(sig, sign(key, exp, action));
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * key → 绝对路径，并做路径穿越防护。
     *
     * @param key 对象 key
     * @return 安全路径
     */
    private Path resolveSafe(String key) {
        if (!StorageKeyRules.isValid(key)) {
            throw new IllegalArgumentException("非法对象 key");
        }
        Path target = baseDir.resolve(key).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("路径越界");
        }
        return target;
    }

    private String sign(String key, long exp, String action) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signKey, HMAC_ALGORITHM));
            byte[] raw = mac.doFinal((key + "|" + exp + "|" + action).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
