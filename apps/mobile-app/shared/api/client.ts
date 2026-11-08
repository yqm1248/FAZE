/**
 * 网络层唯一出口。
 *
 * 职责：拼 8 个请求头、解析 `R<T>`、归一错误、处理 401 双通道与单飞刷新、
 *      回读 `X-Trace-Id`。**全仓只有本文件与 `shared/api/sse.ts`（D3 再建）允许出现 `fetch`。**
 * 调用方：`features/<域>/api/*`；页面**不许**直接 import 本文件以外的 fetch 封装。
 * 数据来源：`extra.apiBaseUrl`（app.config.ts）、SecureStore（tokenStore）。
 * 注意：
 *   1. **不 import `expo-router`**：否则形成 `router → 页面 → api → router` 循环，
 *      且单测环境没有导航容器。登出通知走 `authEvents.emitUnauthorized()`。
 *   2. **永不向渲染层抛异常**：网络/超时/业务码/解析全部归一成 `{ ok: false }`（`0-A8` 的基础）。
 *   3. 401 三条硬约束缺一条就会无限刷新（B2「卡住怎么办」第 2 条）：
 *      ①只重放 1 次；②刷新自身的 401 不再触发刷新；③只对**带了 Authorization** 的请求判登出。
 *   4. `R.msg` 只进 `AppError` 的调试用途，页面展示一律走 `msgKey → t()`。
 */

import Constants from 'expo-constants';
import { Platform } from 'react-native';

import { emitUnauthorized } from './authEvents';
import { FALLBACK_ERROR_KEY, msgKeyOfCode, NETWORK_ERROR_KEY } from './errorCodes';
import {
  createUuidV4,
  expireStoredSession,
  getDeviceId,
  readSession,
  readStoredLang,
  writeSession,
} from './tokenStore';
import type { ApiEnvelope, ApiResult, AppError } from './types';

/**
 * 服务端基地址。**本文件是全仓唯一读取点。**
 *
 * fail-fast：缺配置直接抛错，不静默回退 localhost——静默回退在真机上表现为
 * 「一直网络错误」，是最难查的一类故障（B2 第 2 条）。
 */
const configuredBaseUrl = Constants.expoConfig?.extra?.apiBaseUrl as string | undefined;
if (!configuredBaseUrl) {
  throw new Error(
    '[api] extra.apiBaseUrl 未配置：检查 apps/mobile-app/.env 的 EXPO_PUBLIC_API_BASE_URL',
  );
}
const BASE_URL = configuredBaseUrl.replace(/\/+$/, '');

/** 普通请求超时（毫秒）。AI 流单独 120 秒，见 D3。 */
const DEFAULT_TIMEOUT_MS = 15_000;

/** 刷新令牌接口路径（接口规范 §7.1 冻结）。 */
const REFRESH_PATH = '/api/v1/app/auth/refresh';

/** 业务码：token 过期（可刷新）。 */
const CODE_TOKEN_EXPIRED = 402002;
/** 业务码：token 无效（直接登出）。 */
const CODE_TOKEN_INVALID = 402003;

/** 支持的 HTTP 方法。 */
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/** 单次请求的可选项。 */
export type RequestOptions = {
  /** 方法，默认 GET。 */
  method?: HttpMethod;
  /** 请求体（会被 JSON 序列化）。 */
  body?: unknown;
  /** 是否附加 Authorization；默认 true。登录接口必须传 false。 */
  auth?: boolean;
  /** 超时毫秒数，默认 15 秒。 */
  timeoutMs?: number;
  /**
   * 额外请求头（如下单的 `Idempotency-Key`）。
   *
   * 只允许业务头；`X-Request-Id` / `Accept-Language` / `Authorization` 这些由
   * `buildHeaders` 统一生成，**传进来也盖不掉**（后写覆盖会破坏 401 重放的链路一致性）。
   */
  headers?: Record<string, string>;
};

/**
 * 当前服务端地址（探针页要把它显示给验收人看）。
 *
 * @returns 去掉末尾斜杠的 baseURL
 */
export function getBaseUrl(): string {
  return BASE_URL;
}

/**
 * 发起一次请求。
 *
 * @param path    以 `/` 开头的接口路径
 * @param options 请求选项
 * @returns 成功时 `{ ok: true, data }`，失败时 `{ ok: false, error }`
 */
export function request<T>(path: string, options: RequestOptions = {}): Promise<ApiResult<T>> {
  // X-Request-Id 在**同一次逻辑请求**内复用：401 重放时沿用同一个 id，
  // 服务端才能把它与首次请求串成一条链路。
  const requestId = createUuidV4();
  return sendOnce<T>(path, options, requestId, false);
}

/**
 * GET 快捷方式。
 *
 * @param path    接口路径
 * @param options 其余选项（不含 method/body）
 * @returns 结果
 */
export function get<T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) {
  return request<T>(path, { ...options, method: 'GET' });
}

/**
 * POST 快捷方式。
 *
 * @param path    接口路径
 * @param body    请求体
 * @param options 其余选项
 * @returns 结果
 */
export function post<T>(
  path: string,
  body?: unknown,
  options?: Omit<RequestOptions, 'method' | 'body'>,
) {
  return request<T>(path, { ...options, method: 'POST', body });
}

/**
 * 直接 PUT 二进制内容到预签名上传地址。
 *
 * 职责：三段式上传的第二步（`POST /app/upload/token` → PUT 到 `uploadUrl` → 提交对象 key）。
 * 为什么放在本文件：全仓只有本文件允许出现 `fetch`（AGENTS §4.7 的收口点），
 * 上传虽然打的是另一个地址，仍是网络出口，不该在 features 里再开一个 `fetch`。
 * 注意：
 *   1. **不带 8 个业务请求头**：预签名地址的鉴权靠 URL 里的 `sig`/`exp`，
 *      带 `Authorization` 反而可能被存储端拒绝（它只校验签名）。
 *   2. 上传失败可能是 403（签名过期）或 413（超限），统一归一为 network，
 *      页面只需提示「上传失败，请重试」（重新申请凭证即可）。
 *
 * @param uploadUrl   服务端签发的可写地址
 * @param body        文件内容（web 传 `Blob`）
 * @param contentType 声明的 MIME 类型（必须与申请凭证时一致）
 * @param timeoutMs   超时毫秒数，默认 30 秒（图片上传比普通请求慢）
 * @returns 成功时 `{ ok: true, data: null }`
 */
export async function putBinary(
  uploadUrl: string,
  body: Blob,
  contentType: string,
  timeoutMs = 30_000,
): Promise<ApiResult<null>> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': contentType },
      body,
      signal: controller.signal,
    });
    if (!response.ok) {
      return { ok: false, error: networkError() };
    }
    return { ok: true, data: null };
  } catch {
    return { ok: false, error: networkError() };
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * 下载一个**已签名**的文件地址到本机（Web 走 blob + `<a download>`）。
 *
 * 职责：段 4 F1 的「导出 PDF」——把服务端现签的 60 秒地址变成一次真正的浏览器下载。
 * 为什么必须放在本文件：全仓只有本文件允许出现 `fetch`（AGENTS §4.7 的收口点），
 * 业务代码里再开一个 fetch 就是绕过统一出口。
 * 注意：
 *   1. **不带 8 个业务请求头**：预签名地址的鉴权靠 URL 里的 `sig`/`exp`，
 *      带 `Authorization` 反而可能被存储端拒绝（它只校验签名）。
 *   2. 为什么不用 `<a href download>` 直链：跨域时浏览器**忽略 `download` 属性**，
 *      会变成"新标签页打开 PDF"，就没法保证"存进下载目录"。先 fetch 成 blob 再下载，
 *      下载行为与同源一致。
 *   3. 非 Web 平台返回 false（原生端留段 8：`expo-file-system` + `expo-sharing`）。
 *
 * @param url      服务端现签的下载地址
 * @param fileName 建议文件名（含扩展名）
 * @returns 是否已触发下载
 */
export async function downloadFromUrl(url: string, fileName: string): Promise<boolean> {
  if (typeof document === 'undefined') {
    return false;
  }
  try {
    const response = await fetch(url);
    if (!response.ok) {
      return false;
    }
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = fileName;
    anchor.rel = 'noopener';
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    // 立即 revoke 会让部分浏览器来不及取数据，给 1 秒缓冲
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    return true;
  } catch {
    return false;
  }
}

/**
 * 实际发送一次（含 401 重放）。
 *
 * @param path        接口路径
 * @param options     请求选项
 * @param requestId   逻辑请求 id（重放复用）
 * @param alreadyRetried 是否已经因 401 重放过一次
 * @returns 结果
 */
async function sendOnce<T>(
  path: string,
  options: RequestOptions,
  requestId: string,
  alreadyRetried: boolean,
): Promise<ApiResult<T>> {
  const session = await readSession();
  const useAuth = options.auth !== false && session !== null;
  const accessToken = useAuth ? session.accessToken : null;

  const headers = await buildHeaders(requestId, accessToken, options.body !== undefined, options.headers);
  const url = BASE_URL + (path.startsWith('/') ? path : `/${path}`);
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), options.timeoutMs ?? DEFAULT_TIMEOUT_MS);

  let response: Response;
  try {
    response = await fetch(url, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    });
  } catch {
    // 断网 / 超时 / DNS 失败 —— 一律 network，不把原始异常抛给渲染层。
    return { ok: false, error: networkError() };
  } finally {
    clearTimeout(timeoutId);
  }

  const traceIdHeader = response.headers.get('X-Trace-Id');

  let rawText: string;
  try {
    rawText = await response.text();
  } catch {
    return { ok: false, error: networkError() };
  }

  let envelope: ApiEnvelope<T> | null = null;
  try {
    envelope = rawText === '' ? null : (JSON.parse(rawText) as ApiEnvelope<T>);
  } catch {
    envelope = null;
  }

  // ── 401 双通道（接口规范 §6.1.1）────────────────────────────────────────
  if (response.status === 401 && useAuth && envelope !== null) {
    if (envelope.code === CODE_TOKEN_EXPIRED && !alreadyRetried) {
      const refreshed = await refreshSessionOnce();
      if (refreshed) {
        // 只重放 1 次；复用同一个 requestId
        return sendOnce<T>(path, options, requestId, true);
      }
    }
    if (envelope.code === CODE_TOKEN_EXPIRED || envelope.code === CODE_TOKEN_INVALID) {
      await handleUnauthorized(accessToken);
      return { ok: false, error: businessError(envelope) };
    }
  }

  // 不带 Authorization 的 401（例如登录密码错）：**不判登出**，只当业务错误。
  // 少了这条判断，用户在登录页输错密码会被「踢回登录页」——他本来就在登录页。
  if (response.status === 401 && !useAuth) {
    return {
      ok: false,
      error: envelope === null ? networkError() : businessError(envelope),
    };
  }

  if (envelope === null) {
    return { ok: false, error: parseError(traceIdHeader) };
  }
  if (envelope.code !== 200) {
    return { ok: false, error: businessError(envelope) };
  }
  return { ok: true, data: envelope.data as T };
}

/**
 * 组装 8 个请求头。
 *
 * 接口规范 §4.1 的表实际是 8 行（`X-Trace-Id` 是**响应**头，不是请求头）；
 * 本函数与表一一对应，多一个少一个都算偏离契约。
 *
 * @param requestId   逻辑请求 id
 * @param accessToken access token（无则不带 Authorization）
 * @param hasBody     是否有请求体（决定是否带 Content-Type）
 * @returns 请求头对象
 */
async function buildHeaders(
  requestId: string,
  accessToken: string | null,
  hasBody: boolean,
  extra?: Record<string, string>,
): Promise<Record<string, string>> {
  const lang = (await readStoredLang()) ?? 'zh-Hans';
  const deviceId = await getDeviceId();
  const headers: Record<string, string> = {
    'X-Request-Id': requestId,
    'Accept-Language': lang,
    'X-Client-Version': Constants.expoConfig?.version ?? '0.0.0',
    'X-Client-Platform': Platform.OS,
    'X-Device-Id': deviceId,
  };
  if (hasBody) {
    headers['Content-Type'] = 'application/json;charset=UTF-8';
  }
  if (accessToken !== null) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  // 业务额外头放最后，但**不允许覆盖上面 6 个框架头**：覆盖 X-Request-Id 会让
  // 服务端把同一次逻辑请求的两次重放当成两个请求（401 重放的链路一致性靠它）。
  if (extra !== undefined) {
    for (const [name, value] of Object.entries(extra)) {
      if (!(name in headers)) {
        headers[name] = value;
      }
    }
  }
  return headers;
}

/** 正在进行的刷新（单飞）。null 表示当前没有刷新在飞。 */
let refreshing: Promise<boolean> | null = null;

/**
 * 单飞刷新会话。
 *
 * 并发调用共享同一个 Promise：同一时刻只允许**一个**刷新请求在飞，其余等待它的结果
 * （接口规范 §6.1「单飞」）。少了它，轮换会让其余并发请求全部 401。
 *
 * @returns 是否刷新成功
 */
export function refreshSessionOnce(): Promise<boolean> {
  if (refreshing === null) {
    refreshing = doRefresh().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

/**
 * 真正执行一次刷新。
 *
 * 用裸 `fetch` 而不是 `request()`：避免「刷新失败 → 再触发刷新」的递归。
 * 刷新接口本身返回 401 时直接判失败，**不再尝试刷新**。
 *
 * @returns 是否成功
 */
async function doRefresh(): Promise<boolean> {
  const session = await readSession();
  if (session === null) {
    return false;
  }
  const headers = await buildHeaders(createUuidV4(), null, true);
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);
  try {
    const response = await fetch(BASE_URL + REFRESH_PATH, {
      method: 'POST',
      headers,
      body: JSON.stringify({ refreshToken: session.refreshToken }),
      signal: controller.signal,
    });
    if (response.status === 401) {
      // 刷新本身 401：说明 refresh token 也失效了，直接登出，不许再刷新。
      return false;
    }
    const body = (await response.json()) as ApiEnvelope<{
      accessToken: string;
      refreshToken: string;
    }>;
    if (body.code !== 200 || body.data === null) {
      return false;
    }
    await writeSession({
      accessToken: body.data.accessToken,
      refreshToken: body.data.refreshToken,
    });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * 处理鉴权失败：只清「被拒的那个 token」对应会话，然后通知根布局回登录页。
 *
 * @param rejectedAccessToken 被判 401 的 access token（可能为 null）
 */
async function handleUnauthorized(rejectedAccessToken: string | null): Promise<void> {
  if (rejectedAccessToken !== null) {
    const cleared = await expireStoredSession(rejectedAccessToken);
    if (!cleared) {
      // 迟到 401：会话已经换成新的了，丢弃本次登出，否则用户「刚登录就被踢」。
      return;
    }
  }
  emitUnauthorized();
}

/**
 * 业务错误归一。
 *
 * @param envelope 服务端响应
 * @param headers  响应头（段 1 E2：支付密码锁定/剩余次数走响应头，见 `types.ts`）
 * @returns business 类型错误
 */
function businessError<T>(envelope: ApiEnvelope<T>, headers?: Headers): AppError {
  const base: AppError = {
    kind: 'business',
    code: envelope.code,
    msgKey: msgKeyOfCode(envelope.code),
    traceId: envelope.traceId,
  };
  if (headers === undefined) {
    return base;
  }
  // 动态数字只认响应头：`msg` 是固定文案，塞动态值会让文案与翻译失效（后端 E2 的设计取舍）。
  const retryAfter = readPositiveInt(headers.get('Retry-After'));
  const remaining = readPositiveInt(headers.get('X-Pay-Pwd-Remaining'));
  if (retryAfter !== null) {
    base.retryAfterSeconds = retryAfter;
  }
  if (remaining !== null) {
    base.remainingAttempts = remaining;
  }
  return base;
}

/**
 * 把响应头里的整数字符串读成非负数；缺失或非法返回 null。
 *
 * @param raw 响应头原值
 * @returns 非负整数或 null
 */
function readPositiveInt(raw: string | null): number | null {
  if (raw === null || raw.trim() === '') {
    return null;
  }
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0) {
    return null;
  }
  return Math.trunc(value);
}

/**
 * 网络错误归一。
 *
 * @returns network 类型错误
 */
function networkError(): AppError {
  return { kind: 'network', msgKey: NETWORK_ERROR_KEY, traceId: null };
}

/**
 * 解析错误归一。
 *
 * @param traceId 响应头里的链路 id（有则带上）
 * @returns parse 类型错误
 */
function parseError(traceId: string | null): AppError {
  return { kind: 'parse', msgKey: FALLBACK_ERROR_KEY, traceId };
}
