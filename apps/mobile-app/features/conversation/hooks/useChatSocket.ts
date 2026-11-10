/**
 * 聊天 WebSocket hook（段 2 G6.2~G6.5）。
 *
 * 职责：建立/维持 `/ws/chat` 长连接、心跳、指数退避重连、断线增量补拉、消息去重与上抛。
 * 调用方：`features/conversation/pages/ChatPage.tsx`。
 * 数据来源：`shared/api/client` 的 baseUrl + `tokenStore.readSession()` 的 access token。
 * 注意：
 *   1. **重连必须退避**（1s→2s→4s→8s→16s→30s 封顶）：掉线时死循环猛连会把服务端打挂，
 *      也会让手机发热耗电（工作流 G6.5 第 5 条）。
 *   2. **重连成功后发 `chat.history`（afterSeq=已收最大 seq）补拉增量**，服务端按 seq 升序返回
 *      （G6.5 第 2 条）。**不重拉全量**——全量会把已加载的 20 条历史再拉一遍。
 *   3. **心跳 30 秒**（服务端 90 秒无消息关闭连接）：不做心跳的连接会在 90 秒后被服务端主动关闭，
 *      表现为「页面开着却收不到消息」。
 *   4. 消息去重两套键：自己发的按 `clientMsgId`（ack 回来后替换本地气泡），
 *      对方发的按 `messageId`（补拉与实时推送可能同时到达，G6.5「卡住怎么办」）。
 *      本 hook 只做「去重表」维护，真正的合并由页面按 id 去重渲染。
 *   5. 组件卸载/切会话时**主动关闭**连接并取消重连定时器：否则旧页面的连接会一直挂着,
 *      同一账号多连接还会触发服务端「踢旧设备」。
 *   6. **帧处理层必须校验 `conversationId`**：服务端的离线补推按 `user_id` 跨会话投递
 *      （离线队列一次 drain 出该用户多个会话的消息），一条连接收到的帧里可能带别的会话号。
 *      不校验就会把 conv1 的消息渲染进 conv2（实测发生过 conv1 的图片 id=12 进 conv2）。
 *      见 `isForActiveConversation`。
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type { ChatMessage } from '@/features/conversation/api/conversations';
import { getBaseUrl } from '@/shared/api/client';
import { readSession } from '@/shared/api/tokenStore';

/** 连接状态。 */
export type ChatSocketStatus = 'connecting' | 'online' | 'offline';

/** 发送回执（`chat.ack`）。 */
export type ChatAck = {
  /** 客户端幂等键。 */
  clientMsgId: string;
  /** 服务端消息主键。 */
  messageId: string;
  /** 会话内序号。 */
  seq: string;
  /** 落库时间。 */
  createdAt: string;
};

/** 已读回执（`chat.read`）。 */
export type ChatReadEvent = {
  /** 会话主键。 */
  conversationId: string;
  /** 读取者角色。 */
  readerRole: string;
  /** 已读到哪。 */
  lastReadSeq: number;
};

/** 撤回通知（`chat.recall`）。 */
export type ChatRecallEvent = {
  /** 消息主键。 */
  messageId: string;
  /** 会话主键。 */
  conversationId: string;
};

/** hook 回调集合（全部通过 ref 调用，避免每次渲染更换回调导致重连）。 */
export type ChatSocketHandlers = {
  /** 收到对方新消息。 */
  onMessage: (message: ChatMessage) => void;
  /** 自己发的消息被服务端确认。 */
  onAck: (ack: ChatAck) => void;
  /** 收到已读回执。 */
  onRead: (event: ChatReadEvent) => void;
  /** 收到撤回通知。 */
  onRecall: (event: ChatRecallEvent) => void;
  /** 对方输入状态。 */
  onTyping: (isTyping: boolean) => void;
  /** 增量补拉结果。 */
  onHistory: (messages: ChatMessage[], hasMore: boolean) => void;
  /** 收到 chat.error（业务拒绝）。 */
  onError: (msgKey: string, clientMsgId: string | null) => void;
  /** 被新设备踢下线。 */
  onKicked: () => void;
};

/** 重连退避序列（毫秒）。 */
const BACKOFF_MS: ReadonlyArray<number> = [1000, 2000, 4000, 8000, 16000, 30000];

/** 心跳间隔（毫秒）。 */
const PING_INTERVAL_MS = 30_000;

/** 收集到的最大 seq（跨连接保持）。 */
type SeqRef = { current: number };

/**
 * 维护一条聊天长连接。
 *
 * @param conversationId 会话主键（空串表示还没准备好，不连）
 * @param handlers       事件回调集合
 * @returns 连接状态与发送方法
 */
export function useChatSocket(conversationId: string, handlers: ChatSocketHandlers) {
  const [status, setStatus] = useState<ChatSocketStatus>('connecting');
  const socketRef = useRef<WebSocket | null>(null);
  const handlersRef = useRef(handlers);
  const lastSeqRef: SeqRef = useRef(0);
  const retryRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pingTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const manualCloseRef = useRef(false);

  handlersRef.current = handlers;

  /**
   * 把消息/回执里的 seq 记进「已收到的最大 seq」。
   *
   * @param rawSeq seq 字符串
   */
  const trackSeq = useCallback((rawSeq: string): void => {
    const value = Number(rawSeq);
    if (Number.isFinite(value) && value > lastSeqRef.current) {
      lastSeqRef.current = value;
    }
  }, []);

  /**
   * 发送一个信封。
   *
   * @param type 类型
   * @param data 数据
   * @returns 是否发送成功（连接不在线返回 false）
   */
  const send = useCallback((type: string, data: Record<string, unknown>): boolean => {
    const socket = socketRef.current;
    if (socket === null || socket.readyState !== WebSocket.OPEN) {
      return false;
    }
    socket.send(
      JSON.stringify({
        type,
        id: `${String(Date.now())}-${String(Math.trunc(Math.random() * 1000))}`,
        timestamp: new Date().toISOString(),
        data,
      }),
    );
    return true;
  }, []);

  /**
   * 建立连接（含重连逻辑）。
   */
  const connect = useCallback((): void => {
    if (conversationId === '') {
      return;
    }
    manualCloseRef.current = false;
    setStatus('connecting');
    void readSession().then((session) => {
      if (session === null) {
        setStatus('offline');
        return;
      }
      const base = getBaseUrl().replace(/^http/, 'ws');
      const url = `${base}/ws/chat?token=${encodeURIComponent(session.accessToken)}`;
      const socket = new WebSocket(url);
      socketRef.current = socket;

      socket.onopen = () => {
        retryRef.current = 0;
        setStatus('online');
        // 重连补拉：只拉「已收到之后」的增量（G6.5 第 2 条）
        send('chat.history', { conversationId, afterSeq: lastSeqRef.current, limit: 50 });
        // 心跳：服务端 90 秒无消息会主动关闭
        if (pingTimerRef.current !== null) {
          clearInterval(pingTimerRef.current);
        }
        pingTimerRef.current = setInterval(() => {
          send('ping', {});
        }, PING_INTERVAL_MS);
      };

      socket.onmessage = (event: MessageEvent<string>) => {
        handleEnvelope(event.data);
      };

      socket.onerror = () => {
        // 具体错误由 onclose 统一处理
      };

      socket.onclose = () => {
        socketRef.current = null;
        if (pingTimerRef.current !== null) {
          clearInterval(pingTimerRef.current);
          pingTimerRef.current = null;
        }
        if (manualCloseRef.current) {
          return;
        }
        setStatus('offline');
        const delay = BACKOFF_MS[Math.min(retryRef.current, BACKOFF_MS.length - 1)];
        retryRef.current += 1;
        reconnectTimerRef.current = setTimeout(() => {
          connect();
        }, delay);
      };
    });

    /**
     * 处理一条服务端信封。
     *
     * @param raw 原始 JSON 文本
     */
    function handleEnvelope(raw: string): void {
      let envelope: { type?: string; data?: Record<string, unknown> };
      try {
        envelope = JSON.parse(raw) as { type?: string; data?: Record<string, unknown> };
      } catch {
        return;
      }
      const data = envelope.data ?? {};

      /** 当前会话主键（`connect` 的闭包值；空串表示会话还没准备好）。 */
      const activeConversationId = conversationId;

      /**
       * 判断一帧的会话主键是否属于当前会话。
       *
       * 服务端离线补推按 `user_id` 跨会话投递，帧里可能带的是别的会话——
       * 不校验就会把 conv1 的消息渲染进 conv2。用 `String()` 归一比较：
       * 服务端 Long 按接口规范序列化为字符串，但历史实现/补丁里有 number 形态，
       * 直接用 `!==` 会让**全部帧被丢弃**（聊天页整页不刷新）。
       *
       * @param raw 帧里的 conversationId（类型未知，可能缺失）
       * @returns true 放行；false 丢弃
       */
      const isForActiveConversation = (raw: unknown): boolean => {
        // 会话还没准备好时保持原有行为（不过滤）：只为兜底 hook 被别处调用时不误丢帧，
        // 正常路径下 conversationId 为空时连接根本不会建立。
        if (activeConversationId === '') {
          return true;
        }
        // 帧里没带 conversationId（结构异常）时保持原有行为，不因缺字段丢消息；
        // 真正要拦的是「带了一个别的会话号」。
        if (typeof raw !== 'string' && typeof raw !== 'number') {
          return true;
        }
        return String(raw) === activeConversationId;
      };

      switch (envelope.type) {
        case 'chat.message': {
          // 跨会话过滤必须放在 trackSeq 之前：别的会话的 seq 若混进来，
          // 会把本会话的「已收最大 seq」顶高，重连补拉时漏消息。
          if (!isForActiveConversation(data.conversationId)) {
            return;
          }
          const message = data as unknown as ChatMessage;
          trackSeq(message.seq);
          handlersRef.current.onMessage(message);
          return;
        }
        case 'chat.ack': {
          const ack = data as unknown as ChatAck;
          trackSeq(ack.seq);
          handlersRef.current.onAck(ack);
          return;
        }
        case 'chat.read': {
          if (!isForActiveConversation(data.conversationId)) {
            return;
          }
          handlersRef.current.onRead(data as unknown as ChatReadEvent);
          return;
        }
        case 'chat.recall': {
          if (!isForActiveConversation(data.conversationId)) {
            return;
          }
          handlersRef.current.onRecall(data as unknown as ChatRecallEvent);
          return;
        }
        case 'chat.typing': {
          if (!isForActiveConversation(data.conversationId)) {
            return;
          }
          handlersRef.current.onTyping(data.isTyping === true);
          return;
        }
        case 'chat.history': {
          // 帧级先拦一次（服务端 payload 带 conversationId），
          // 列表内再逐条拦一次：补推混进来的别的会话消息一并丢掉。
          if (!isForActiveConversation(data.conversationId)) {
            return;
          }
          const list = ((data.messages ?? []) as ChatMessage[]).filter((item) =>
            isForActiveConversation(item.conversationId),
          );
          for (const item of list) {
            trackSeq(item.seq);
          }
          handlersRef.current.onHistory(list, data.hasMore === true);
          return;
        }
        case 'chat.error': {
          handlersRef.current.onError(
            typeof data.msgKey === 'string' ? data.msgKey : 'error.common.server',
            typeof data.clientMsgId === 'string' ? data.clientMsgId : null,
          );
          return;
        }
        case 'chat.kicked': {
          manualCloseRef.current = true;
          handlersRef.current.onKicked();
          return;
        }
        default:
          return;
      }
    }
  }, [conversationId, send, trackSeq]);

  useEffect(() => {
    connect();
    return () => {
      manualCloseRef.current = true;
      if (reconnectTimerRef.current !== null) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      if (pingTimerRef.current !== null) {
        clearInterval(pingTimerRef.current);
        pingTimerRef.current = null;
      }
      if (socketRef.current !== null) {
        socketRef.current.close();
        socketRef.current = null;
      }
    };
  }, [connect]);

  /**
   * 发文本消息（返回 clientMsgId 供页面先插入「发送中」气泡）。
   *
   * @param content 文本
   * @returns clientMsgId；连接不在线或内容为空返回 null
   */
  const sendText = useCallback(
    (content: string): string | null => {
      const trimmed = content.trim();
      if (trimmed === '') {
        return null;
      }
      const clientMsgId = `c-${String(Date.now())}-${String(Math.trunc(Math.random() * 1_000_000))}`;
      const sent = send('chat.send', {
        conversationId,
        msgType: 'text',
        content: trimmed,
        clientMsgId,
      });
      return sent ? clientMsgId : null;
    },
    [conversationId, send],
  );

  /**
   * 发图片 / 文件消息（G6.3 第 6 条：先上传拿对象 key，再把 key 作为 fileUrl 发出去）。
   *
   * <p>服务端存 key、出参换签名地址，所以这里传的是 **key 而不是 URL**。</p>
   *
   * @param kind     消息类型：image / file
   * @param fileKey  上传后拿到的对象 key
   * @param fileName 文件名（file 消息展示用）
   * @param fileSize 文件大小（字节）
   * @returns clientMsgId；连接不在线返回 null
   */
  const sendAttachment = useCallback(
    (kind: 'image' | 'file', fileKey: string, fileName: string, fileSize: number): string | null => {
      const clientMsgId = `c-${String(Date.now())}-${String(Math.trunc(Math.random() * 1_000_000))}`;
      const sent = send('chat.send', {
        conversationId,
        msgType: kind,
        content: '',
        fileUrl: fileKey,
        fileName,
        fileSize,
        clientMsgId,
      });
      return sent ? clientMsgId : null;
    },
    [conversationId, send],
  );

  /**
   * 上报已读。
   *
   * @param lastReadSeq 已读到的最大 seq
   */
  const sendRead = useCallback(
    (lastReadSeq: string): void => {
      send('chat.read', { conversationId, lastReadSeq });
    },
    [conversationId, send],
  );

  /**
   * 上报输入状态。
   *
   * @param isTyping 是否在输入
   */
  const sendTyping = useCallback(
    (isTyping: boolean): void => {
      send('chat.typing', { conversationId, isTyping });
    },
    [conversationId, send],
  );

  /**
   * 撤回消息。
   *
   * @param messageId 消息主键
   */
  const recallMessage = useCallback(
    (messageId: string): void => {
      send('chat.recall', { messageId });
    },
    [send],
  );

  return { status, sendText, sendAttachment, sendRead, sendTyping, recallMessage };
}
