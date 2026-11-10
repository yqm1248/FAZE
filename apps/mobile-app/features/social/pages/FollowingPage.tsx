/**
 * 关注律师页（APP 52；设计稿 `52-following.html`）。
 *
 * 职责：列出关注过的律师，按最近活跃排序；三种状态必须能区分——
 *       「在线 + 有新动态」「离线 + 回复过咨询」「长期未活跃（明确写出来）」；
 *       支持取消关注与发消息。
 * 所属路由：`/following`（路由壳 `app/following.tsx`）；入口在「我的」页与全部功能页。
 * 数据来源：`GET /app/social/follows?cursor=`（律师 id / 在线状态 / 最近活跃时间 / 最新动态）、
 *           `DELETE /app/social/follows?lawyerId=`（取消关注；接口见 `api/social.ts` 注意事项 2）。
 * 注意：
 *   1. **三态判定在 `types.ts` 的 `followDisplayState()` 里**（优先级：长期未活跃 > 在线 > 回复咨询）。
 *      长期未活跃（≥30 天没上线）**必须显式写出**，让用户自己决定要不要继续关注——
 *      只显示「2 个月前在线」不够，用户会以为只是没看。
 *   2. 排序按「最近活跃」，排序规则写在页面顶部（设计稿要求，避免用户以为列表乱了）。
 *   3. 取消关注做成弱化文字按钮（不是主按钮，防误触）；点「发消息」跳聊天页。
 *   4. 空态文案照稿：标题「还没有关注律师」+ 说明 + 「去找律师」。
 *   5. 四态：加载（骨架）/ 错误（可重试）/ 空（自定义空态块）/ 正常。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import type { AsyncState } from '@/shared/components/AsyncBoundary';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { chatHref, PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';
import { formatServerDate, parseServerTime } from '@/shared/utils/serverTime';

import { getFollows, unfollowLawyer } from '../api/social';
import { followDisplayState } from '../types';
import type { FollowDisplayState, FollowItem, FollowsData } from '../types';

/** 「长期未活跃」的判定阈值（天），与设计稿「近 30 天没有发布内容」一致。 */
const INACTIVE_DAYS = 30;

/**
 * 把「最近活跃时间」说成一句人话。
 *
 * @param lastActiveAt 服务端时间串
 * @returns 如「今天在线」「3 天前在线」「2 个月前在线」；解析失败返回「近期未上线」
 */
function recentActiveText(lastActiveAt: string | null, t: ReturnType<typeof useI18n>['t']): string {
  const time = parseServerTime(lastActiveAt);
  if (time === null) {
    return t('lawyer.following.recentUnknown');
  }
  const days = Math.floor((Date.now() - time) / (1000 * 60 * 60 * 24));
  if (days <= 0) {
    return t('lawyer.following.activeToday');
  }
  if (days < INACTIVE_DAYS) {
    return t('lawyer.following.activeDaysAgo', { days });
  }
  const months = Math.max(1, Math.floor(days / 30));
  return t('lawyer.following.activeMonthsAgo', { months });
}

/** 三种展示状态对应的标签底色与文字色。 */
const STATE_TONE: Record<FollowDisplayState, { background: string; color: string }> = {
  active: { background: color.state.successSoft, color: color.state.success },
  replied: { background: color.background.input, color: color.text.muted },
  inactive: { background: color.background.input, color: color.text.muted },
};

/**
 * 关注律师页。
 *
 * 副作用：挂载请求列表；取消关注调删除接口并本地移除。
 *
 * @returns 关注律师列表界面
 */
export function FollowingPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();

  const [state, setState] = useState<AsyncState<FollowsData>>({ phase: 'loading' });
  /** 正在取关的律师 id（防连点）。 */
  const [removingId, setRemovingId] = useState<string | null>(null);

  /**
   * 拉关注列表。
   *
   * @param silent 静默刷新（聚焦用）：不置 loading、失败保留已有列表
   */
  const load = useCallback(async (silent = false) => {
    if (!silent) {
      setState({ phase: 'loading' });
    }
    const result = await getFollows();
    if (!result.ok) {
      if (silent) {
        return;
      }
      setState({ phase: 'error', error: result.error });
      return;
    }
    setState({ phase: 'success', data: result.data });
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // 从律师详情返回时静默刷新关注状态（不闪骨架）。
  useRefreshOnFocus(
    useCallback(() => {
      void load(true);
    }, [load]),
  );

  /**
   * 取消关注。
   *
   * 副作用：调删除接口；成功后本地移除该卡（失败保留并提示）。
   *
   * @param item 被取关的律师
   */
  const cancelFollow = async (item: FollowItem) => {
    if (removingId !== null) {
      return;
    }
    setRemovingId(item.lawyerId);
    try {
      const result = await unfollowLawyer(item.lawyerId);
      if (!result.ok) {
        toast.show(errorText(result.error));
        return;
      }
      setState((previous) => {
        if (previous.phase !== 'success') {
          return previous;
        }
        return {
          phase: 'success',
          data: {
            ...previous.data,
            list: previous.data.list.filter((entry) => entry.lawyerId !== item.lawyerId),
          },
        };
      });
      toast.show(t('lawyer.following.unfollowed'));
    } finally {
      setRemovingId(null);
    }
  };

  /**
   * 渲染一张关注卡。
   *
   * @param item 关注项
   * @returns 律师卡
   */
  const renderCard = (item: FollowItem) => {
    const displayState = followDisplayState(item, INACTIVE_DAYS);
    const tone = STATE_TONE[displayState];
    const isInactive = displayState === 'inactive';
    // 动态区：有服务端 feed 文案就用它，没有就按状态给内置文案。
    const feedText =
      item.feedTitle !== null && item.feedTitle !== ''
        ? item.feedTitle
        : isInactive
          ? t('lawyer.following.inactiveFeed')
          : item.feedType === 'reply'
            ? t('lawyer.following.feedReplyFallback')
            : t('lawyer.following.feedNone');
    const feedIcon =
      item.feedType === 'article'
        ? 'document-text-outline'
        : item.feedType === 'reply'
          ? 'chatbubble-ellipses-outline'
          : 'information-circle-outline';
    return (
      <View key={item.lawyerId} style={styles.card}>
        <View style={styles.head}>
          <View style={styles.avatar}>
            <Ionicons name="person" size={26} color={color.brand.primaryPressed} />
          </View>
          <View style={styles.info}>
            <View style={styles.nameRow}>
              <LocalizedText numberOfLines={1} style={styles.name}>
                {item.name}
              </LocalizedText>
              {item.years === null ? null : (
                <LocalizedText numberOfLines={1} style={styles.years}>
                  {t('lawyer.browse.years', { years: item.years })}
                </LocalizedText>
              )}
            </View>
            <LocalizedText numberOfLines={1} style={styles.firm}>
              {item.firm ?? t('lawyer.following.firmUnknown')}
            </LocalizedText>
          </View>
          <View style={[styles.stateTag, { backgroundColor: tone.background }]}>
            {displayState === 'active' ? <View style={styles.onlineDot} /> : null}
            <LocalizedText numberOfLines={1} style={[styles.stateText, { color: tone.color }]}>
              {isInactive
                ? t('lawyer.following.stateInactive')
                : displayState === 'active' && item.onlineStatus === 'online'
                  ? t('lawyer.browse.online')
                  : recentActiveText(item.lastActiveAt, t)}
            </LocalizedText>
          </View>
        </View>

        {item.specialties.length === 0 ? null : (
          <View style={styles.tags}>
            {item.specialties.slice(0, 3).map((specialty) => (
              <View key={specialty} style={styles.tag}>
                <LocalizedText numberOfLines={1} style={styles.tagText}>
                  {specialty}
                </LocalizedText>
              </View>
            ))}
          </View>
        )}

        <View style={styles.feed}>
          <Ionicons name={feedIcon} size={18} color={color.text.muted} />
          <View style={styles.feedMain}>
            <LocalizedText numberOfLines={2} style={styles.feedText}>
              {feedText}
            </LocalizedText>
            <LocalizedText numberOfLines={1} style={styles.feedTime}>
              {isInactive
                ? t('lawyer.following.lastActive', {
                    date: formatServerDate(item.lastActiveAt),
                  })
                : item.feedAt === null
                  ? t('lawyer.following.noRecentActivity')
                  : recentActiveText(item.feedAt, t)}
            </LocalizedText>
          </View>
        </View>

        <View style={styles.actions}>
          <Pressable
            onPress={() => {
              void cancelFollow(item);
            }}
            accessibilityRole="button"
            accessibilityLabel={t('lawyer.following.cancelFollow')}
            style={({ pressed }) => [styles.unfollow, pressed ? styles.pressed : null]}
          >
            <LocalizedText numberOfLines={1} style={styles.unfollowText}>
              {t('lawyer.following.cancelFollow')}
            </LocalizedText>
          </Pressable>
          <Pressable
            onPress={() => router.push(chatHref(item.lawyerId))}
            accessibilityRole="button"
            accessibilityLabel={t('lawyer.following.message')}
            style={({ pressed }) => [styles.messageButton, pressed ? styles.pressed : null]}
          >
            <LocalizedText numberOfLines={1} style={styles.messageText}>
              {t('lawyer.following.message')}
            </LocalizedText>
          </Pressable>
        </View>
      </View>
    );
  };

  /** 空态块（标题 + 说明 + 去找律师按钮，照稿）。 */
  const renderEmpty = () => (
    <View style={styles.empty}>
      <Ionicons name="person-outline" size={56} color={color.border.outline} />
      <LocalizedText numberOfLines={1} style={styles.emptyTitle}>
        {t('lawyer.following.emptyTitle')}
      </LocalizedText>
      <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
        {t('lawyer.following.emptyDesc')}
      </LocalizedText>
      <PrimaryButton
        label={t('lawyer.following.emptyAction')}
        onPress={() => router.push(PAGE_ROUTES.lawyerTab)}
        style={styles.emptyButton}
      />
    </View>
  );

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('lawyer.following.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        <AsyncBoundary state={state} onRetry={() => void load()} loadingRows={3}>
          {(data) =>
            data.list.length === 0 ? (
              renderEmpty()
            ) : (
              <View style={styles.listBody}>
                <View style={styles.sortNote}>
                  <Ionicons name="funnel-outline" size={15} color={color.text.muted} />
                  <LocalizedText numberOfLines={1} style={styles.sortText}>
                    {t('lawyer.following.sortNote')}
                  </LocalizedText>
                </View>
                <View style={styles.list}>{data.list.map(renderCard)}</View>
                <LocalizedText numberOfLines={1} style={styles.noMore}>
                  {t('lawyer.following.noMore')}
                </LocalizedText>
                <LocalizedText numberOfLines={3} style={styles.pageNote}>
                  {t('lawyer.following.pageNote')}
                </LocalizedText>
              </View>
            )
          }
        </AsyncBoundary>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxl,
  },
  listBody: {
    gap: spacing.sm,
  },
  sortNote: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    paddingTop: spacing.md,
  },
  sortText: {
    ...typography.caption,
    color: color.text.muted,
  },
  list: {
    gap: spacing.md,
    paddingTop: spacing.sm,
  },
  card: {
    paddingTop: spacing.md,
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.sm,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.sm,
  },
  head: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  avatar: {
    width: 48,
    height: 48,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  info: {
    flex: 1,
  },
  nameRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: spacing.sm,
  },
  name: {
    ...typography.bodyLarge,
    fontWeight: typography.h3.fontWeight,
    color: color.text.primary,
    flexShrink: 1,
  },
  years: {
    ...typography.micro,
    color: color.text.muted,
  },
  firm: {
    ...typography.caption,
    color: color.text.secondary,
    marginTop: spacing.xxs,
  },
  stateTag: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xxs,
    paddingHorizontal: spacing.xs,
    paddingVertical: spacing.xxs,
    borderRadius: radius.xs,
  },
  stateText: {
    ...typography.micro,
  },
  onlineDot: {
    width: 6,
    height: 6,
    borderRadius: radius.pill,
    backgroundColor: color.state.success,
  },
  tags: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
  },
  tag: {
    paddingHorizontal: spacing.xs,
    paddingVertical: spacing.xxs,
    borderRadius: radius.xs,
    backgroundColor: color.background.input,
  },
  tagText: {
    ...typography.micro,
    color: color.text.secondary,
  },
  feed: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
    padding: spacing.sm,
    borderRadius: radius.sm,
    backgroundColor: color.background.canvas,
  },
  feedMain: {
    flex: 1,
    gap: spacing.xxs,
  },
  feedText: {
    ...typography.caption,
    color: color.text.body,
  },
  feedTime: {
    ...typography.micro,
    color: color.text.muted,
  },
  actions: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  unfollow: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.xxs,
  },
  unfollowText: {
    ...typography.caption,
    color: color.text.muted,
  },
  messageButton: {
    height: 32,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  messageText: {
    ...typography.caption,
    color: color.brand.primaryPressed,
  },
  noMore: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.sm,
  },
  pageNote: {
    ...typography.caption,
    color: color.text.muted,
    marginTop: spacing.sm,
  },
  empty: {
    alignItems: 'center',
    paddingTop: spacing.huge,
    paddingHorizontal: spacing.lg,
    gap: spacing.sm,
  },
  emptyTitle: {
    ...typography.h3,
    color: color.text.primary,
    marginTop: spacing.sm,
  },
  emptyDesc: {
    ...typography.bodySmall,
    color: color.text.muted,
    textAlign: 'center',
  },
  emptyButton: {
    marginTop: spacing.lg,
    width: 180,
  },
  pressed: {
    opacity: 0.7,
  },
});
