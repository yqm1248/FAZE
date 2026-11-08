/**
 * AI 对话页（第 3 段 F2 骨架 → F3/F4/F5 完整版；设计稿 `design/ui/03-agent.html`）。
 *
 * 职责：AI 会话主界面——顶栏（标题 / 历史 / 清空）、消息区（`FlatList inverted`）、
 * 输入条（multiline，最多 4 行）；承载四态、流式回答、法条引用、显式标识、
 * 转人工、长按复制、停止、断线重连与配额显示。
 * 调用方：路由壳 `app/(tabs)/agent.tsx`（底部 tab「智能体」）。
 * 数据来源：`features/ai/hooks/useAiStream.ts`（POST + SSE）、`useAiQuota.ts`（C5）。
 * 注意：
 *   1. **消息顺序与 `inverted`**：`messages` 存最新在前，`FlatList inverted` 把 `data[0]`
 *      渲染在底部；RTL 由 RN 自动镜像，不手工 `scaleX(-1)`。
 *   2. **每条 assistant 回答的叠放顺序**（工作流 F3 第 4、5 条）：
 *      气泡正文 → `LawRefCard`（有 refs 时）→ `AiGeneratedLabel`（显式标识，最下方）。
 *      思考中/失败的气泡不挂标识（还不是「AI 生成内容」的成品）。
 *   3. **降级模式**（F4）：`useAiStream` 判定 `ai_enabled === false`（或 C2 返回 degraded）后，
 *      整页换成 `AiLawSearchView`，**不显示错误页**。
 *   4. **停止**（F5）：生成中发送按钮变方块停止键；停止保留半截内容且不重连。
 *   5. **断线**（F3/F5）：浏览器 `offline` 事件或重连等待中，顶部出现 `OfflineBar`；
 *      重连逻辑在 `useAiStream`（最多 3 次，1s→2s→4s，不新建气泡）。
 *   6. 清空只清视图（`DELETE /app/ai/conversations/{id}` 在 G2 的历史页做）；对话历史入口
 *      跳 `/ai-history`（G1）。读 `conversationId` 恢复历史消息属于 F2 的活，尚未实现。
 *   7. `testID`：`ai-chat-input` / `ai-chat-send` / `ai-chat-clear` / `ai-chat-history` /
 *      `ai-msg-<index>` / `ai-law-ref-<index>` / `ai-quota-text` / `ai-stop-button` /
 *      `ai-retry-button` / `ai-transfer-button` / `offline-bar`（121 E2E 用）。
 */

import * as Clipboard from 'expo-clipboard';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import {
  FlatList,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';

import { useAiQuota } from '@/features/ai/hooks/useAiQuota';
import { useAiStream } from '@/features/ai/hooks/useAiStream';
import type { ChatMessage } from '@/features/ai/hooks/useAiStream';
import { useOnlineStatus } from '@/features/ai/hooks/useOnlineStatus';
import { AiLawSearchView } from '@/features/ai/pages/AiLawSearchView';
import { AiGeneratedLabel } from '@/shared/components/AiGeneratedLabel';
import { useToast } from '@/shared/components/AppToast';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { HistoryIcon, StopIcon, TrashIcon } from '@/shared/components/icons/AiIcons';
import { LawRefCard } from '@/shared/components/LawRefCard';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { MessageBubble } from '@/shared/components/MessageBubble';
import { OfflineBar } from '@/shared/components/OfflineBar';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/**
 * 计算气泡里要显示的文本（思考中 / 已停止 / 正常内容）。
 *
 * @param message 消息
 * @param t       翻译函数
 * @returns 气泡文本
 */
function bubbleContentOf(message: ChatMessage, t: (key: MessageKey) => string): string {
  if (message.pending === true && message.content === '') {
    return t('ai.chat.thinking');
  }
  if (message.stopped === true && message.content === '') {
    return t('ai.chat.stopped');
  }
  return message.content;
}

/**
 * AI 对话页。
 *
 * 副作用：进页读配额与 AI 开关；发送时提交问题并订阅 SSE；长按复制写剪贴板。
 *
 * @returns AI 对话界面（或降级检索界面）
 */
export function AiChatPage() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const [input, setInput] = useState('');
  const [clearVisible, setClearVisible] = useState(false);

  const quota = useAiQuota();
  const { online } = useOnlineStatus();
  const stream = useAiStream({ onDone: quota.refresh });
  const { messages, generating, reconnecting, modeLoading, degradedMode, refreshMode } = stream;

  // F4 第 4 条「重进页面恢复」：每次 tab 获得焦点时重新探测 ai_enabled（不要求实时热更新）。
  useFocusEffect(
    useCallback(() => {
      refreshMode();
    }, [refreshMode]),
  );

  const canSend = input.trim() !== '' && !quota.exhausted && !generating;

  /** 发送当前输入（发送后清空输入框）。 */
  const handleSend = useCallback((): void => {
    if (!canSend) {
      return;
    }
    stream.send(input);
    setInput('');
  }, [canSend, input, stream]);

  /** 长按气泡复制（F3 第 7 条）。 */
  const handleCopy = useCallback(
    (content: string): void => {
      void (async () => {
        await Clipboard.setStringAsync(content);
        toast.show(t('ai.chat.copied'));
      })();
    },
    [t, toast],
  );

  /** 历史入口（G1）：跳对话历史页。 */
  const handleHistory = useCallback((): void => {
    router.push(PAGE_ROUTES.aiHistory);
  }, [router]);

  /** 清空当前视图（本批次不调后端删除）。 */
  const handleClear = useCallback((): void => {
    stream.clear();
    setClearVisible(false);
  }, [stream]);

  if (modeLoading) {
    return (
      <Screen edges={['top', 'left', 'right']}>
        <View style={styles.center}>
          <LoadingState rows={3} />
        </View>
      </Screen>
    );
  }

  if (degradedMode) {
    return <AiLawSearchView />;
  }

  return (
    <Screen edges={['top', 'left', 'right']}>
      <View style={styles.appbar}>
        <LocalizedText numberOfLines={1} style={styles.appbarTitle}>
          {t('ai.chat.title')}
        </LocalizedText>
        <View style={styles.appbarActions}>
          <Pressable
            testID="ai-chat-history"
            onPress={handleHistory}
            accessibilityRole="button"
            accessibilityLabel={t('ai.chat.history')}
            style={styles.iconButton}
          >
            <HistoryIcon color={color.text.primary} />
          </Pressable>
          <Pressable
            testID="ai-chat-clear"
            onPress={() => setClearVisible(true)}
            accessibilityRole="button"
            accessibilityLabel={t('ai.chat.clear')}
            style={styles.iconButton}
          >
            <TrashIcon color={color.text.primary} />
          </Pressable>
        </View>
      </View>

      <KeyboardAvoidingView
        style={styles.body}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        {!online || reconnecting ? (
          <OfflineBar
            testID="offline-bar"
            label={online ? t('ai.chat.reconnecting') : t('ai.chat.offline')}
          />
        ) : null}

        {messages.length === 0 ? (
          <View style={styles.empty}>
            <LocalizedText numberOfLines={3} style={styles.emptyText}>
              {t('ai.chat.emptyHint')}
            </LocalizedText>
          </View>
        ) : (
          <FlatList
            data={messages}
            inverted
            keyExtractor={(item) => item.id}
            renderItem={({ item, index }) => (
              <View testID={`ai-msg-${String(index)}`} style={styles.messageRow}>
                <MessageBubble
                  role={item.role}
                  content={bubbleContentOf(item, t)}
                  pending={item.pending === true && item.content === ''}
                  onLongPress={
                    item.role === 'assistant' && item.content !== ''
                      ? () => handleCopy(item.content)
                      : undefined
                  }
                />

                {item.role === 'assistant' ? (
                  <View style={styles.assistantExtras}>
                    {(item.refs ?? []).map((refItem, refIndex) => (
                      <LawRefCard
                        key={`${item.id}-ref-${String(refIndex)}`}
                        refItem={refItem}
                        index={refIndex}
                      />
                    ))}

                    {item.failed === true ? (
                      <View style={styles.failureRow}>
                        <LocalizedText numberOfLines={2} style={styles.failureText}>
                          {t(item.failureKey ?? 'ai.chat.errorInterrupted')}
                        </LocalizedText>
                        <Pressable
                          testID="ai-retry-button"
                          onPress={stream.retry}
                          accessibilityRole="button"
                          accessibilityLabel={t('ai.chat.retry')}
                          style={styles.retryButton}
                        >
                          <LocalizedText numberOfLines={1} style={styles.retryButtonText}>
                            {t('ai.chat.retry')}
                          </LocalizedText>
                        </Pressable>
                      </View>
                    ) : null}

                    {item.degraded === true ? (
                      <Pressable
                        testID="ai-transfer-button"
                        onPress={() => router.push(PAGE_ROUTES.lawyerTab)}
                        accessibilityRole="button"
                        accessibilityLabel={t('ai.chat.transfer')}
                        style={styles.transferButton}
                      >
                        <LocalizedText numberOfLines={1} style={styles.transferButtonText}>
                          {t('ai.chat.transfer')}
                        </LocalizedText>
                      </Pressable>
                    ) : null}

                    {item.pending !== true && item.failed !== true ? <AiGeneratedLabel /> : null}
                  </View>
                ) : null}
              </View>
            )}
            contentContainerStyle={styles.list}
            keyboardShouldPersistTaps="handled"
          />
        )}

        <View style={styles.composer}>
          {quota.remaining !== null ? (
            <LocalizedText testID="ai-quota-text" numberOfLines={1} style={styles.quotaText}>
              {t('ai.chat.quotaRemaining', { n: quota.remaining })}
            </LocalizedText>
          ) : null}
          {quota.exhausted ? (
            <LocalizedText testID="ai-quota-exhausted" numberOfLines={2} style={styles.quotaExhausted}>
              {t('ai.chat.quotaExhausted')}
            </LocalizedText>
          ) : null}
          <View style={styles.composerBox}>
            <TextInput
              testID="ai-chat-input"
              value={input}
              onChangeText={setInput}
              placeholder={t('ai.chat.placeholder')}
              placeholderTextColor={color.text.muted}
              accessibilityLabel={t('ai.chat.placeholder')}
              editable={!quota.exhausted}
              multiline
              style={styles.input}
            />
            {generating ? (
              <Pressable
                testID="ai-stop-button"
                onPress={stream.stop}
                accessibilityRole="button"
                accessibilityLabel={t('ai.chat.stop')}
                style={[styles.send, styles.stop]}
              >
                <StopIcon color={color.text.onBrand} />
              </Pressable>
            ) : (
              <Pressable
                testID="ai-chat-send"
                onPress={handleSend}
                disabled={!canSend}
                accessibilityRole="button"
                accessibilityLabel={t('ai.chat.send')}
                accessibilityState={{ disabled: !canSend }}
                style={[styles.send, canSend ? null : styles.sendDisabled]}
              >
                <LocalizedText numberOfLines={1} style={styles.sendText}>
                  {t('ai.chat.send')}
                </LocalizedText>
              </Pressable>
            )}
          </View>
        </View>
      </KeyboardAvoidingView>

      <ConfirmDialog
        visible={clearVisible}
        title={t('ai.chat.clearTitle')}
        description={`${t('ai.chat.clearBody')}\n${t('ai.chat.clearHint')}`}
        confirmLabel={t('ai.chat.clearConfirm')}
        cancelLabel={t('ai.chat.clearCancel')}
        danger
        onConfirm={handleClear}
        onCancel={() => setClearVisible(false)}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  center: {
    flex: 1,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.xl,
  },
  appbar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
    backgroundColor: color.background.surface,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  appbarTitle: {
    ...typography.h2,
    color: color.text.primary,
  },
  appbarActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xxs,
  },
  iconButton: {
    width: 44,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.sm,
  },
  body: {
    flex: 1,
  },
  empty: {
    flex: 1,
    paddingTop: spacing.xxxl,
    paddingHorizontal: spacing.xxl,
    alignItems: 'center',
  },
  emptyText: {
    ...typography.bodyLarge,
    color: color.text.muted,
    textAlign: 'center',
  },
  list: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    gap: spacing.sm,
  },
  messageRow: {
    width: '100%',
  },
  assistantExtras: {
    width: '100%',
  },
  failureRow: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  failureText: {
    ...typography.caption,
    color: color.state.danger,
    flexShrink: 1,
  },
  retryButton: {
    paddingVertical: spacing.xxs,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    borderWidth: size.hairline,
    borderColor: color.brand.primary,
  },
  retryButtonText: {
    ...typography.caption,
    color: color.brand.primary,
  },
  transferButton: {
    marginTop: spacing.sm,
    alignSelf: 'flex-start',
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
  },
  transferButtonText: {
    ...typography.caption,
    color: color.brand.primaryPressed,
  },
  composer: {
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
    backgroundColor: color.background.surface,
  },
  quotaText: {
    ...typography.micro,
    color: color.text.secondary,
    marginBottom: spacing.xs,
  },
  quotaExhausted: {
    ...typography.micro,
    color: color.state.danger,
    marginBottom: spacing.xs,
  },
  composerBox: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: spacing.sm,
    paddingStart: spacing.md,
    paddingEnd: spacing.xs,
    paddingVertical: spacing.xs,
    minHeight: size.inputHeight,
    borderRadius: radius.lg,
    backgroundColor: color.background.input,
  },
  input: {
    flex: 1,
    minHeight: size.inputHeight,
    maxHeight: 4 * typography.body.lineHeight + spacing.md * 2,
    paddingVertical: spacing.sm,
    paddingHorizontal: 0,
    ...typography.body,
    color: color.text.body,
  },
  send: {
    minWidth: 56,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
  },
  stop: {
    minWidth: 44,
  },
  sendDisabled: {
    opacity: 0.4,
  },
  sendText: {
    ...typography.body,
    fontWeight: typography.h3.fontWeight,
    color: color.text.onBrand,
  },
});
