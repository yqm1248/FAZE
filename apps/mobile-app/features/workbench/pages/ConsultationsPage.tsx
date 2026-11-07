/**
 * 客户咨询列表（设计稿 `design/ui/63-consultations.html`，段 5 D1）。
 *
 * 职责：律师视角的会话列表——未读优先，其余按最近消息倒序（页面顶部一句话说明排序）。
 * 调用方：路由壳 `app/workbench-consultations.tsx`（从工作台宫格「我的客户」进入）。
 * 数据来源：`GET /api/v1/app/workbench/conversations`。
 * 注意：
 *   1. **没有筛选 Tab、没有搜索框**：设计稿明确没有，本段也不加（D1 第 1 条）。
 *   2. 列表项字段按设计稿：头像 + 未读角标 + 姓名 + 订单状态标签（已付款/未付款）+ 相对时间 +
 *      消息摘要；未读时摘要加粗。
 *   3. 「订单状态标签」是**布尔**（后端算好 `orderPaid`）：没有订单时显示「未付款」，不报错。
 *   4. 四态齐全：加载（骨架）/ 空（「还没有客户咨询」）/ 错误（重试）/ 正常。
 *
 * testID 清单：consultations-screen / consultations-list / consultations-row-{id} /
 *              consultations-unread-{id} / consultations-empty / consultations-error /
 *              consultations-retry / consultations-loading
 */

import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';

import { fetchConsultations } from '@/features/workbench/api/client';
import type { ConsultantRow } from '@/features/workbench/api/client';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { chatByConversationHref } from '@/shared/navigation/routes';
import type { AppError } from '@/shared/api/types';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/**
 * 把 UTC 时间格式化成相对时间（今天显示 HH:mm，昨天显示「昨天」，更早显示 M-D）。
 *
 * @param raw UTC 无时区字符串
 * @returns 展示文本；空或非法返回空串
 */
function formatRelative(raw: string | null): string {
  if (raw === null || raw === '') {
    return '';
  }
  const date = new Date(raw.endsWith('Z') ? raw : `${raw}Z`);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const now = new Date();
  const sameDay =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();
  const pad = (value: number): string => String(value).padStart(2, '0');
  if (sameDay) {
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }
  const yesterday = new Date(now.getTime() - 86_400_000);
  if (
    date.getFullYear() === yesterday.getFullYear() &&
    date.getMonth() === yesterday.getMonth() &&
    date.getDate() === yesterday.getDate()
  ) {
    return 'yesterday';
  }
  return `${date.getMonth() + 1}-${date.getDate()}`;
}

/**
 * 渲染客户咨询列表。
 *
 * 副作用：挂载时拉一次列表；下拉刷新再拉一次。
 *
 * @returns 咨询列表页
 */
export function ConsultationsPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [rows, setRows] = useState<ConsultantRow[]>([]);
  const [phase, setPhase] = useState<'loading' | 'success' | 'empty' | 'error'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  /** 拉列表。 */
  const load = useCallback(async (isRefresh = false): Promise<void> => {
    if (!isRefresh) {
      setPhase('loading');
    }
    const res = await fetchConsultations();
    if (!res.ok) {
      setError(res.error);
      setPhase('error');
      setRefreshing(false);
      return;
    }
    setError(null);
    setRows(res.data.list);
    setPhase(res.data.list.length === 0 ? 'empty' : 'success');
    setRefreshing(false);
  }, []);

  // 首屏加载一次：`load` 是稳定引用，所以这个 effect 只跑一次
  useEffect(() => {
    void load();
  }, [load]);

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('workbench.consult.title')} />
      <View style={styles.root} testID="consultations-screen">
        <LocalizedText numberOfLines={1} style={styles.sortHint}>
          {t('workbench.consult.sortHint')}
        </LocalizedText>

        {phase === 'loading' ? (
          <View style={styles.stateWrap} testID="consultations-loading">
            <LoadingState rows={5} />
          </View>
        ) : null}

        {phase === 'error' && error !== null ? (
          <View style={styles.stateWrap} testID="consultations-error">
            <ErrorState
              error={error}
              onRetry={() => void load()}
              retryTestID="consultations-retry"
            />
          </View>
        ) : null}

        {phase === 'empty' ? (
          <View style={styles.stateWrap} testID="consultations-empty">
            <EmptyState i18nKey="workbench.consult.empty" />
            <LocalizedText numberOfLines={2} style={styles.emptyHint}>
              {t('workbench.consult.emptyHint')}
            </LocalizedText>
          </View>
        ) : null}

        {phase === 'success' ? (
          <FlatList
            testID="consultations-list"
            data={rows}
            keyExtractor={(item) => item.conversationId}
            contentContainerStyle={styles.list}
            refreshControl={
              <RefreshControl
                refreshing={refreshing}
                onRefresh={() => {
                  setRefreshing(true);
                  void load(true);
                }}
              />
            }
            renderItem={({ item }) => {
              const unread = item.unreadCount > 0;
              const time = formatRelative(item.lastMsgAt);
              return (
                <Pressable
                  testID={`consultations-row-${item.conversationId}`}
                  accessibilityRole="button"
                  accessibilityLabel={item.peerName ?? t('workbench.consult.unnamed')}
                  onPress={() => router.push(chatByConversationHref(item.conversationId))}
                  style={({ pressed }) => [styles.row, pressed ? styles.pressed : null]}
                >
                  <View style={styles.avatar}>
                    {unread ? (
                      <View style={styles.badge} testID={`consultations-unread-${item.conversationId}`}>
                        <LocalizedText numberOfLines={1} style={styles.badgeText}>
                          {String(item.unreadCount > 99 ? 99 : item.unreadCount)}
                        </LocalizedText>
                      </View>
                    ) : null}
                  </View>
                  <View style={styles.rowBody}>
                    <View style={styles.rowHead}>
                      <LocalizedText numberOfLines={1} style={styles.name}>
                        {item.peerName ?? t('workbench.consult.unnamed')}
                      </LocalizedText>
                      <LocalizedText numberOfLines={1} style={styles.time}>
                        {time === 'yesterday' ? t('workbench.consult.yesterday') : time}
                      </LocalizedText>
                    </View>
                    <View style={styles.rowFoot}>
                      <LocalizedText
                        numberOfLines={1}
                        style={[styles.summary, unread ? styles.summaryUnread : null]}
                      >
                        {item.lastMessage ?? t('workbench.consult.noMessage')}
                      </LocalizedText>
                      <View style={[styles.orderTag, item.orderPaid ? styles.orderTagPaid : null]}>
                        <LocalizedText
                          numberOfLines={1}
                          style={[styles.orderTagText, item.orderPaid ? styles.orderTagTextPaid : null]}
                        >
                          {item.orderPaid
                            ? t('workbench.consult.orderPaid')
                            : t('workbench.consult.orderUnpaid')}
                        </LocalizedText>
                      </View>
                    </View>
                  </View>
                </Pressable>
              );
            }}
          />
        ) : null}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  sortHint: {
    ...typography.micro,
    color: color.text.muted,
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.sm,
  },
  stateWrap: { paddingHorizontal: spacing.xl },
  emptyHint: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: -spacing.lg,
  },
  list: { paddingHorizontal: spacing.xl, paddingBottom: spacing.xxxl, gap: spacing.sm },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    padding: spacing.md,
  },
  pressed: { opacity: 0.85 },
  avatar: {
    width: size.avatarSmall,
    height: size.avatarSmall,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  badge: {
    position: 'absolute',
    top: -spacing.xxs,
    end: -spacing.xxs,
    minWidth: spacing.lg,
    paddingHorizontal: spacing.xxs,
    borderRadius: radius.pill,
    backgroundColor: color.state.danger,
    alignItems: 'center',
  },
  badgeText: { ...typography.micro, color: color.text.onBrand },
  rowBody: { flex: 1, gap: spacing.xxs },
  rowHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  name: { ...typography.bodyLarge, color: color.text.primary, flexShrink: 1 },
  time: { ...typography.micro, color: color.text.muted },
  rowFoot: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  summary: { ...typography.caption, color: color.text.muted, flexShrink: 1 },
  summaryUnread: { color: color.text.primary, fontWeight: '700' },
  orderTag: {
    paddingHorizontal: spacing.xs,
    paddingVertical: spacing.xxs,
    borderRadius: radius.xs,
    backgroundColor: color.background.input,
  },
  orderTagPaid: { backgroundColor: color.state.successSoft },
  orderTagText: { ...typography.micro, color: color.text.muted },
  orderTagTextPaid: { color: color.state.success },
});
