/*
 * 职责：新增签名的入参（段 4 D1 `POST /app/signatures`）。
 * 调用方：AppSignatureController。
 * 数据来源：客户端先走 `POST /app/upload/token`（`scene=signature`）直传拿到对象 key，再回填到这里。
 * 注意：
 *   1. **服务端不接收文件本体**，只接收对象 key；key 的格式与归属由 `StorageKeyRules` 校验
 *      （必须是 `private/signature/{yyyyMM}/{自己的 userId}/...`），否则可以引用别人的图。
 *   2. 图片规格（透明底 PNG，600×300 px 以内）由客户端保证；**服务端不缩放**
 *      ——缩放会损失笔迹细节（工作流 D1 第 1 条）。
 */
package com.faze.modules.document.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增签名入参。
 */
@Data
public class SignatureCreateBO {

    /** 直传拿到的对象 key（形如 `private/signature/202611/12/{uuid}.png`）。 */
    @NotBlank(message = "签名图对象键不能为空")
    @Size(max = 500, message = "对象键过长")
    private String imageOssKey;
}
