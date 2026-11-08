/*
 * 职责：签名库列表项（段 4 D1 `GET /app/signatures`）。
 * 调用方：AppSignatureController → SignatureService。
 * 数据来源：signature（`document_id IS NULL`）+ 现签的文件地址。
 * 注意：`imageUrl` 是**60 秒现签地址**，不缓存、不入库；`id` 是 Long，随 Jackson 全局配置序列化成字符串。
 */
package com.faze.modules.document.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 签名库一项。
 */
@Data
public class SignatureVO {

    /** 签名主键。 */
    private Long id;

    /** 签名 PNG 的 60 秒签名地址。 */
    private String imageUrl;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
