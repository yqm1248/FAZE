/**
 * 我的投诉列表页（段 7 新增第 69 页；负责人 2026-11-09 拍板新开）。
 *
 * 职责：按时间倒序列出本人提交过的投诉——工单号、状态徽标、正文摘要、截图缩略、
 *       **后台回复内容与回复时间**；分页用「加载更多」。
 * 所属路由：`/complaint-list`（路由壳 `app/complaint-list.tsx`）；
 *           入口在「投诉」页（顶部 + 提交成功态）与「我的」页「我的与服务」分组。
 * 数据来源：`GET /app/complaints?pageNum=&pageSize=`（字段 id / targetType / targetId / reason /
 *           images / status / ticketNo / replyContent / replyAt / createdAt）。
 * 注意：
 *   1. **状态徽标必须有文字，不能只靠颜色**（负责人要求；RTL 与色弱用户都要能读）：
 *      `waiting` 待处理 / `processing` 处理中 / `replied` 已回复 / `closed` 已关闭。
 *      2026-11-09 后端修复了状态映射——此前恒返回 `pending`，现在返回工单状态。
 *   2. **后台回复是本页存在的理由**：`replyContent` 非空就整块展示（含 `replyAt`），
 *      不再让用户去客服中心问「我的投诉处理到哪了」。
 *   3. 截图是 `images` 字段里的 JSON 字符串（后端出参），用 `parseImageKeys()` 解析；
 *      缩略图要短时签名地址，逐张懒加载（`getDownloadUrl`），拿不到就不占位。
 *   4. 四态齐全：加载（骨架）/ 空（「还没有投诉记录」+ 去提交投诉）/ 错误（`ErrorState` + 重试）/ 正常。
 *   5. 分页：首屏 10 条，`hasMore`（后端下发）为真时给「加载更多」；加载更多失败只 toast，不清空已加载数据。
 *   6. 页面没有固定底栏；`ScrollView` 底部留 `spacing.xxl`，最后一条不会被压住（验收点 d）。
 */

import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';
import { getDownloadUrl } from '@/features/account/api/upload';

import { listMyComplaints } from '../api/complaint';
import { parseImageKeys } from '../types';
import type { ComplaintItem } from '../types';

/** 每页条数（首屏 10 条，加载更多也按 10）。 */
const PAGE_SIZE = 10;

/** 列表页的数据状态。 */
type ListState =
  | { phase: 'loading' }
  | { phase: 'error'; error: AppError }
  | { phase: 'ready'; items: ComplaintItem[]; total: number; hasMore: boolean };

/** 状态徽标的视觉与文案配置。 */
type StatusMeta = {
  /** 文案 key（**必须有文字**，颜色只做辅助）。 */
  labelKey: MessageKey;
  /** 徽标底色。 */
  background: string;
  /** 徽标文字色。 */
  tint: string;
};

/**
 * 把后端工单状态映射成徽标配置。
 *
 * 未知状态按「待处理」展示：宁可让用户看到「待处理」去问客服，
 * 也不要出现一个没有文案的空徽标。
 *
 * @param raw 后端 `status`（waiting / processing / replied / closed）
 * @returns 文案 key 与颜色
 */
function statusMeta(raw: string): StatusMeta {
  if (raw === 'processing') {
    return {
      labelKey: 'ops.complaintList.statusProcessing',
      background: color.brand.primarySoft,
      tint: color.brand.primaryPressed,
    };
  }
  if (raw === 'replied') {
    return {
      labelKey: 'ops.complaintList.statusReplied',
      background: color.state.successSoft,
      tint: color.state.success,
    };
  }
  if (raw === 'closed') {
    return {
      labelKey: 'ops.complaintList.statusClosed',
      background: color.background.input,
      tint: color.text.muted,
    };
  }
  return {
    labelKey: 'ops.complaintList.statusWaiting',
    background: color.background.input,
    tint: color.state.warning,
  };
}

/**
 * 投诉对象类型 → 文案 key。
 *
 * @param raw 后端 `targetType`（lawyer / platform / order）
 * @returns 文案 key
 */
function targetLabelKey(raw: string): MessageKey {
  if (raw === 'lawyer') {
    return 'ops.complaintList.targetLawyer';
  }
  if (raw === 'order') {
    return 'ops.complaintList.targetOrder';
  }
  if (raw === 'platform') {
    return 'ops.complaintList.targetService';
  }
  return 'ops.complaintList.targetUnknown';
}

/**
 * 一张投诉截图缩略图。
 *
 * 职责：把 oss_key 换成短时签名地址后渲染；拿不到地址时渲染占位块（不塌陷布局）。
 * 副作用：挂载时请求一次签名地址。
 *
 * @param props oss_key
 * @returns 48×48 缩略图或占位块
 */
function ComplaintThumb({ ossKey }: { ossKey: string }) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const result = await getDownloadUrl(ossKey);
      if (!cancelled && result.ok) {
        setUrl(result.data.url);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [ossKey]);

  if (url === null) {
    return (
      <View style={styles.thumbPlaceholder}>
        <Ionicons name="image-outline" size={18} color={color.text.muted} />
      </View>
    );
  }
  return <Image source={{ uri: url }} style={styles.thumb} contentFit="cover" />;
}

/**
 * 我的投诉列表页。
 *
 * 副作用：挂载拉第一页；点「加载更多」拉下一页；点「去提交投诉」跳 57 页。
 *
 * @returns 投诉记录列表界面
 */
export function ComplaintListPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();

  const [state, setState] = useState<ListState>({ phase: 'loading' });
  /** 当前已加载到第几页。 */
  const [page, setPage] = useState(1);
  /** 是否正在加载更多（防连点）。 */
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
    const result = await listMyComplaints(1, PAGE_SIZE);
    if (!result.ok) {
      if (silent) {
        return;
      }
      setState({ phase: 'error', error: result.error });
      return;
    }
    const { list, total, hasMore } = result.data;
    setPage(1);
    setState({
      phase: 'ready',
      items: list,
      total,
      // hasMore 缺省时按「已加载条数 < 总数」推算，兼容旧后端响应。
      hasMore: hasMore ?? list.length < total,
    });
  }, []);

  useEffect(() => {
    void loadFirstPage();
  }, [loadFirstPage]);

  // 从投诉详情 / 提交页返回时静默刷新；已翻页时跳过，避免打回第 1 页。
  useRefreshOnFocus(
    useCallback(() => {
      if (page !== 1) {
        return;
      }
      void loadFirstPage(true);
    }, [loadFirstPage, page]),
  );

  /** 加载下一页：失败只 toast，不清空已加载数据。 */
  const loadMore = async () => {
    if (state.phase !== 'ready' || loadingMore) {
      return;
    }
    setLoadingMore(true);
    const nextPage = page + 1;
    const result = await listMyComplaints(nextPage, PAGE_SIZE);
    setLoadingMore(false);
    if (!result.ok) {
      toast.show(errorText(result.error));
      return;
    }
    setPage(nextPage);
    setState((previous) => {
      if (previous.phase !== 'ready') {
        return previous;
      }
      const merged = [...previous.items, ...result.data.list];
      return {
        phase: 'ready',
        items: merged,
        total: result.data.total,
        hasMore: result.data.hasMore ?? merged.length < result.data.total,
      };
    });
  };

  /**
   * 渲染一条投诉卡。
   *
   * @param item 投诉行
   * @returns 卡片
   */
  const renderItem = (item: ComplaintItem) => {
    const meta = statusMeta(item.status);
    const thumbs = parseImageKeys(item.images);
    return (
      <View key={item.id} style={styles.card}>
        <View style={styles.cardHead}>
          <View style={styles.targetTag}>
            <LocalizedText numberOfLines={1} style={styles.targetText}>
              {t(targetLabelKey(item.targetType))}
            </LocalizedText>
          </View>
          <View style={[styles.statusTag, { backgroundColor: meta.background }]}>
            <LocalizedText numberOfLines={1} style={[styles.statusText, { color: meta.tint }]}>
              {t(meta.labelKey)}
            </LocalizedText>
          </View>
        </View>

        <LocalizedText numberOfLines={2} style={styles.reason}>
          {item.reason}
        </LocalizedText>

        <View style={styles.metaRow}>
          <LocalizedText numberOfLines={1} style={styles.metaText}>
            {t('ops.complaintList.timeLine', { time: formatServerDateTime(item.createdAt) })}
          </LocalizedText>
          {item.ticketNo === null ? null : (
            <LocalizedText numberOfLines={1} style={styles.ticketText}>
              {t('ops.complaintList.ticketLine', { ticketNo: item.ticketNo })}
            </LocalizedText>
          )}
        </View>

        {thumbs.length === 0 ? null : (
          <View style={styles.thumbs}>
            {thumbs.map((key) => (
              <ComplaintThumb key={key} ossKey={key} />
            ))}
          </View>
        )}

        {item.replyContent === null ? null : (
          <View style={styles.replyBox}>
            <View style={styles.replyHead}>
              <Ionicons name="chatbubble-ellipses-outline" size={14} color={color.brand.primary} />
              <LocalizedText numberOfLines={1} style={styles.replyLabel}>
                {t('ops.complaintList.replyLabel')}
              </LocalizedText>
              <LocalizedText numberOfLines={1} style={styles.replyTime}>
                {formatServerDateTime(item.replyAt)}
              </LocalizedText>
            </View>
            <LocalizedText numberOfLines={4} style={styles.replyText}>
              {item.replyContent}
            </LocalizedText>
          </View>
        )}
      </View>
    );
  };

  /** 空态块（标题 + 说明 + 去提交投诉）。 */
  const renderEmpty = () => (
    <View style={styles.empty}>
      <EmptyState i18nKey="ops.complaintList.emptyTitle" />
      <LocalizedText numberOfLines={3} style={styles.emptyDesc}>
        {t('ops.complaintList.emptyDesc')}
      </LocalizedText>
      <PrimaryButton
        label={t('ops.complaintList.emptyAction')}
        onPress={() => router.push(PAGE_ROUTES.complaint)}
        style={styles.emptyButton}
      />
    </View>
  );

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('ops.complaintList.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        {state.phase === 'loading' ? <LoadingState rows={4} /> : null}

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
          <View style={styles.body}>
            <LocalizedText numberOfLines={3} style={styles.lead}>
              {t('ops.complaintList.lead')}
            </LocalizedText>
            <View style={styles.list}>{state.items.map(renderItem)}</View>
            {state.hasMore ? (
              <PrimaryButton
                variant="outline"
                label={
                  loadingMore
                    ? t('ops.complaintList.loadingMore')
                    : t('ops.complaintList.loadMore')
                }
                onPress={() => {
                  void loadMore();
                }}
                disabled={loadingMore}
                style={styles.loadMore}
              />
            ) : (
              <LocalizedText numberOfLines={1} style={styles.noMore}>
                {t('ops.complaintList.noMore')}
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
    // 页面无固定底栏；底部留白保证最后一条完整可见（验收点 d）。
    paddingBottom: spacing.xxl,
  },
  body: {
    gap: spacing.md,
  },
  lead: {
    ...typography.caption,
    color: color.text.muted,
  },
  list: {
    gap: spacing.md,
  },
  card: {
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.sm,
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  targetTag: {
    paddingHorizontal: spacing.xs,
    paddingVertical: spacing.xxs,
    borderRadius: radius.xs,
    backgroundColor: color.background.input,
  },
  targetText: {
    ...typography.micro,
    color: color.text.secondary,
  },
  statusTag: {
    paddingHorizontal: spacing.xs,
    paddingVertical: spacing.xxs,
    borderRadius: radius.xs,
  },
  statusText: {
    ...typography.micro,
    fontWeight: typography.h3.fontWeight,
  },
  reason: {
    ...typography.body,
    color: color.text.body,
  },
  metaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: spacing.md,
  },
  metaText: {
    ...typography.micro,
    color: color.text.muted,
  },
  ticketText: {
    ...typography.micro,
    color: color.text.secondary,
  },
  thumbs: {
    flexDirection: 'row',
    gap: spacing.xs,
  },
  thumb: {
    width: 48,
    height: 48,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  thumbPlaceholder: {
    width: 48,
    height: 48,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
  },
  replyBox: {
    padding: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.brand.primarySoft,
    gap: spacing.xxs,
  },
  replyHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  replyLabel: {
    ...typography.micro,
    fontWeight: typography.h3.fontWeight,
    color: color.brand.primaryPressed,
    flex: 1,
  },
  replyTime: {
    ...typography.micro,
    color: color.text.muted,
  },
  replyText: {
    ...typography.bodySmall,
    color: color.text.body,
  },
  loadMore: {
    marginTop: spacing.xs,
  },
  noMore: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.xs,
  },
  empty: {
    alignItems: 'center',
    paddingTop: spacing.xxl,
    gap: spacing.sm,
  },
  emptyDesc: {
    ...typography.bodySmall,
    color: color.text.muted,
    textAlign: 'center',
  },
  emptyButton: {
    marginTop: spacing.md,
    width: 180,
  },
});
