/**
 * SSE 流式读取的唯一入口。
 *
 * 职责：用 `expo/fetch` 的 `ReadableStream` 逐块读 SSE，按事件切分后回调。
 * 调用方：`features/dev/pages/ProbePage.tsx`（段 0）、段 3 的 AI 问答（复用同一套）。
 * 数据来源：服务端 `POST /api/v1/pub/probe/ai`（段 0）、`/app/ai/stream`（段 3）。
 * 注意：
 *   1. **必须用 `expo/fetch`，不能用 RN 全局 `fetch`**：RN 0.81 的全局 fetch 建在 XHR 上
 *      （`whatwg-fetch` → `XMLHttpRequest`），`response.body` 是 `undefined`、没有 `getReader()`。
 *      这条是实测结论（见第 0 段工作流 D3 第 3 条），照接口规范 §6.7 写会拿到 undefined。
 *   2. **不用 `EventSource`**：它不能带自定义头（token 只能进 URL，违反「禁止 token 放 URL」），
 *      且只支持 GET，而探针是 POST。
 *   3. `getReader()` **只允许出现在本文件**（D3 算做完第 7 条），别处一律经本模块。
 *   4. `TextDecoder` 在 Hermes 上不一定存在：先用 `typeof` 探测，缺失时抛明确错误，
 *      由调用方决定降级（不要在 Hermes 里引 `buffer` 包）。
 */

import { fetch } from 'expo/fetch';

/** 一个 SSE 事件。 */
export type SseEvent = {
  /** 事件名（`event:` 后的值）；未写时为空串。 */
  event: string;
  /** 数据（`data:` 后的原始字符串，多行 data 用换行连接）。 */
  data: string;
};

/** 流式请求参数。 */
export type SseRequest = {
  /** 完整 URL。 */
  url: string;
  /** 请求方法，默认 POST。 */
  method?: 'GET' | 'POST';
  /** 请求头。 */
  headers?: Record<string, string>;
  /** 请求体（JSON 字符串）。 */
  body?: string;
  /** 外部取消信号。 */
  signal?: AbortSignal;
};

/** 默认超时（毫秒）——AI 流最长 120 秒，见接口规范 §6.7。 */
const DEFAULT_TIMEOUT_MS = 120_000;

/**
 * 判断当前运行时是否支持 `TextDecoder`。
 *
 * @returns true 表示可用
 */
export function isTextDecoderAvailable(): boolean {
  return typeof TextDecoder !== 'undefined';
}

/**
 * 读取一个 SSE 流并逐事件回调。
 *
 * 副作用：发起网络请求；每解析出一个完整事件就调用一次 `onEvent`。
 * 本函数**不抛业务异常**：网络/解析错误以 rejection 形式返回，由调用方归一。
 *
 * @param request 请求参数
 * @param onEvent 事件回调（按到达顺序）
 * @returns 流结束（或 `[DONE]`）时 resolve
 * @throws Error `TextDecoder` 不可用，或响应没有 body
 */
export async function streamSse(request: SseRequest, onEvent: (event: SseEvent) => void): Promise<void> {
  if (!isTextDecoderAvailable()) {
    throw new Error('[sse] 当前运行时没有 TextDecoder，无法解码流');
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);
  const externalSignal = request.signal;

  /**
   * 转发外部取消信号。
   *
   * 用 `addEventListener` 而不是赋值 `onabort`：多处订阅时后者会互相覆盖。
   */
  const onExternalAbort = () => controller.abort();
  externalSignal?.addEventListener('abort', onExternalAbort);

  try {
    const response = await fetch(request.url, {
      method: request.method ?? 'POST',
      headers: request.headers,
      body: request.body,
      signal: controller.signal,
    });

    if (response.body === null) {
      throw new Error('[sse] 响应没有 body（是否用了 RN 全局 fetch？必须用 expo/fetch）');
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      // stream: true 处理跨块的多字节字符（中文/藏文/阿拉伯文都会被切断）
      buffer += decoder.decode(value, { stream: true });

      // SSE 事件以空行分隔；兼容 \r\n\r\n
      let boundary = buffer.indexOf('\n\n');
      while (boundary >= 0) {
        const chunk = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const parsed = parseChunk(chunk);
        if (parsed !== null) {
          onEvent(parsed);
          if (parsed.data === '[DONE]') {
            return;
          }
        }
        boundary = buffer.indexOf('\n\n');
      }
    }

    // 流结束时可能还剩最后一个无空行结尾的事件
    const tail = parseChunk(buffer);
    if (tail !== null) {
      onEvent(tail);
    }
  } finally {
    clearTimeout(timeoutId);
    externalSignal?.removeEventListener('abort', onExternalAbort);
  }
}

/**
 * 解析一个 SSE 事件块（`event:` 与 `data:` 行）。
 *
 * @param chunk 不含末尾空行的块
 * @returns 事件对象；注释块（`: ping`）或空块返回 null
 */
function parseChunk(chunk: string): SseEvent | null {
  let eventName = '';
  const dataLines: string[] = [];

  for (const rawLine of chunk.split('\n')) {
    const line = rawLine.replace(/\r$/, '');
    if (line.length === 0 || line.startsWith(':')) {
      continue;
    }
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim();
      continue;
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart());
    }
  }

  if (dataLines.length === 0) {
    return null;
  }
  return { event: eventName, data: dataLines.join('\n') };
}
