/**
 * 消息详情页（设计稿 `39-message-detail.html`，段 2 G8.3 建页、2026-11-14 由占位改为真实详情）。
 *
 * 职责：展示一条站内信的完整标题、时间与正文；正文在消息中心里被截断到 2 行，这里给全文。
 * 调用方：路由壳 `app/message-detail.tsx`；入口是消息中心列表点任意一条（带 `?id=`）。
 * 数据来源：`GET /api/v1/app/notifications/{id}`（`getNotification`）。
 *
 * 注意：
 *   1. **它原本是个结构占位**：段 2 时没有通知数据源，页面只显示「没有可展示的消息」，
 *      而且消息中心点击根本不跳这里（只标已读）。2026-11-14 补了后端详情接口 + 列表跳转，
 *      本页才真正可用。所以「点开通知没反应」的问题在这一页与 `MessagesPage` 一起修。
 *   2. **不带 `id` 时不请求**：直接 URL 访问本页（无参数）只会看到「没有可展示的消息」+
 *      返回消息中心，不拿列表首条充数。
 *   3. **不做已读回写**：已读由消息中心在点击时调 `markNotificationRead`（乐观更新），
 *      本页只读。两个动作分开才能在失败时分别重试。
 *   4. 四态：加载 / 错误（可重试）/ 正常 / 无 id（空态）。
 *   5. 正文不额外拼接：`content` 为 null（纯标题通知）时只显示标题与时间，不渲染空白块。
 *   6. **不新增 i18n key**：加载与错误复用 `LoadingState` / `ErrorState` 的既有四语文案，
 *      404/403 走 `errorText()`，避免为「消息不存在」临时生造维/哈/藏文译文。
 */

import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';

import { getNotification } from '../api/notifications';
import type { NotificationItem } from '../api/notifications';

/** 页面的数据状态。 */
type DetailState =
  | { phase: 'loading' }
  | { phase: 'error'; error: AppError }
  | { phase: 'ready'; item: NotificationItem | null };

/**
 * 渲染消息详情页。
 *
 * 副作用：挂载与 `id` 变化时拉一次详情（无 id 不发请求）。
 *
 * @returns 详情界面
 */
export function MessageDetailPage() {
  const { t } = useI18n();
  const router = useRouter();
  const params = useLocalSearchParams<{ id?: string }>();
  const id = typeof params.id === 'string' ? params.id : '';
  const [state, setState] = useState<DetailState>({ phase: 'loading' });

  /** 拉详情；`id` 为空时不请求，直接进「无可展示内容」。 */
  const load = useCallback(async (): Promise<void> => {
    if (id === '') {
      setState({ phase: 'ready', item: null });
      return;
    }
    setState({ phase: 'loading' });
    const result = await getNotification(id);
    if (!result.ok) {
      setState({ phase: 'error', error: result.error });
      return;
    }
    setState({ phase: 'ready', item: result.data });
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 空态：无 id（直接访问本页）或拿不到内容。 */
  const renderEmpty = () => (
    <View style={styles.emptyBox} testID="messageDetail-empty">
      <LocalizedText numberOfLines={2} style={styles.emptyTitle}>
        {t('content.messageDetail.emptyTitle')}
      </LocalizedText>
      <PrimaryButton
        label={t('content.messages.title')}
        variant="text"
        onPress={() => router.replace(PAGE_ROUTES.messages)}
      />
    </View>
  );

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('content.messageDetail.title')} />

      {state.phase === 'loading' ? <LoadingState rows={4} /> : null}

      {state.phase === 'error' ? (
        <View style={styles.pad}>
          <ErrorState
            error={state.error}
            onRetry={() => {
              void load();
            }}
          />
        </View>
      ) : null}

      {state.phase === 'ready' && state.item === null ? renderEmpty() : null}

      {state.phase === 'ready' && state.item !== null ? (
        <ScrollView contentContainerStyle={styles.body} testID="messageDetail-screen">
          <LocalizedText numberOfLines={3} style={styles.title}>{state.item.title}</LocalizedText>
          {state.item.createdAt === null ? null : (
            <LocalizedText numberOfLines={1} style={styles.time}>
              {formatServerDateTime(state.item.createdAt)}
            </LocalizedText>
          )}
          {state.item.content === null || state.item.content.trim() === '' ? null : (
            <View style={styles.contentBox}>
              <LocalizedText numberOfLines={20} style={styles.content}>{state.item.content}</LocalizedText>
            </View>
          )}
        </ScrollView>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  pad: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
  },
  body: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
    paddingBottom: spacing.xxl,
    gap: spacing.sm,
  },
  title: {
    ...typography.h3,
    color: color.text.primary,
  },
  time: {
    ...typography.caption,
    color: color.text.muted,
  },
  contentBox: {
    marginTop: spacing.md,
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
  },
  content: {
    ...typography.body,
    color: color.text.body,
  },
  emptyBox: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xxl,
    gap: spacing.sm,
  },
  emptyTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
    textAlign: 'center',
  },
});
