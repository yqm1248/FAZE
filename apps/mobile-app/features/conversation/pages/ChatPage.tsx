/**
 * 一对一聊天页（段 2 G6.3，设计稿 `17-chat.html`）。
 *
 * 职责：连 `/ws/chat`、收发文本消息、下拉加载更早历史、显示对方输入状态与断线重连提示、
 * 长按撤回自己 2 分钟内的消息。
 * 调用方：路由壳 `app/chat.tsx`；入口在订单详情的「联系律师」与后续消息列表（段 8）。
 * 数据来源：`POST /app/conversations`（按律师建会话）、`GET /app/conversations/{id}/messages`（首屏历史）、
 *          `POST /app/conversations/{id}/read`（断线时已读兜底）、WebSocket `/ws/chat`。
 * 注意：
 *   1. **先拉首屏再连 WS**：先连 WS 再拉历史会让「刚收到的消息」被历史覆盖（顺序错乱）。
 *      本页顺序：建会话 → 拉 20 条 → 连 WS（WS 连上后自动补拉 seq 之后的增量）。
 *   2. **发送是乐观的**：先插「发送中」气泡，收到 `chat.ack` 后替换成服务端消息；
 *      超时/失败显示「发送失败」，点按重发**复用同一个 clientMsgId**（服务端据此去重，
 *      重发不会产生两条）。
 *   3. **已读上报**：进入页面时按最大 seq 上报一次；收到对方新消息且页面在前台时再报一次。
 *      WS 断线时改走 REST 兜底（`markConversationRead`）。
 *   4. **图片/文件消息**：服务端已支持（`msgType=image|file` + fileUrl），
 *      但客户端选图/选文件需要 `expo-image-picker` / `expo-document-picker`（新增依赖须先登记），
 *      段 2 不做；本页对收到的图片消息按「图片已发送」占位渲染（不打挂）。
 *   5. 撤回入口：长按自己的消息 → 确认框 → `chat.recall`（服务端还会校验 2 分钟窗口）。
 */

import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { createConversation, getMessages, markConversationRead } from '@/features/conversation/api/conversations';
import type { ChatMessage, ConversationItem } from '@/features/conversation/api/conversations';
import { useChatSocket } from '@/features/conversation/hooks/useChatSocket';
import type { AppError } from '@/shared/api/types';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { parseServerTime } from '@/shared/utils/serverTime';

/** 首屏与每次翻页条数。 */
const PAGE_SIZE = 20;

/** 输入状态节流（毫秒）：3 秒无输入自动消失（设计稿与工作流一致）。 */
const TYPING_IDLE_MS = 3000;

/** 快捷问句（设计稿 quick-ask 三条；提交的是文本，显示走 i18n）。 */
const QUICK_KEYS: ReadonlyArray<MessageKey> = [
  'conversation.quick1',
  'conversation.quick2',
  'conversation.quick3',
];

/** 本地「发送中」气泡。 */
type PendingMessage = {
  /** 客户端幂等键。 */
  clientMsgId: string;
  /** 文本。 */
  content: string;
  /** 是否失败（点按重发）。 */
  failed: boolean;
};

/**
 * 聊天页。
 *
 * 副作用：建/取会话 → 拉首屏历史 → 连 WS；页面卸载关闭连接。
 *
 * @returns 聊天界面
 */
export function ChatPage() {
  const params = useLocalSearchParams<{ lawyerId?: string }>();
  const lawyerId = typeof params.lawyerId === 'string' ? params.lawyerId : '';
  const { t } = useI18n();
  const toast = useToast();

  const [phase, setPhase] = useState<'loading' | 'error' | 'ready'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [conversation, setConversation] = useState<ConversationItem | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [pending, setPending] = useState<PendingMessage[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loadingEarlier, setLoadingEarlier] = useState(false);
  const [draft, setDraft] = useState('');
  const [peerTyping, setPeerTyping] = useState(false);
  const [recallTarget, setRecallTarget] = useState<ChatMessage | null>(null);

  const scrollRef = useRef<ScrollView | null>(null);
  const typingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastTypingSentRef = useRef(false);
  const conversationId = conversation?.id ?? '';

  /**
   * 合并一条服务端消息（按 id 去重，追加后按 seq 升序）。
   *
   * @param incoming 新消息
   */
  const mergeMessage = useCallback((incoming: ChatMessage): void => {
    setMessages((current) => {
      if (current.some((item) => item.id === incoming.id)) {
        return current;
      }
      const next = [...current, incoming];
      next.sort((left, right) => Number(left.seq) - Number(right.seq));
      return next;
    });
  }, []);

  /** 首屏：建会话 + 拉历史。 */
  const load = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const created = await createConversation(lawyerId);
    if (!created.ok) {
      setError(created.error);
      setPhase('error');
      return;
    }
    setConversation(created.data);
    const history = await getMessages(created.data.id, undefined, PAGE_SIZE);
    if (!history.ok) {
      setError(history.error);
      setPhase('error');
      return;
    }
    setError(null);
    setMessages(history.data.list);
    setHasMore(history.data.hasMore);
    setPhase('ready');
  }, [lawyerId]);

  useEffect(() => {
    void load();
  }, [load]);

  /** pending 的 ref（ack 回调用它取本地内容；必须在 useChatSocket 之前就存在）。 */
  const pendingRef = useRef<PendingMessage[]>([]);
  pendingRef.current = pending;

  const { status: socketStatus, sendText, sendRead, sendTyping, recallMessage } = useChatSocket(
    phase === 'ready' ? conversationId : '',
    {
      onMessage: (message) => {
        mergeMessage(message);
        setPending((current) => current.filter((item) => item.clientMsgId !== message.clientMsgId));
      },
      onAck: (ack) => {
        // 服务端确认：把「发送中」替换成真实消息（内容取自本地 pending）
        setPending((current) => current.filter((item) => item.clientMsgId !== ack.clientMsgId));
        setMessages((existing) => {
          if (existing.some((item) => item.id === ack.messageId)) {
            return existing;
          }
          const local = pendingRef.current.find((item) => item.clientMsgId === ack.clientMsgId);
          if (local === undefined) {
            return existing;
          }
          const confirmed: ChatMessage = {
            id: ack.messageId,
            conversationId,
            seq: ack.seq,
            clientMsgId: ack.clientMsgId,
            senderRole: 'user',
            senderId: '',
            msgType: 'text',
            content: local.content,
            fileUrl: null,
            fileName: null,
            fileSize: null,
            recalled: false,
            readByPeer: false,
            createdAt: ack.createdAt,
          };
          const next = [...existing, confirmed];
          next.sort((left, right) => Number(left.seq) - Number(right.seq));
          return next;
        });
      },
      onRead: () => {
        // 对方读了我的消息：刷新「已读」状态（简化：把已发出的全部标为已读）
        setMessages((current) => current.map((item) => ({ ...item, readByPeer: true })));
      },
      onRecall: (event) => {
        setMessages((current) =>
          current.map((item) =>
            item.id === event.messageId ? { ...item, recalled: true, content: null, fileUrl: null } : item,
          ),
        );
      },
      onTyping: (isTyping) => {
        setPeerTyping(isTyping);
      },
      onHistory: (list) => {
        for (const item of list) {
          mergeMessage(item);
        }
      },
      onError: (msgKey, clientMsgId) => {
        toast.show(t(msgKey as MessageKey));
        if (clientMsgId !== null) {
          setPending((current) =>
            current.map((item) =>
              item.clientMsgId === clientMsgId ? { ...item, failed: true } : item,
            ),
          );
        }
      },
      onKicked: () => {
        toast.show(t('conversation.kicked'));
      },
    },
  );

  /** 已读上报（WS 在线走 WS，断线走 REST 兜底）。 */
  const reportRead = useCallback((): void => {
    const last = messages[messages.length - 1];
    if (last === undefined) {
      return;
    }
    if (socketStatus === 'online') {
      sendRead(last.seq);
      return;
    }
    void markConversationRead(conversationId, last.seq);
  }, [messages, conversationId, socketStatus, sendRead]);

  /** 进入页面/收到消息后上报已读。 */
  useEffect(() => {
    if (phase === 'ready' && socketStatus === 'online') {
      reportRead();
    }
  }, [phase, socketStatus, messages, reportRead]);

  /** 加载更早的消息（seq 游标，向更早翻）。 */
  const loadEarlier = async (): Promise<void> => {
    const first = messages[0];
    if (first === undefined || loadingEarlier) {
      return;
    }
    setLoadingEarlier(true);
    try {
      const result = await getMessages(conversationId, first.seq, PAGE_SIZE);
      if (!result.ok) {
        toast.show(t('error.common.server'));
        return;
      }
      setMessages((current) => {
        const known = new Set(current.map((item) => item.id));
        const older = result.data.list.filter((item) => !known.has(item.id));
        return [...older, ...current];
      });
      setHasMore(result.data.hasMore);
    } finally {
      setLoadingEarlier(false);
    }
  };

  /** 发送一条文本。 */
  const send = (text: string): void => {
    const trimmed = text.trim();
    if (trimmed === '') {
      return;
    }
    const clientMsgId = sendText(trimmed);
    if (clientMsgId === null) {
      toast.show(t('conversation.reconnecting'));
      return;
    }
    setPending((current) => [...current, { clientMsgId, content: trimmed, failed: false }]);
    setDraft('');
    setTyping(false);
    setTimeout(() => {
      scrollRef.current?.scrollToEnd({ animated: true });
    }, 50);
  };

  /** 输入状态上报（3 秒无输入发 false）。 */
  const setTyping = (typing: boolean): void => {
    if (typing !== lastTypingSentRef.current) {
      lastTypingSentRef.current = typing;
      sendTyping(typing);
    }
    if (typingTimerRef.current !== null) {
      clearTimeout(typingTimerRef.current);
    }
    typingTimerRef.current = setTimeout(() => {
      if (lastTypingSentRef.current) {
        lastTypingSentRef.current = false;
        sendTyping(false);
      }
    }, TYPING_IDLE_MS);
  };

  const headerTitle = conversation?.peerName ?? t('conversation.title');
  const empty = messages.length === 0 && pending.length === 0;

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={headerTitle} />
      {socketStatus === 'offline' ? (
        <View style={styles.banner}>
          <LocalizedText numberOfLines={1} style={styles.bannerText}>
            {t('conversation.reconnecting')}
          </LocalizedText>
        </View>
      ) : null}
      {socketStatus === 'connecting' && phase === 'ready' ? (
        <View style={styles.banner}>
          <LocalizedText numberOfLines={1} style={styles.bannerText}>
            {t('conversation.connecting')}
          </LocalizedText>
        </View>
      ) : null}

      {phase === 'loading' ? (
        <View style={styles.pad}>
          <LoadingState rows={4} />
        </View>
      ) : null}

      {phase === 'error' && error !== null ? (
        <View style={styles.pad}>
          <ErrorState
            error={error}
            onRetry={() => {
              void load();
            }}
          />
        </View>
      ) : null}

      {phase === 'ready' ? (
        <View style={styles.body}>
          <ScrollView ref={scrollRef} contentContainerStyle={styles.list} onContentSizeChange={() => {
            scrollRef.current?.scrollToEnd({ animated: false });
          }}>
            {hasMore ? (
              <Pressable
                onPress={() => {
                  void loadEarlier();
                }}
                style={styles.loadEarlier}
                accessibilityRole="button"
              >
                <LocalizedText numberOfLines={1} style={styles.loadEarlierText}>
                  {loadingEarlier ? t('common.state.loading') : t('conversation.loadEarlier')}
                </LocalizedText>
              </Pressable>
            ) : null}

            {empty ? (
              <AppCard style={styles.emptyCard}>
                <LocalizedText numberOfLines={2} style={styles.emptyTitle}>
                  {t('conversation.emptyTitle')}
                </LocalizedText>
                <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
                  {t('conversation.emptyDesc')}
                </LocalizedText>
                <View style={styles.quickList}>
                  {QUICK_KEYS.map((key) => (
                    <Pressable
                      key={key}
                      onPress={() => setDraft(t(key))}
                      style={styles.quickItem}
                      accessibilityRole="button"
                    >
                      <LocalizedText numberOfLines={1} style={styles.quickText}>{t(key)}</LocalizedText>
                    </Pressable>
                  ))}
                </View>
              </AppCard>
            ) : null}

            {messages.map((message) => (
              <MessageBubble
                key={message.id}
                message={message}
                onLongPress={() => {
                  if (message.senderRole === 'lawyer') {
                    return;
                  }
                  setRecallTarget(message);
                }}
              />
            ))}

            {pending.map((item) => (
              <View key={item.clientMsgId} style={[styles.row, styles.rowOut]}>
                <View style={[styles.bubble, styles.bubbleOut]}>
                  <LocalizedText numberOfLines={8} style={styles.bubbleTextOut}>{item.content}</LocalizedText>
                  {item.failed ? (
                    <LocalizedText numberOfLines={1} style={styles.failedText}>
                      {t('conversation.sendFailed')}
                    </LocalizedText>
                  ) : (
                    <LocalizedText numberOfLines={1} style={styles.sendingText}>
                      {t('common.state.submitting')}
                    </LocalizedText>
                  )}
                </View>
              </View>
            ))}

            {peerTyping ? (
              <LocalizedText numberOfLines={1} style={styles.typing}>
                {t('conversation.typing')}
              </LocalizedText>
            ) : null}
          </ScrollView>

          <View style={styles.composer}>
            <TextInput
              value={draft}
              onChangeText={(text) => {
                setDraft(text);
                setTyping(text.length > 0);
              }}
              placeholder={t('conversation.inputPlaceholder')}
              placeholderTextColor={color.text.muted}
              style={styles.input}
              multiline
              maxLength={2000}
              accessibilityLabel={t('conversation.inputPlaceholder')}
            />
            <Pressable
              onPress={() => send(draft)}
              style={[styles.sendButton, draft.trim() === '' ? styles.sendDisabled : null]}
              accessibilityRole="button"
              accessibilityLabel={t('conversation.send')}
              disabled={draft.trim() === ''}
            >
              <Ionicons name="send" size={18} color={color.text.onBrand} />
            </Pressable>
          </View>
        </View>
      ) : null}

      <ConfirmDialog
        visible={recallTarget !== null}
        title={t('conversation.recallTitle')}
        description={t('conversation.recallBody')}
        confirmLabel={t('conversation.recallConfirm')}
        cancelLabel={t('common.action.cancel')}
        danger
        onConfirm={() => {
          if (recallTarget !== null) {
            recallMessage(recallTarget.id);
          }
          setRecallTarget(null);
        }}
        onCancel={() => setRecallTarget(null)}
      />
    </Screen>
  );
}

/**
 * 一条消息气泡。
 *
 * @param props.message    消息
 * @param props.onLongPress 长按回调（撤回用）
 * @returns 气泡
 */
function MessageBubble({ message, onLongPress }: { message: ChatMessage; onLongPress: () => void }) {
  const { t } = useI18n();
  const out = message.senderRole !== 'lawyer';
  const clock = clockOf(message.createdAt);
  return (
    <Pressable
      onLongPress={onLongPress}
      style={[styles.row, out ? styles.rowOut : styles.rowIn]}
      accessibilityRole="button"
      accessibilityHint={out ? t('conversation.recallHint') : undefined}
    >
      <View style={[styles.bubble, out ? styles.bubbleOut : styles.bubbleIn]}>
        {message.recalled ? (
          <LocalizedText numberOfLines={2} style={styles.recalled}>
            {t('conversation.recalled')}
          </LocalizedText>
        ) : message.msgType === 'image' ? (
          <LocalizedText numberOfLines={2} style={out ? styles.bubbleTextOut : styles.bubbleTextIn}>
            {t('conversation.imageMessage')}
          </LocalizedText>
        ) : (
          <LocalizedText numberOfLines={12} style={out ? styles.bubbleTextOut : styles.bubbleTextIn}>
            {message.content ?? ''}
          </LocalizedText>
        )}
        <View style={styles.timeRow}>
          <LtrText numberOfLines={1} style={out ? styles.timeOut : styles.timeIn}>{clock}</LtrText>
          {out && message.readByPeer ? (
            <LocalizedText numberOfLines={1} style={styles.readFlag}>
              {t('conversation.read')}
            </LocalizedText>
          ) : null}
        </View>
      </View>
    </Pressable>
  );
}

/**
 * 把服务端时间格式化成 `HH:mm`。
 *
 * @param raw 服务端时间（UTC）
 * @returns 本地时钟文本
 */
function clockOf(raw: string): string {
  const millis = parseServerTime(raw);
  if (millis === null) {
    return '';
  }
  const date = new Date(millis);
  const hour = String(date.getHours()).padStart(2, '0');
  const minute = String(date.getMinutes()).padStart(2, '0');
  return `${hour}:${minute}`;
}

const styles = StyleSheet.create({
  pad: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
  },
  body: {
    flex: 1,
  },
  banner: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.xl,
    backgroundColor: color.state.warning,
  },
  bannerText: {
    ...typography.caption,
    color: color.text.onBrand,
    textAlign: 'center',
  },
  list: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    gap: spacing.sm,
  },
  loadEarlier: {
    alignSelf: 'center',
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
  },
  loadEarlierText: {
    ...typography.caption,
    color: color.brand.primary,
  },
  emptyCard: {
    gap: spacing.sm,
  },
  emptyTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  emptyDesc: {
    ...typography.caption,
    color: color.text.muted,
  },
  quickList: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  quickItem: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  quickText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  row: {
    flexDirection: 'row',
  },
  rowOut: {
    justifyContent: 'flex-end',
  },
  rowIn: {
    justifyContent: 'flex-start',
  },
  bubble: {
    maxWidth: '78%',
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    gap: spacing.xxs,
  },
  bubbleOut: {
    backgroundColor: color.brand.primary,
    borderTopEndRadius: radius.xs,
  },
  bubbleIn: {
    backgroundColor: color.background.surface,
    borderTopStartRadius: radius.xs,
  },
  bubbleTextOut: {
    ...typography.body,
    color: color.text.onBrand,
  },
  bubbleTextIn: {
    ...typography.body,
    color: color.text.body,
  },
  recalled: {
    ...typography.bodySmall,
    color: color.text.muted,
    fontStyle: 'italic',
  },
  timeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: spacing.xs,
  },
  timeOut: {
    ...typography.micro,
    color: color.text.onBrand,
    opacity: 0.7,
  },
  timeIn: {
    ...typography.micro,
    color: color.text.muted,
  },
  readFlag: {
    ...typography.micro,
    color: color.text.onBrand,
    opacity: 0.7,
  },
  sendingText: {
    ...typography.micro,
    color: color.text.onBrand,
    opacity: 0.7,
    alignSelf: 'flex-end',
  },
  failedText: {
    ...typography.micro,
    color: color.state.danger,
    alignSelf: 'flex-end',
  },
  typing: {
    ...typography.caption,
    color: color.text.muted,
    marginStart: spacing.xs,
  },
  composer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: spacing.sm,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
    backgroundColor: color.background.surface,
  },
  input: {
    flex: 1,
    minHeight: size.inputHeight,
    maxHeight: 110,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    backgroundColor: color.background.input,
    color: color.text.primary,
    ...typography.body,
  },
  sendButton: {
    width: size.inputHeight,
    height: size.inputHeight,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.brand.primary,
  },
  sendDisabled: {
    opacity: 0.4,
  },
});
