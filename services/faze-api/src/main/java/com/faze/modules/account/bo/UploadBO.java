/*
 * 职责：上传凭证申请入参（`POST /app/upload/token`）。
 * 调用方：modules/account/controller/UploadController。
 * 数据来源：客户端提交。
 * 注意：
 *   1. `scene` 与 `contentType` 都用**白名单正则**，不用黑名单——
 *      黑名单永远漏一个，而 scene 直接进存储路径。
 *   2. `size` 只是「客户端声明」的值，服务端在真正写入时还会再判一次（不信任声明）。
 *   3. 文件名只取扩展名，**原文件名不参与路径**（中文名、`../`、超长名都不进来）。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 上传凭证申请。
 */
@Data
public class UploadBO {

    /** 场景：realname / identity / license / avatar / chat（`chat` 为段 2 G6 聊天附件）。 */
    @NotBlank(message = "场景不能为空")
    @Pattern(regexp = "^(realname|identity|license|avatar|chat)$", message = "场景不在白名单内")
    private String scene;

    /** 原始文件名（只用于取扩展名）。 */
    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名过长")
    private String fileName;

    /** 文件字节数。 */
    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小非法")
    @Max(value = 10485760, message = "文件超过 10MB")
    private Long size;

    /** MIME 类型。 */
    @NotBlank(message = "文件类型不能为空")
    @Pattern(regexp = "^(image/(jpeg|png|webp)|application/pdf)$", message = "文件类型不在白名单内")
    private String contentType;
}
