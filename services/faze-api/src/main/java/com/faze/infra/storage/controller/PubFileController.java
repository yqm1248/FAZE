/*
 * 职责：本地存储的直传/下载端点（第 1 段 C 组，方案 B 专用）。
 * 调用方：客户端拿到 uploadUrl / 下载 URL 后直接调用。
 * 数据来源：infra/storage/LocalFileStorage。
 * 注意：
 *   1. **这是本地模拟，不是生产形态**：云 OSS 下文件由客户端直传，faze-api 不接收文件。
 *      段 4 换 OSS 后本类应删除，接口文档里也不出现这两个路径。
 *   2. 两个端点都在 `/api/v1/pub/**`（免登录）——鉴权靠 URL 里的 HMAC 签名与过期时间，
 *      这正是 OSS 预签名 URL 的语义。**签名与过期缺一不可**。
 *   3. 上传在写入前再判一次大小上限（不信任 Content-Length，也不信任客户端声明）。
 *   4. 下载只回 `GET`，且校验 action=get 的签名——上传签名不能拿来下载。
 */
package com.faze.infra.storage.controller;

import com.faze.config.properties.FazeStorageProperties;
import com.faze.infra.storage.FileStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地文件直传与下载端点。
 */
@RestController
@RequestMapping("/api/v1/pub/file")
public class PubFileController {

    private static final String ACTION_PUT = "put";
    private static final String ACTION_GET = "get";

    private final FileStorage fileStorage;
    private final long maxUploadBytes;

    /**
     * 构造控制器。
     *
     * @param fileStorage 存储实现
     * @param properties  存储配置（取大小上限）
     */
    public PubFileController(FileStorage fileStorage, FazeStorageProperties properties) {
        this.fileStorage = fileStorage;
        this.maxUploadBytes = properties.maxUploadBytes();
    }

    /**
     * 接收客户端直传的文件。
     *
     * @param key  对象 key
     * @param exp  过期时间戳（秒）
     * @param sig  签名（action=put）
     * @param body 文件字节
     * @return 204 表示写入成功
     */
    @PutMapping("/put")
    public ResponseEntity<Void> put(@RequestParam("key") String key,
                                    @RequestParam("exp") long exp,
                                    @RequestParam("sig") String sig,
                                    @RequestBody(required = false) byte[] body) {
        if (!fileStorage.verify(key, exp, sig, ACTION_PUT)) {
            return ResponseEntity.status(403).build();
        }
        if (body == null || body.length == 0 || body.length > maxUploadBytes) {
            return ResponseEntity.status(413).build();
        }
        fileStorage.put(key, new ByteArrayInputStream(body), body.length);
        return ResponseEntity.noContent().build();
    }

    /**
     * 下载文件。
     *
     * @param key 对象 key
     * @param exp 过期时间戳（秒）
     * @param sig 签名（action=get）
     * @return 文件流
     */
    @GetMapping("/get")
    public ResponseEntity<byte[]> get(@RequestParam("key") String key,
                                      @RequestParam("exp") long exp,
                                      @RequestParam("sig") String sig) {
        if (!fileStorage.verify(key, exp, sig, ACTION_GET)) {
            return ResponseEntity.status(403).build();
        }
        try (InputStream in = fileStorage.get(key)) {
            byte[] bytes = in.readAllBytes();
            return ResponseEntity.ok()
                    .contentType(mediaTypeOf(key))
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.status(404).build();
        }
    }

    /**
     * 按扩展名推断响应类型（下载时浏览器/客户端要能正确渲染图片）。
     *
     * @param key 对象 key
     * @return MIME 类型
     */
    private MediaType mediaTypeOf(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        return MediaType.IMAGE_JPEG;
    }
}
