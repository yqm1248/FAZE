/**
 * 三段式上传接口（申请凭证 → PUT 直传 → 回传对象 key）。
 *
 * 职责：把「上传一张证件照/头像」收敛成一次调用，页面只拿到最终 key。
 * 调用方：`features/account/pages/RealnamePage.tsx`、`LawyerJoinPage.tsx`、
 *         `features/settings/pages/EditProfilePage.tsx`。
 * 数据来源：`POST /api/v1/app/upload/token`（申请凭证）→ `PUT uploadUrl`（直传）
 *           → `GET /api/v1/app/upload/url`（预览，只对属主签发）。
 * 注意：
 *   1. 文件字节**不经过 faze-api**：本地实现（方案 B）的直传端点在 `/api/v1/pub/file/put`，
 *      换 OSS 后同一段代码不用改。
 *   2. `scene` 白名单：`realname` / `identity` / `license` / `avatar`（后端正则校验）。
 *   3. 文件名只用于取扩展名；服务端不接受中文名/`../` 之类路径片段。
 *   4. **key 必须原样回传**给业务接口（如 `realname.idCardFront`），不许自己拼。
 */

import { get, post, putBinary } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 上传场景（后端白名单）。 */
export type UploadScene = 'realname' | 'identity' | 'license' | 'avatar';

/** 上传凭证出参（`UploadTokenVO`）。 */
export type UploadTokenData = {
  /** 对象 key，业务提交时原样回传。 */
  key: string;
  /** 可 PUT 的直传地址。 */
  uploadUrl: string;
  /** 有效期（秒）。 */
  expiresIn: number;
};

/** 下载地址出参（`FileStorage.PresignedUrl`）。 */
export type DownloadUrlData = {
  url: string;
  expiresIn: number;
};

/** 待上传的文件（由页面把浏览器 `File` 转成这个形状）。 */
export type UploadFileInput = {
  /** 原始文件名（仅用于取扩展名）。 */
  fileName: string;
  /** 文件字节数。 */
  size: number;
  /** MIME 类型（白名单：image/jpeg、image/png、image/webp、application/pdf）。 */
  contentType: string;
  /** 文件内容。 */
  body: Blob;
};

/**
 * 申请上传凭证。
 *
 * @param scene 场景
 * @param file  文件元信息
 * @returns 成功时 `data` 为 key + 直传地址 + 有效期
 */
export function requestUploadToken(
  scene: UploadScene,
  file: Pick<UploadFileInput, 'fileName' | 'size' | 'contentType'>,
): Promise<ApiResult<UploadTokenData>> {
  return post<UploadTokenData>('/api/v1/app/upload/token', {
    scene,
    fileName: file.fileName,
    size: file.size,
    contentType: file.contentType,
  });
}

/**
 * 完整上传流程：申请凭证 → PUT 直传 → 返回对象 key。
 *
 * 副作用：把文件写到服务端存储。
 *
 * @param scene 场景
 * @param file  文件内容与元信息
 * @returns 成功时 `data` 为对象 key
 */
export async function uploadFile(
  scene: UploadScene,
  file: UploadFileInput,
): Promise<ApiResult<string>> {
  const token = await requestUploadToken(scene, file);
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
 * 取短时下载地址（预览已上传的证件照 / 头像）。
 *
 * @param key 对象 key
 * @returns 成功时 `data` 为地址与有效期
 */
export function getDownloadUrl(key: string): Promise<ApiResult<DownloadUrlData>> {
  return get<DownloadUrlData>(`/api/v1/app/upload/url?key=${encodeURIComponent(key)}`);
}
