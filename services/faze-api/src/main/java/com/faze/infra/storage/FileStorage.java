/*
 * 职责：文件存储的抽象接口（第 1 段 C 组）。当前实现是本地目录，段 4 换云 OSS。
 * 调用方：modules/account（上传签名）、infra/storage 的本地实现、PubFileController（本地直传端点）。
 * 数据来源：配置文件 `faze.storage.*`。
 * 注意：
 *   1. **接口按云 OSS 的语义设计**（预签名上传 / 预签名下载 / 私有桶），
 *      这样段 4 换成 OSS 时只替换实现类，上层代码不用改一行业务逻辑。
 *   2. 本地实现与 OSS 有一处**本质差异**：OSS 是客户端直传（文件不经服务端），
 *      本地实现必须经服务端中转。这个差异只体现在「上传 URL 指向谁」上，
 *      调用方拿到的仍然是「一个可以 PUT 文件的 URL」。
 *   3. 所有 key 必须由服务端生成并校验格式（见 KeyRules），**禁止把客户端传来的 path 直接拼接**。
 */
package com.faze.infra.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * 文件存储。
 */
public interface FileStorage {

    /**
     * 生成上传凭证。
     *
     * @param key         服务端生成的完整对象 key
     * @param contentType 客户端声明的 MIME 类型（本地实现只用于校验）
     * @param sizeBytes   客户端声明的文件大小（用于上限校验）
     * @return 上传地址与有效期
     */
    PresignedUpload presignUpload(String key, String contentType, long sizeBytes);

    /**
     * 生成下载凭证。
     *
     * @param key 对象 key
     * @return 下载地址与有效期
     */
    PresignedUrl presignDownload(String key);

    /**
     * 写入对象（仅本地实现需要；OSS 模式下由客户端直传，不经过这里）。
     *
     * @param key  对象 key
     * @param in   内容流
     * @param size 字节数
     */
    void put(String key, InputStream in, long size);

    /**
     * 读取对象（仅本地实现的下载端点使用）。
     *
     * @param key 对象 key
     * @return 内容流
     */
    InputStream get(String key);

    /**
     * 校验本地签名（仅本地实现使用；OSS 模式下校验由云厂商完成）。
     *
     * @param key    对象 key
     * @param exp    过期时间戳（秒）
     * @param sig    签名
     * @param action put / get；**必须区分**，否则上传签名可当下载签名用
     * @return 是否合法且未过期
     */
    boolean verify(String key, long exp, String sig, String action);

    /**
     * 上传凭证。
     *
     * @param url        可 PUT 的地址
     * @param expiresIn  有效期（秒）
     */
    record PresignedUpload(String url, long expiresIn) {
    }

    /**
     * 下载凭证。
     *
     * @param url       可 GET 的地址
     * @param expiresIn 有效期（秒）
     */
    record PresignedUrl(String url, long expiresIn) {
    }
}
