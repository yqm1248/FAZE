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

    /**
     * 场景：realname / identity / license / avatar / chat / signature / complaint / feedback。
     *
     * <p>段 4 的 `document`（文书 PDF）与 `doc_template`（模板预览图）**故意不在此白名单内**：
     * 它们由服务端生成后用 `StorageKeyRules.build()` 直接落盘，客户端拿不到上传凭证。
     * 放进来说明「客户端可以自己传一份 PDF 公文」——那是绕过生成链的漏洞。</p>
     *
     * <p>**用户直传白名单必须与 {@link com.faze.infra.storage.StorageKeyRules#ALLOWED_SCENES}
     * 的「用户直传子集」逐项一致**，也要与 APP 侧 `UploadScene`
     * （`apps/mobile-app/features/account/api/upload.ts`）一致。三处任一漏项的表现是：
     * 页面提示「截图上传失败」，后端返回 `401001 scene: 场景不在白名单内`，
     * 而 `StorageKeyRules` 那边看起来是支持该场景的——排查时极易误判成前端问题。</p>
     */
    @NotBlank(message = "场景不能为空")
    @Pattern(regexp = "^(realname|identity|license|avatar|chat|signature|complaint|feedback)$",
            message = "场景不在白名单内")
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
