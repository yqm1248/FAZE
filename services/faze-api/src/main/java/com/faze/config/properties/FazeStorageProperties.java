/*
 * 职责：存储配置（第 1 段 C 组）。一个领域一个类，不与其他配置合并。
 * 调用方：Spring 依赖注入；infra/storage 的实现类。
 * 数据来源：`faze.storage.*`，密钥与目录都由环境变量覆盖，配置里只留本地开发默认值。
 * 注意：
 *   1. **本段是「本地目录」实现（方案 B）**：用户 2026-11-04 拍板——暂不开通云 OSS，
 *      先用本地目录跑通 C 组，段 4 换成 OSS 时只替换 FileStorage 的实现类，接口不动。
 *   2. 因此 `sign-secret` 只用于本地模拟签名 URL 的 HMAC，**不是云厂商的 AK/SK**；
 *      接 OSS 后本配置项不再需要。
 *   3. 目录必须落在仓库之外（默认 E:/dshcode/faze-files），避免被 git 扫到。
 */
package com.faze.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储配置。
 *
 * @param baseDir            本地文件根目录
 * @param signSecret         本地签名密钥（HMAC），仅本地实现使用
 * @param downloadTtlSeconds 下载链接默认有效期（秒）
 * @param uploadTtlSeconds   上传链接默认有效期（秒）
 * @param maxUploadBytes     单文件大小上限（字节）
 */
@ConfigurationProperties(prefix = "faze.storage")
public record FazeStorageProperties(
        String baseDir,
        String signSecret,
        int downloadTtlSeconds,
        int uploadTtlSeconds,
        long maxUploadBytes) {
}
