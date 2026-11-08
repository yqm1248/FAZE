/**
 * AI 流式对话状态机（F3 流式 + F4 四态 + F5 停止/重连）。
 *
 * 职责：把「C2 提交 → C3 SSE 订阅 → start/delta/refs/done/error 事件 → 落 UI」收敛在一个 Hook 里，
 * 并实现：停止（AbortController）、断线重连（最多 3 次，1s→2s→4s，带 fromSeq，不新建气泡）、
 * 失败重试、降级模式切换（`ai_enabled=false` 或 C2 返回 `degraded=true`）。
 * 调用方：`features/ai/pages/AiChatPage.tsx`。
 * 数据来源：`features/ai/api/ai.ts`（JSON + SSE）。
 * 注意：
 *   1. **打字机就是「收到 delta 直接追加」**（工作流 F3 第 3 条）：不做本地 60ms 匀速动画，
 *      真流式的节奏已经足够；加动画只会让字落后于服务端。
 *   2. **重连不新建气泡**：复用同一个 `assistantId` 与同一个 `streamPath`，只把 `fromSeq`
 *      换成已收到的 delta 条数（服务端 `delta` 事件当前不带 seq，见 `api/ai.ts` 第 3 条）。
 *   3. **用户主动停止后不重连**：`stop()` 先置 `userStoppedRef` 再 abort，异常分支据此区分
 *      「网络断了」与「我点停止」。半截内容保留（C4 语义）。
 *   4. **业务 error 事件不重连**（回答已过期 508001 / 生成中断）：重连只会重复报错；
 *      只有网络/读取异常才走重连。重连 3 次仍失败 → 气泡标记 `failed`，页面给重试按钮。
 *   5. 消息数组**最新在前**（`FlatList inverted` 的 data[0] 渲染在底部）。
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import {
  getPublicAiConfig,
  parseEventData,
  streamAnswer,
  submitQuestion,
} from '@/features/ai/api/ai';
import type {
  LawRef,
  StreamDone,
  StreamErrorPayload,
  StreamStart,
} from '@/features/ai/api/ai';
import type { MessageKey } from '@/shared/i18n';
import type { SseEvent } from '@/shared/api/sse';

/** 重连上限（F5：最多 3 次）。 */
const MAX_RECONNECT_ATTEMPTS = 3;

/** 首次重连延迟（F5：1s→2s→4s）。 */
const RECONNECT_BASE_DELAY_MS = 1_000;

/** 「回答已过期」业务码（C3 第 6 条 / F5 卡住项）。 */
const CODE_STREAM_EXPIRED = 508001;

/** 单条对话消息（UI 状态）。 */
export type ChatMessage = {
  /** 本地唯一 id（列表 key；assistant 的本地 id 在 done 前一直用）。 */
  id: string;
  /** 角色。 */
  role: 'user' | 'assistant';
  /** 文本内容（流式过程中不断追加）。 */
  content: string;
  /** 服务端真实消息 id（`start` / `done` 事件带回；user 消息为空）。 */
  serverId?: string;
  /** 是否「思考中 / 重连中」占位（true 时气泡显示占位文案）。 */
  pending?: boolean;
  /** 法条引用快照（`refs` 事件）。 */
  refs?: LawRef[];
  /** 是否 D3 降级（done.degraded），true 时显示「转人工律师」。 */
  degraded?: boolean;
  /** 是否失败（网络重连耗尽 / 业务 error），页面给重试按钮。 */
  failed?: boolean;
  /** 失败原因文案 key（页面用 `t()` 显示）。 */
  failureKey?: MessageKey;
  /** 是否用户主动停止。 */
  stopped?: boolean;
};

/** `useAiStream` 的选项。 */
export type UseAiStreamOptions = {
  /** 每轮回答成功结束后的回调（页面用来刷新配额）。 */
  onDone?: () => void;
};

/** `useAiStream` 的返回值。 */
export type UseAiStreamResult = {
  /** 消息列表（最新在前）。 */
  messages: ChatMessage[];
  /** 是否正在生成（true 时发送按钮变停止）。 */
  generating: boolean;
  /** 是否在重连等待/重试中（与 `generating` 同时为 true）。 */
  reconnecting: boolean;
  /** 首次进入页面时的「模式探测」加载态。 */
  modeLoading: boolean;
  /** 是否处于降级模式（`ai_enabled=false` 或 C2 返回 degraded）。 */
  degradedMode: boolean;
  /** 发送一个问题（会追加 user + assistant 两条消息）。 */
  send: (question: string) => void;
  /** 停止生成（保留半截内容，不重连）。 */
  stop: () => void;
  /** 重试上一条失败的问题（移除旧气泡后重新提交，F4「重试 = 重新提交问题」）。 */
  retry: () => void;
  /** 重新探测 AI 开关（恢复 AI 模式；F4 第 4 条「重进页面恢复」也走这里）。 */
  refreshMode: () => void;
  /** 把当前会话从页面状态中清掉（F2 的清空按钮用；不调后端删除）。 */
  clear: () => void;
};

/**
 * AI 流式对话 Hook。
 *
 * 副作用：网络请求（读取开关、提交问题、订阅 SSE、重连定时器）。
 *
 * @param options.onDone 每轮正常结束后的回调
 * @returns 消息、状态与操作函数
 */
export function useAiStream(options: UseAiStreamOptions = {}): UseAiStreamResult {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [generating, setGenerating] = useState(false);
  const [reconnecting, setReconnecting] = useState(false);
  const [modeLoading, setModeLoading] = useState(true);
  const [degradedMode, setDegradedMode] = useState(false);

  const generatingRef = useRef(false);
  const conversationIdRef = useRef<string | null>(null);
  const seqRef = useRef(0);
  const doneRef = useRef(false);
  const attemptRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const userStoppedRef = useRef(false);
  const lastQuestionRef = useRef<string | null>(null);
  const lastPairRef = useRef<{ userId: string; assistantId: string } | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mountedRef = useRef(true);
  const onDoneRef = useRef<UseAiStreamOptions['onDone']>(options.onDone);

  useEffect(() => {
    onDoneRef.current = options.onDone;
  }, [options.onDone]);

  useEffect(() => {
    return () => {
      mountedRef.current = false;
      if (reconnectTimerRef.current !== null) {
        clearTimeout(reconnectTimerRef.current);
      }
      abortRef.current?.abort();
    };
  }, []);

  /** 按 id 改一条消息（支持函数式 patch）。 */
  const patchMessage = useCallback(
    (id: string, patch: Partial<ChatMessage> | ((message: ChatMessage) => Partial<ChatMessage>)): void => {
      setMessages((prev) =>
        prev.map((message) =>
          message.id === id
            ? { ...message, ...(typeof patch === 'function' ? patch(message) : patch) }
            : message,
        ),
      );
    },
    [],
  );

  /** 追加一段流式文本（打字机）。 */
  const appendDelta = useCallback((id: string, chunk: string): void => {
    setMessages((prev) =>
      prev.map((message) =>
        message.id === id
          ? { ...message, content: message.content + chunk, pending: false }
          : message,
      ),
    );
  }, []);

  /** 结束生成（统一收口）。 */
  const finish = useCallback((): void => {
    generatingRef.current = false;
    setGenerating(false);
    setReconnecting(false);
  }, []);

  /** 读一次 AI 开关（F4：只有显式 false 才降级）。 */
  const loadMode = useCallback((): void => {
    setModeLoading(true);
    void (async () => {
      const result = await getPublicAiConfig();
      if (!mountedRef.current) {
        return;
      }
      // 读不到配置不阻断：按「开启」处理，避免接口抖动把用户挡在降级页。
      setDegradedMode(result.ok && result.data.ai_enabled === false);
      setModeLoading(false);
    })();
  }, []);

  useEffect(() => {
    loadMode();
  }, [loadMode]);

  /** 处理一个 SSE 事件。 */
  const handleEvent = useCallback(
    (event: SseEvent, assistantId: string): void => {
      if (event.event === 'start') {
        const data = parseEventData<StreamStart>(event.data);
        if (data !== null) {
          patchMessage(assistantId, { serverId: String(data.messageId) });
        }
        return;
      }
      if (event.event === 'delta') {
        const data = parseEventData<{ content?: string }>(event.data);
        if (data !== null && typeof data.content === 'string') {
          // 服务端 delta 不带 seq：用收到的条数当 fromSeq（见文件头注释第 2 条）。
          seqRef.current += 1;
          appendDelta(assistantId, data.content);
        }
        return;
      }
      if (event.event === 'refs') {
        const data = parseEventData<{ refs?: LawRef[] }>(event.data);
        if (data !== null && Array.isArray(data.refs)) {
          patchMessage(assistantId, { refs: data.refs });
        }
        return;
      }
      if (event.event === 'done') {
        const data = parseEventData<StreamDone>(event.data);
        doneRef.current = true;
        patchMessage(assistantId, (message) => ({
          pending: false,
          serverId: data === null ? message.serverId : String(data.messageId),
          degraded: data !== null && data.degraded === true,
          content: data !== null && typeof data.finalContent === 'string' ? data.finalContent : message.content,
        }));
        finish();
        onDoneRef.current?.();
        return;
      }
      if (event.event === 'error') {
        const data = parseEventData<StreamErrorPayload>(event.data);
        // 业务错误不重连（C3 的 508001 等），直接把气泡标失败并结束。
        doneRef.current = true;
        patchMessage(assistantId, {
          pending: false,
          failed: true,
          failureKey: data !== null && data.code === CODE_STREAM_EXPIRED ? 'ai.chat.expired' : 'ai.chat.errorInterrupted',
        });
        finish();
      }
    },
    [appendDelta, finish, patchMessage],
  );

  /**
   * 建立一次 SSE 连接并处理重连循环。
   *
   * @param streamPath  服务端返回的相对流地址
   * @param assistantId 当前 assistant 气泡的本地 id（重连复用，不新建气泡）
   * @returns 流结束/失败时 resolve
   */
  const openStream = useCallback(
    async (streamPath: string, assistantId: string): Promise<void> => {
      for (;;) {
        const controller = new AbortController();
        abortRef.current = controller;
        try {
          await streamAnswer(streamPath, seqRef.current, controller.signal, (event) => {
            handleEvent(event, assistantId);
          });
        } catch {
          // 落到下面按「网络失败」处理；用户停止在下方统一判断。
        }
        if (userStoppedRef.current) {
          patchMessage(assistantId, { pending: false, stopped: true });
          finish();
          return;
        }
        if (doneRef.current) {
          // done / error 事件已经把状态处理完（finish 也已调用）。
          return;
        }
        // 流结束但没有 done：按网络失败处理（可能是 HTTP 错误 JSON 被当作流读完）。
        if (attemptRef.current >= MAX_RECONNECT_ATTEMPTS) {
          patchMessage(assistantId, {
            pending: false,
            failed: true,
            failureKey: 'error.common.network',
          });
          finish();
          return;
        }
        const delay = RECONNECT_BASE_DELAY_MS * 2 ** attemptRef.current;
        attemptRef.current += 1;
        setReconnecting(true);
        await new Promise<void>((resolve) => {
          reconnectTimerRef.current = setTimeout(resolve, delay);
        });
        if (!mountedRef.current || userStoppedRef.current) {
          return;
        }
      }
    },
    [finish, handleEvent, patchMessage],
  );

  /** 发送一个问题（C2 → C3）。 */
  const send = useCallback(
    (question: string): void => {
      const trimmed = question.trim();
      if (trimmed === '' || generatingRef.current) {
        return;
      }
      const stamp = Date.now();
      const userId = `user-${String(stamp)}`;
      const assistantId = `assistant-${String(stamp)}`;
      lastQuestionRef.current = trimmed;
      lastPairRef.current = { userId, assistantId };
      seqRef.current = 0;
      doneRef.current = false;
      attemptRef.current = 0;
      userStoppedRef.current = false;
      generatingRef.current = true;
      setGenerating(true);
      setReconnecting(false);
      setMessages((prev) => [
        { id: assistantId, role: 'assistant', content: '', pending: true },
        { id: userId, role: 'user', content: trimmed },
        ...prev,
      ]);

      void (async () => {
        const result = await submitQuestion(trimmed, conversationIdRef.current);
        if (!mountedRef.current) {
          return;
        }
        if (!result.ok) {
          patchMessage(assistantId, {
            pending: false,
            failed: true,
            failureKey: result.error.msgKey as MessageKey,
          });
          finish();
          return;
        }
        if (result.data.degraded) {
          // AI 开关在提交时被关掉：撤回这两条本地消息，切降级视图（不显示假回答）。
          setMessages((prev) => prev.filter((item) => item.id !== userId && item.id !== assistantId));
          setDegradedMode(true);
          finish();
          return;
        }
        if (result.data.conversationId !== null) {
          conversationIdRef.current = result.data.conversationId;
        }
        if (result.data.streamUrl === null) {
          patchMessage(assistantId, {
            pending: false,
            failed: true,
            failureKey: 'ai.chat.errorInterrupted',
          });
          finish();
          return;
        }
        await openStream(result.data.streamUrl, assistantId);
      })();
    },
    [finish, openStream, patchMessage],
  );

  /** 停止生成（用户主动；保留半截）。 */
  const stop = useCallback((): void => {
    userStoppedRef.current = true;
    if (reconnectTimerRef.current !== null) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    abortRef.current?.abort();
    finish();
  }, [finish]);

  /** 重试上一条失败的问题（移除旧气泡后重新提交）。 */
  const retry = useCallback((): void => {
    if (generatingRef.current) {
      return;
    }
    const pair = lastPairRef.current;
    const question = lastQuestionRef.current;
    if (pair === null || question === null) {
      return;
    }
    setMessages((prev) => prev.filter((item) => item.id !== pair.assistantId && item.id !== pair.userId));
    send(question);
  }, [send]);

  /** 清空视图（F2 的清空按钮；不调后端删除，那是 G2）。 */
  const clear = useCallback((): void => {
    setMessages([]);
    lastPairRef.current = null;
    lastQuestionRef.current = null;
  }, []);

  return {
    messages,
    generating,
    reconnecting,
    modeLoading,
    degradedMode,
    send,
    stop,
    retry,
    refreshMode: loadMode,
    clear,
  };
}
