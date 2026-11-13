/**
 * 消息中心页（设计稿 `38-messages.html`）——站内信列表 + 未读标记。
 *
 * 职责：拉取当前用户的站内信（公告 / 订单提醒 / 工单回复 / 安全提醒），按时间倒序展示，
 *       点击未读项标记已读，支持「加载更多」与下拉刷新语义的静默刷新。
 * 调用方：路由壳 `app/messages.tsx`；入口在首页快捷入口与「我的」页。
 * 数据来源：`GET /api/v1/app/notifications`（游标分页）、`POST .../{id}/read`。
 * 注意：
 *   1. **后端是游标分页不是页码分页**：记「最后一条 id」而不是页码；服务端用 `id < cursor`。
 *   2. 「加载更多」按钮只在还有下一页时出现；返回空数组即视为到底。
 *   3. 标记已读是**乐观更新**：先改本地状态让圆点立刻消失，失败再回滚并 toast——
 *      等接口回来才变化会让用户以为没点中。
 *   4. 四态齐全：加载（骨架）/ 空（保留原空态与「去找律师」）/ 错误（ErrorState + 重试）/ 正常。
 *   5. 不做「全部已读」：后端没有批量已读接口，前端软删/批量循环调单条都会放大请求，先不做。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { DirectionalIcon } from '@/shared/components/DirectionalIcon';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';

import { listNotifications, markNotificationRead } from '../api/notifications';
import type { NotificationItem } from '../api/notifications';

/** 每页条数。 */
const PAGE_SIZE = 20;

/** 列表页的数据状态。 */
type ListState =
  | { phase: 'loading' }
  | { phase: 'error'; error: AppError }
  | { phase: 'ready'; items: NotificationItem[]; hasMore: boolean };

/**
 * 一条站内信卡片。
 *
 * @param props.item       站内信
 * @param props.onPress    点击回调（未读时用于标记已读）
 * @returns 卡片
 */
function NotificationCard({ item, onPress }: { item: NotificationItem; onPress: () => void }) {
  const unread = item.isRead === 0;
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      style={({ pressed }) => [styles.card, pressed && styles.cardPressed]}
    >
      <View style={styles.cardHead}>
        <View style={styles.titleRow}>
          {unread ? <View style={styles.dot} /> : null}
          <LocalizedText numberOfLines={1} style={[styles.cardTitle, unread && styles.cardTitleUnread]}>
            {item.title}
          </LocalizedText>
        </View>
        <LocalizedText numberOfLines={1} style={styles.cardTime}>
          {formatServerDateTime(item.createdAt)}
        </LocalizedText>
      </View>
      {item.content ? (
        <LocalizedText numberOfLines={2} style={styles.cardBody}>
          {item.content}
        </LocalizedText>
      ) : null}
    </Pressable>
  );
}

/**
 * 消息中心页。
 *
 * 副作用：进入时拉一次列表；点击未读项时向服务端写「已读」。
 *
 * @returns 站内信列表界面
 */
export function MessagesPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const [state, setState] = useState<ListState>({ phase: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);

  /**
   * 拉第一页（首屏与错误态重试共用）。
   *
   * @param silent 静默刷新（聚焦用）：不置 loading、失败保留已有列表
   */
  const loadFirstPage = useCallback(async (silent = false) => {
    if (!silent) {
      setState({ phase: 'loading' });
    }
    const result = await listNotifications({ limit: PAGE_SIZE });
    if (!result.ok) {
      if (silent) {
        return;
      }
      setState({ phase: 'error', error: result.error });
      return;
    }
    const items = result.data ?? [];
    setState({ phase: 'ready', items, hasMore: items.length >= PAGE_SIZE });
  }, []);

  useEffect(() => {
    void loadFirstPage();
  }, [loadFirstPage]);

  // 从其他页面返回时静默刷新未读状态。
  useRefreshOnFocus(
    useCallback(() => {
      void loadFirstPage(true);
    }, [loadFirstPage]),
  );

  /** 加载更多：失败只 toast，不清空已加载数据。 */
  const loadMore = async () => {
    if (state.phase !== 'ready' || loadingMore || state.items.length === 0) {
      return;
    }
    setLoadingMore(true);
    const last = state.items[state.items.length - 1];
    if (!last) {
      setLoadingMore(false);
      return;
    }
    const result = await listNotifications({ cursor: last.id, limit: PAGE_SIZE });
    setLoadingMore(false);
    if (!result.ok) {
      toast.show(errorText(result.error));
      return;
    }
    const next = result.data ?? [];
    setState((previous) => {
      if (previous.phase !== 'ready') {
        return previous;
      }
      // 按 id 去重：游标分页在数据插入时可能重复返回同一条
      const seen = new Set(previous.items.map((item) => item.id));
      const merged = [...previous.items, ...next.filter((item) => !seen.has(item.id))];
      return { phase: 'ready', items: merged, hasMore: next.length >= PAGE_SIZE };
    });
  };

  /**
   * 点击一条站内信：未读则先本地置读（乐观），再调接口，失败回滚。
   *
   * @param item 站内信
   */
  const handlePress = (item: NotificationItem) => {
    if (item.isRead === 1) {
      return;
    }
    setState((previous) => {
      if (previous.phase !== 'ready') {
        return previous;
      }
      return {
        ...previous,
        items: previous.items.map((row) => (row.id === item.id ? { ...row, isRead: 1 } : row)),
      };
    });
    void markNotificationRead(item.id).then((result) => {
      if (result.ok) {
        return;
      }
      // 回滚并提示：不能让「点了变已读、下次进来又未读」这种状态漂移悄悄发生
      setState((previous) => {
        if (previous.phase !== 'ready') {
          return previous;
        }
        return {
          ...previous,
          items: previous.items.map((row) => (row.id === item.id ? { ...row, isRead: 0 } : row)),
        };
      });
      toast.show(errorText(result.error));
    });
  };

  /** 空态块（保留段 2 的空态文案与「去找律师」入口）。 */
  const renderEmpty = () => (
    <View style={styles.empty}>
      <View style={styles.iconBox}>
        <Ionicons name="notifications-off-outline" size={40} color={color.text.muted} />
      </View>
      <LocalizedText numberOfLines={2} style={styles.emptyTitle}>
        {t('content.messages.emptyTitle')}
      </LocalizedText>
      <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
        {t('content.messages.emptyDesc')}
      </LocalizedText>
      <Pressable
        onPress={() => router.push(PAGE_ROUTES.lawyerTab)}
        accessibilityRole="button"
        style={styles.chatEntry}
      >
        <Ionicons name="chatbubbles-outline" size={18} color={color.brand.primary} />
        <LocalizedText numberOfLines={1} style={styles.chatEntryText}>
          {t('content.messages.lawyerEntry')}
        </LocalizedText>
      </Pressable>
      <PrimaryButton
        label={t('content.messages.backHome')}
        variant="text"
        onPress={() => router.replace('/home')}
      />
    </View>
  );

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('content.messages.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        {/*
          「我的咨询」入口条（2026-11-14 补）。
          会话列表没有独立一级入口（底部 5 tab 已定稿），而用户找「那个律师对话」时
          第一站是消息中心，所以这里放在最顶——**任何状态下都显示**（不依赖站内信是否加载成功，
          它只是导航）。未读数不在这里算：站内信未读与会话未读是两个口径，混在一起会互相打架。
        */}
        <Pressable
          testID="messages-conversations-entry"
          accessibilityRole="button"
          accessibilityLabel={t('conversation.list.title')}
          onPress={() => router.push(PAGE_ROUTES.conversations)}
          style={({ pressed }) => [styles.convEntry, pressed ? styles.convEntryPressed : null]}
        >
          <View style={styles.convEntryIcon}>
            <Ionicons name="chatbubbles-outline" size={22} color={color.brand.primary} />
          </View>
          <View style={styles.convEntryBody}>
            <LocalizedText numberOfLines={1} style={styles.convEntryTitle}>
              {t('conversation.list.title')}
            </LocalizedText>
          </View>
          <DirectionalIcon name="chevron-forward" size={18} color={color.text.muted} />
        </Pressable>

        {state.phase === 'loading' ? <LoadingState rows={5} /> : null}

        {state.phase === 'error' ? (
          <ErrorState
            error={state.error}
            onRetry={() => {
              void loadFirstPage();
            }}
          />
        ) : null}

        {state.phase === 'ready' && state.items.length === 0 ? renderEmpty() : null}

        {state.phase === 'ready' && state.items.length > 0 ? (
          <View style={styles.list}>
            {state.items.map((item) => (
              <NotificationCard key={item.id} item={item} onPress={() => handlePress(item)} />
            ))}
            {state.hasMore ? (
              <PrimaryButton
                variant="outline"
                label={loadingMore ? t('content.messages.loadingMore') : t('content.messages.loadMore')}
                onPress={() => {
                  void loadMore();
                }}
                disabled={loadingMore}
                style={styles.loadMore}
              />
            ) : (
              <LocalizedText numberOfLines={1} style={styles.noMore}>
                {t('content.messages.noMore')}
              </LocalizedText>
            )}
          </View>
        ) : null}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    // 页面无固定底栏；底部留白保证最后一条完整可见。
    paddingBottom: spacing.xxl,
  },
  convEntry: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.md,
  },
  convEntryPressed: { opacity: 0.85 },
  convEntryIcon: {
    width: size.avatarSmall,
    height: size.avatarSmall,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.brand.primarySoft,
  },
  convEntryBody: { flex: 1, gap: spacing.xxs },
  convEntryTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  convEntryDesc: {
    ...typography.caption,
    color: color.text.muted,
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingTop: spacing.xxxl,
    gap: spacing.sm,
  },
  iconBox: {
    width: 88,
    height: 88,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.background.input,
    marginBottom: spacing.sm,
  },
  emptyTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
    textAlign: 'center',
  },
  emptyDesc: {
    ...typography.bodySmall,
    color: color.text.muted,
    textAlign: 'center',
  },
  chatEntry: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    marginTop: spacing.lg,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
  },
  chatEntryText: {
    ...typography.bodySmall,
    color: color.brand.primary,
  },
  list: {
    gap: spacing.md,
  },
  card: {
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.xs,
  },
  cardPressed: {
    opacity: 0.7,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    flexShrink: 1,
  },
  dot: {
    width: 7,
    height: 7,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
  },
  cardTitle: {
    ...typography.body,
    color: color.text.primary,
    flexShrink: 1,
  },
  cardTitleUnread: {
    fontWeight: '600',
  },
  cardTime: {
    ...typography.caption,
    color: color.text.muted,
  },
  cardBody: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  loadMore: {
    marginTop: spacing.sm,
  },
  noMore: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.sm,
  },
});
