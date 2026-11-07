/**
 * 文书后处理出口（段 4 F1）——下载 PDF / 分享 / 打印 / 存相册。
 *
 * 职责：把「把一份真 PDF 交给浏览器」的四个出口收敛成一个模块，页面只调这里，
 *      不在 `DocPreviewPage.tsx` 里直接写 `<a>` / `window.print` / `navigator.share`。
 * 调用方：`features/document/pages/DocPreviewPage.tsx` 的导出面板。
 * 数据来源：`GET /app/documents/{id}/export?format=pdf`（`./client.ts` 的 `fetchExport`），
 *          返回 `{ url, fileName, expiresIn: 60 }`——**现签的 60 秒地址**，不缓存、不复用。
 * 注意：
 *   1. **浏览器验收口径**（负责人 2026-11-07 裁决，工作流 §三 F1）：段 4 只验 Web 形态；
 *      系统分享面板、存相册、打印指定的本地 PDF 一律留段 8，本段只验「接口层 + 浏览器降级形态」。
 *   2. **存相册本段不做**：`expo-media-library` 没有 Web 实现，`isGallerySupported()` 恒为 false，
 *      页面据此把按钮置灰并说明原因（挂账见函数注释里的 TODO）。
 *   3. **分享**用 `navigator.share`；浏览器没有这个能力时返回 `unsupported`，
 *      页面把按钮置灰 + 说明「当前浏览器不支持系统分享」。**不假装可用**
 *      （工作流 F1 的「最常见糊弄」：装了 expo-sharing 就当分享可用）。
 *   4. **打印**用 `window.print()`：浏览器只允许打印**当前页面**，不允许在客户端指定某个本地 PDF；
 *      这一条在证据里记成「浏览器限制」，不判失败。
 *   5. **下载走 `shared/api/client.ts` 的 `downloadFromUrl(url, fileName)`**（fetch → Blob →
 *      `<a download>` → revoke）：它放在网络出口里，所以跨域也能真的存进下载目录，
 *      同时不违反「全仓只有 `shared/api` 允许出现 `fetch`」的收口规则（AGENTS §4.7）。
 *      返回 `false` 时退回直链新标签页兜底，页面按「下载失败，请重试」提示。
 *   6. 所有 DOM 能力都用 `Platform.OS === 'web'` 与 `typeof` 判空保护：原生端调用只返回
 *      `unsupported`，不抛异常、不白屏。
 */

import { Platform } from 'react-native';

import { downloadFromUrl } from '@/shared/api/client';
import type { AppError } from '@/shared/api/types';

import { fetchExport } from './client';

/** 四个出口。 */
export type ExportKind = 'download' | 'share' | 'print' | 'gallery';

/**
 * 出口执行结果。
 *
 * 为什么要三种而不是 `ApiResult`：`unsupported`（当前浏览器/平台没有这个能力）既不是
 * 网络错误也不是业务错误，混进 `AppError` 会把「能力缺失」伪装成「请求失败」，
 * 页面就没法区分「按钮该置灰」和「该弹重试」。
 */
export type ExportOutcome =
  | {
      /** 动作已交给浏览器（下载已存盘 / 打印面板已调起 / 分享面板已打开）。 */
      status: 'done';
      /** 建议文件名（打印没有文件名，给空串）。 */
      fileName: string;
      /**
       * 下载的落地方式：
       * - `blob`：走了 `downloadFromUrl`（fetch → Blob → `<a download>`），**真的落进下载目录**。
       */
      via?: 'blob';
    }
  | {
      /** 当前平台或浏览器没有这个能力，页面应把按钮置灰并说明原因。 */
      status: 'unsupported';
    }
  | {
      /** 业务/网络失败（归一后的错误，页面按 `msgKey` 走 `t()`）。 */
      status: 'failed';
      error: AppError;
    };

/**
 * 当前是否跑在 Web 运行时（浏览器验收的唯一形态）。
 *
 * @returns web 为 true；iOS / Android 为 false
 */
function isWebRuntime(): boolean {
  return Platform.OS === 'web';
}

/**
 * 下载能力是否可用。
 *
 * 段 4 只有浏览器形态：原生端要落 `expo-file-system` + 下载管理器（留段 8），
 * 所以这里按 Web 运行时判断，页面据此把「导出 PDF」置灰。
 *
 * @returns web 为 true
 */
export function isDownloadSupported(): boolean {
  return isWebRuntime();
}

/**
 * 分享能力是否可用（`navigator.share`）。
 *
 * 注意：`navigator.share` 只在**安全上下文**（https 或 localhost）存在；
 * 桌面 Chrome 是否暴露它取决于系统「分享」集成，所以必须运行时判断，不能按「装了什么包」推断。
 *
 * @returns 可用为 true
 */
export function isShareSupported(): boolean {
  if (!isWebRuntime()) {
    return false;
  }
  if (typeof navigator === 'undefined') {
    return false;
  }
  return typeof navigator.share === 'function';
}

/**
 * 打印能力是否可用（`window.print`）。
 *
 * @returns web 为 true
 */
export function isPrintSupported(): boolean {
  if (!isWebRuntime()) {
    return false;
  }
  return typeof window !== 'undefined' && typeof window.print === 'function';
}

/**
 * 存相册能力是否可用。
 *
 * **本段恒为 false**：`expo-media-library` 没有 Web 实现，真机能力按工作流 §三 F1 留段 8。
 *
 * TODO(段 8)：接入 `expo-media-library` 的 `saveToLibraryAsync`（原生），
 * 并把 `下载 → 存相册` 的授权流程补上；段 4 的验收口径明确不判通过这一项。
 *
 * @returns 恒为 false（当前段）
 */
export function isGallerySupported(): boolean {
  return false;
}

/**
 * 打开直链（下载失败时的兜底出口，不读文件字节）。
 *
 * 为什么留它：`downloadFromUrl` 在极端浏览器（禁止 Blob 下载、内存受限）可能返回 false，
 * 此时至少让用户能在新标签页打开 PDF 自己另存，而不是什么都没发生。
 *
 * 副作用：向 DOM 插入一个 a 元素并 `click()`，随后移除。
 *
 * @param url 现签的 60 秒下载地址
 */
function openDirectLink(url: string): void {
  if (typeof document === 'undefined') {
    return;
  }
  const anchor = document.createElement('a');
  anchor.href = url;
  // 新窗口打开时断开 opener，避免被下载页反向控制（同源策略之外的稳妥做法）
  anchor.rel = 'noopener noreferrer';
  anchor.target = '_blank';
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}

/**
 * 下载 PDF（F1 出口 1）。
 *
 * 副作用：先向服务端现签一次 60 秒地址（这一步会写接口日志），
 * 再通过 `downloadFromUrl` 把字节取回并触发存盘；失败时退回新标签页直链。
 *
 * @param documentId 文书主键（字符串形式的 Long，禁止 `Number()`）
 * @returns 成功时 `status === 'done'`；失败时带归一后的错误
 */
export async function downloadDocument(documentId: string): Promise<ExportOutcome> {
  if (!isWebRuntime()) {
    return { status: 'unsupported' };
  }
  const info = await fetchExport(documentId);
  if (!info.ok) {
    return { status: 'failed', error: info.error };
  }
  const saved = await downloadFromUrl(info.data.url, info.data.fileName);
  if (saved) {
    return { status: 'done', fileName: info.data.fileName, via: 'blob' };
  }
  // Blob 下载失败（极少数浏览器策略/内存限制）：至少把直链打开，让用户能自己另存。
  openDirectLink(info.data.url);
  return {
    status: 'failed',
    error: { kind: 'network', msgKey: 'error.common.network', traceId: null },
  };
}

/**
 * 判断异常是不是「用户主动取消分享」。
 *
 * `navigator.share` 在用户点取消时 reject `AbortError`——那不是失败，
 * 当成失败弹「分享失败」会让用户以为自己操作错了。
 *
 * @param error 捕获到的异常（`unknown`，不假设形状）
 * @returns 是取消则为 true
 */
function isShareAbort(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  return (error as { name?: unknown }).name === 'AbortError';
}

/**
 * 分享 PDF（F1 出口 2）。
 *
 * 副作用：先现签 60 秒地址，再调 `navigator.share`。分享出去的是**现签 URL**，
 * 因此 60 秒后失效——真正的「分享文件本体」要等段 8（把文件读成 `File` 后走 `navigator.share({ files })`，
 * 那需要先给 `shared/api/client.ts` 加 blob 读取能力，见文件头第 5 条）。
 *
 * @param documentId 文书主键
 * @param title      分享标题（已 `t()`，一般是文书标题）
 * @returns 可用性 / 成功 / 失败三态
 */
export async function shareDocument(documentId: string, title: string): Promise<ExportOutcome> {
  if (!isShareSupported()) {
    return { status: 'unsupported' };
  }
  const info = await fetchExport(documentId);
  if (!info.ok) {
    return { status: 'failed', error: info.error };
  }
  try {
    await navigator.share({ title, text: info.data.fileName, url: info.data.url });
    return { status: 'done', fileName: info.data.fileName };
  } catch (error) {
    if (isShareAbort(error)) {
      // 用户取消：不报错，但也不算「分享成功」——用 done + 空文件名的语义会让页面提示「已分享」，
      // 所以这里给 failed，页面文案是「分享已取消或失败」。
      return { status: 'failed', error: { kind: 'parse', msgKey: 'error.common.server', traceId: null } };
    }
    return { status: 'failed', error: { kind: 'parse', msgKey: 'error.common.server', traceId: null } };
  }
}

/**
 * 打印当前页面（F1 出口 4）。
 *
 * 副作用：调起浏览器打印面板（`window.print()`）。
 *
 * 浏览器限制：只能打印当前页面（App 界面），**不能**在客户端指定某个本地 PDF 去打印；
 * 工作流 §三 F1 明确「不验『打印指定本地 PDF』」，这一条记为浏览器限制，不判失败。
 *
 * @returns 成功 / 不支持两态
 */
export function printCurrentPage(): ExportOutcome {
  if (!isPrintSupported()) {
    return { status: 'unsupported' };
  }
  window.print();
  return { status: 'done', fileName: '' };
}
