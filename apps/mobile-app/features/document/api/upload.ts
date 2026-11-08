/**
 * 签名图上传（段 4 E6）——三段式上传的 `scene=signature` 场景。
 *
 * 职责：把「手写板导出的透明底 PNG」或「相册选的图片」传到对象存储，拿到对象 key 后
 *      交给 `./client.ts` 的 `addSignature(key)` 落库。
 * 调用方：`features/document/pages/DocSealPage.tsx`。
 * 数据来源：`POST /api/v1/app/upload/token`（段 1 G3 的接口，body `{scene,fileName,size,contentType}`）
 *          → `PUT {uploadUrl}`（预签名直传）→ 返回 `{ key }`。
 * 注意：
 *   1. **字节不经过 faze-api**：先申请凭证、再 PUT 到签名地址，最后只回传对象 key
 *      （与 `features/account/api/upload.ts` 同一协议，页面不许自己拼路径）。
 *   2. **为什么不复用 `features/account/api/upload.ts`**：那个文件的 `UploadScene` 联合类型
 *      只登记了 `realname` / `identity` / `license` / `avatar` / `chat`，不含 `signature`；
 *      而它不在本段 E 组的可写范围（并行开发，改它必然写冲突）。因此按**完全相同**的
 *      协议在本域内实现一次，请求体字段与接口地址一字不差。等段 8 收口时应合并回
 *      `features/account/api/upload.ts` 并把 `signature` 加进 `UploadScene`。
 *   3. `scene` 必须是小写 `signature`——后端 `UploadController` 按白名单校验。
 *   4. 客户端不做大小 / 类型白名单判断（服务端二次校验）；但 MIME 必须报准，
 *      否则存储端的 Content-Type 与真实字节不符，浏览器预览会失败。
 *   5. 二进制出口只有 `shared/api/client.ts` 的 `putBinary`（全仓唯一允许出现 `fetch` 的地方之一）。
 */

import { post, putBinary } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 上传场景（后端 `UploadController` 的白名单值）。 */
const SIGNATURE_SCENE = 'signature';

/** 上传凭证出参（与段 1 `UploadTokenVO` 同形）。 */
export type SignatureUploadToken = {
  /** 对象 key，`addSignature` 时原样回传。 */
  key: string;
  /** 可 PUT 的直传地址。 */
  uploadUrl: string;
  /** 有效期（秒）。 */
  expiresIn: number;
};

/** 待上传的签名图。 */
export type SignatureUploadInput = {
  /** 文件名（仅用于取扩展名，服务端不接受中文路径片段）。 */
  fileName: string;
  /** 字节数。 */
  size: number;
  /** MIME 类型（`image/png` / `image/webp` / `image/jpeg`）。 */
  contentType: string;
  /** 文件内容。 */
  body: Blob;
};

/**
 * 申请签名图上传凭证。
 *
 * @param file 文件元信息
 * @returns 成功时 `data` 为 key + 直传地址 + 有效期
 */
export function requestSignatureUploadToken(
  file: Pick<SignatureUploadInput, 'fileName' | 'size' | 'contentType'>,
): Promise<ApiResult<SignatureUploadToken>> {
  return post<SignatureUploadToken>('/api/v1/app/upload/token', {
    scene: SIGNATURE_SCENE,
    fileName: file.fileName,
    size: file.size,
    contentType: file.contentType,
  });
}

/**
 * 完整上传流程：申请凭证 → PUT 直传 → 返回对象 key。
 *
 * 副作用：把图片字节写到服务端对象存储。
 *
 * @param file 图片内容与元信息
 * @returns 成功时 `data` 为对象 key
 */
export async function uploadSignatureImage(file: SignatureUploadInput): Promise<ApiResult<string>> {
  const token = await requestSignatureUploadToken(file);
  if (!token.ok) {
    return token;
  }
  const uploaded = await putBinary(token.data.uploadUrl, file.body, file.contentType);
  if (!uploaded.ok) {
    return uploaded;
  }
  return { ok: true, data: token.data.key };
}

/**
 * 把 base64 dataURL 解码成 Blob。
 *
 * 为什么需要它：手写板确认后给的是 `data:image/png;base64,...`（透明底 PNG），
 * 而 `putBinary` 要 `Blob`。**不用 `fetch(dataUrl)`**：那会在业务代码里出现 `fetch`，
 * 违反「全仓只有 shared/api 允许 fetch」的收口规则。
 *
 * @param dataUrl `data:<mime>;base64,<data>` 形式的字符串
 * @returns 解码后的 Blob；格式非法或运行环境没有 `atob` 时返回 null（调用方给错误提示）
 */
export function dataUrlToBlob(dataUrl: string): Blob | null {
  const commaIndex = dataUrl.indexOf(',');
  if (commaIndex <= 0) {
    return null;
  }
  const header = dataUrl.slice(0, commaIndex);
  const base64 = dataUrl.slice(commaIndex + 1);
  if (base64 === '') {
    return null;
  }
  const mimeMatch = /^data:([^;]+)/.exec(header);
  const mimeType = mimeMatch === null ? 'image/png' : (mimeMatch[1] ?? 'image/png');
  if (typeof atob !== 'function') {
    return null;
  }
  let binary: string;
  try {
    binary = atob(base64);
  } catch {
    return null;
  }
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return new Blob([bytes], { type: mimeType });
}
