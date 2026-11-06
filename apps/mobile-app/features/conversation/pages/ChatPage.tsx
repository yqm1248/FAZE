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
 *   4. **图片/文件消息**（段 2 G6.3 第 6 条）：服务端已支持（`msgType=image|file` + fileUrl）。
 *      客户端链路是「选图/选文件（`expo-image-picker` / `expo-document-picker`）→ 上传拿对象 key
 *      → 把 key 当 `fileUrl` 发出」。本页只负责选择、上传、发送与挂载；缩略图/文件卡片/全屏
 *      查看在 `features/conversation/components/` 下的三个组件里。上传期间输入区禁用，防重复点击。
 *   5. 撤回入口：长按自己的消息 → 确认框 → `chat.recall`（服务端还会校验 2 分钟窗口）。
 */

import { Ionicons } from '@expo/vector-icons';
import * as DocumentPicker from 'expo-document-picker';
import * as ImagePicker from 'expo-image-picker';
import { useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { uploadFile } from '@/features/account/api/upload';
import {
  createConversation,
  getConversation,
  getMessages,
  markConversationRead,
} from '@/features/conversation/api/conversations';
import type { ChatMessage, ConversationItem } from '@/features/conversation/api/conversations';
import { AttachSheet } from '@/features/conversation/components/AttachSheet';
import { MessageFile } from '@/features/conversation/components/MessageFile';
import { MessageImage } from '@/features/conversation/components/MessageImage';
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

/** 本地「发送中」气泡（文本 / 图片 / 文件三种）。 */
type PendingMessage = {
  /** 客户端幂等键。 */
  clientMsgId: string;
  /** 文本内容（图片/文件为空串）。 */
  content: string;
  /** 消息类型：text / image / file。 */
  kind: 'text' | 'image' | 'file';
  /** 本地预览地址（图片/文件发送中显示用；发送成功后由服务端消息替代）。 */
  localUri?: string;
  /** 文件名（文件消息）。 */
  fileName?: string;
  /** 文件大小（字节，文件消息；确认后用服务端消息替代，期间卡片要能显示大小）。 */
  fileSize?: number;
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
  const params = useLocalSearchParams<{ lawyerId?: string; conversationId?: string }>();
  const lawyerId = typeof params.lawyerId === 'string' ? params.lawyerId : '';
  const conversationIdParam = typeof params.conversationId === 'string' ? params.conversationId : '';
  const isDirect = conversationIdParam !== '';
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
  /** 附件上传中（输入区禁用 + 转圈提示，防重复点击）。 */
  const [uploading, setUploading] = useState(false);
  /** 附件选择面板可见性。 */
  const [attachVisible, setAttachVisible] = useState(false);

  const scrollRef = useRef<ScrollView | null>(null);
  /** 上传中的同步守卫：state 更新有一帧延迟，连点会进两次上传。 */
  const uploadingRef = useRef(false);
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

  /** 首屏：建会话（或按 conversationId 直达）+ 拉历史。 */
  const load = useCallback(async (): Promise<void> => {
    setPhase('loading');
    // 两种入口：订单详情「联系律师」只给 lawyerId → 创建或获取；
    // 推送点击直达（G7.3）只给 conversationId → 直接查会话
    const created = isDirect ? await getConversation(conversationIdParam) : await createConversation(lawyerId);
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
  }, [lawyerId, isDirect, conversationIdParam]);

  useEffect(() => {
    void load();
  }, [load]);

  /** pending 的 ref（ack 回调用它取本地内容；必须在 useChatSocket 之前就存在）。 */
  const pendingRef = useRef<PendingMessage[]>([]);
  pendingRef.current = pending;

  const { status: socketStatus, sendText, sendAttachment, sendRead, sendTyping, recallMessage } =
    useChatSocket(phase === 'ready' ? conversationId : '', {
      onMessage: (message) => {
        mergeMessage(message);
        setPending((current) => current.filter((item) => item.clientMsgId !== message.clientMsgId));
      },
      onAck: (ack) => {
        // 服务端确认：把「发送中」替换成真实消息（类型/内容取自本地 pending；
        // 附件先用本地 URI 显示/打开，下次拉历史会拿到服务端签名地址）
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
            msgType: local.kind,
            content: local.content === '' ? null : local.content,
            fileUrl: local.localUri ?? null,
            fileName: local.fileName ?? null,
            // 附件大小在 pending 里是 number 字节；确认消息要转回服务端的字符串口径
            fileSize: local.fileSize === undefined ? null : String(local.fileSize),
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
    setPending((current) => [
      ...current,
      { clientMsgId, content: trimmed, kind: 'text', failed: false },
    ]);
    setDraft('');
    setTyping(false);
    setTimeout(() => {
      scrollRef.current?.scrollToEnd({ animated: true });
    }, 50);
  };

  /**
   * 选图/选文件 → 上传 → 发消息（G6.3 第 6 条）。
   *
   * <p>先申请上传凭证拿到对象 key，再把 key 作为 `fileUrl` 发出去；服务端存 key、
   * 出参换签名地址。上传失败不产生消息（不插「发送中」气泡，只提示）。</p>
   */

  /** 选图并发送（用户取消静默返回；权限被拒要提示）。 */
  const pickImage = async (): Promise<void> => {
    try {
      const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
      if (!permission.granted) {
        toast.show(t('conversation.composer.permissionDenied'));
        return;
      }
      const picked = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ['images'],
        quality: 0.8,
      });
      // 取消选择不是错误：不插消息、不弹提示（完成标准第 6 条）
      if (picked.canceled || picked.assets.length === 0) {
        return;
      }
      // 数组下标在 noUncheckedIndexedAccess 下可能为 undefined，必须显式判空
      const asset = picked.assets[0];
      if (asset === undefined) {
        return;
      }
      const fileName = asset.fileName ?? `image-${String(Date.now())}.jpg`;
      await uploadAndSend('image', asset.uri, fileName, asset.mimeType ?? 'image/jpeg', asset.fileSize ?? 0);
    } catch {
      // 系统选择器偶发失败（权限弹窗被系统回收、Web FileReader 失败等）：给提示，不插消息
      toast.show(t('conversation.message.pickFailed'));
    }
  };

  /** 选文件并发送（后端只允许 PDF；取消同样的静默处理）。 */
  const pickFile = async (): Promise<void> => {
    try {
      const picked = await DocumentPicker.getDocumentAsync({
        type: 'application/pdf',
        copyToCacheDirectory: true,
      });
      if (picked.canceled || picked.assets.length === 0) {
        return;
      }
      const asset = picked.assets[0];
      if (asset === undefined) {
        return;
      }
      await uploadAndSend(
        'file',
        asset.uri,
        asset.name,
        asset.mimeType ?? 'application/pdf',
        asset.size ?? 0,
      );
    } catch {
      toast.show(t('conversation.message.pickFailed'));
    }
  };

  /**
   * 上传并发送一个附件。
   *
   * @param kind        消息类型
   * @param uri         本地 URI
   * @param fileName    文件名
   * @param contentType MIME 类型（后端白名单：图片 jpeg/png/webp、文件 pdf）
   * @param size        字节数
   */
  const uploadAndSend = async (
    kind: 'image' | 'file',
    uri: string,
    fileName: string,
    contentType: string,
    size: number,
  ): Promise<void> => {
    // 防重复：上传中再点一次直接返回（ref 是同步的，state 有一帧延迟）
    if (uploadingRef.current) {
      return;
    }
    uploadingRef.current = true;
    setUploading(true);
    try {
      const blob = await (await fetch(uri)).blob();
      // 选择器给的大小可能与真实字节不同（web 的 blob 一定准），以实际字节为准
      const bytes = size > 0 ? size : blob.size;
      const uploaded = await uploadFile('chat', {
        fileName,
        size: bytes,
        contentType,
        body: blob,
      });
      if (!uploaded.ok) {
        toast.show(t('conversation.message.uploadFailed'));
        return;
      }
      const clientMsgId = sendAttachment(kind, uploaded.data, fileName, bytes);
      if (clientMsgId === null) {
        toast.show(t('conversation.reconnecting'));
        return;
      }
      setPending((current) => [
        ...current,
        {
          clientMsgId,
          content: '',
          kind,
          // 图片要拿 localUri 立刻预览；文件也留着，确认前点卡片可先用本地副本打开
          localUri: uri,
          fileName: kind === 'file' ? fileName : undefined,
          fileSize: kind === 'file' ? bytes : undefined,
          failed: false,
        },
      ]);
      setTimeout(() => {
        scrollRef.current?.scrollToEnd({ animated: true });
      }, 50);
    } catch {
      toast.show(t('conversation.message.uploadFailed'));
    } finally {
      uploadingRef.current = false;
      setUploading(false);
    }
  };

  /** 打开附件选择面板（图片 / PDF 文件）；上传中不允许再开。 */
  const openAttachSheet = (): void => {
    if (uploadingRef.current) {
      return;
    }
    setAttachVisible(true);
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
                  {item.kind === 'image' ? (
                    // 本地 pending 图：先拿 localUri 预览，确认后由服务端消息替代
                    <MessageImage uri={item.localUri ?? null} out />
                  ) : item.kind === 'file' ? (
                    <MessageFile
                      fileName={item.fileName ?? null}
                      fileSize={item.fileSize === undefined ? null : String(item.fileSize)}
                      uri={item.localUri ?? null}
                      out
                    />
                  ) : (
                    <LocalizedText numberOfLines={8} style={styles.bubbleTextOut}>{item.content}</LocalizedText>
                  )}
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

          <View style={styles.composerWrap}>
            {uploading ? (
              <View style={styles.uploadingRow}>
                <ActivityIndicator size="small" color={color.brand.primary} />
                <LocalizedText numberOfLines={1} style={styles.uploadingText}>
                  {t('conversation.composer.uploading')}
                </LocalizedText>
              </View>
            ) : null}
            <View style={styles.composer}>
              <Pressable
                onPress={openAttachSheet}
                style={[styles.attachButton, uploading ? styles.buttonDisabled : null]}
                accessibilityRole="button"
                accessibilityLabel={t('conversation.composer.attach')}
                accessibilityState={{ disabled: uploading }}
                disabled={uploading}
              >
                <Ionicons name="add-circle-outline" size={size.cardIcon} color={color.brand.primary} />
              </Pressable>
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
                style={[styles.sendButton, draft.trim() === '' || uploading ? styles.sendDisabled : null]}
                accessibilityRole="button"
                accessibilityLabel={t('conversation.send')}
                accessibilityState={{ disabled: draft.trim() === '' || uploading }}
                disabled={draft.trim() === '' || uploading}
              >
                <Ionicons name="send" size={18} color={color.text.onBrand} />
              </Pressable>
            </View>
          </View>
        </View>
      ) : null}

      <AttachSheet
        visible={attachVisible}
        onClose={() => setAttachVisible(false)}
        onPickImage={() => {
          setAttachVisible(false);
          void pickImage();
        }}
        onPickFile={() => {
          setAttachVisible(false);
          void pickFile();
        }}
      />

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
          <MessageImage uri={message.fileUrl} out={out} />
        ) : message.msgType === 'file' ? (
          <MessageFile
            fileName={message.fileName}
            fileSize={message.fileSize}
            uri={message.fileUrl}
            out={out}
          />
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
  composerWrap: {
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
    backgroundColor: color.background.surface,
  },
  uploadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.sm,
  },
  uploadingText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  composer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: spacing.sm,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
  },
  attachButton: {
    width: size.inputHeight,
    height: size.inputHeight,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.background.input,
  },
  buttonDisabled: {
    opacity: 0.4,
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
