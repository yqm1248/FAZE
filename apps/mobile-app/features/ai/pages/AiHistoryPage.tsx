/**
 * 对话历史页（第 3 段 G1 列表 + G2 左滑删除；设计稿 `design/ui/18-ai-history.html`）。
 *
 * 职责：拉「我的 AI 会话」列表 → 按 Asia/Shanghai 分「今天 / 昨天 / 更早」→ 点击回看该会话；
 * 左滑露出「删除」→ 二次确认 → 软删并从列表移除。四态（正常 / 加载 / 空 / 错误）全处理。
 * 调用方：路由壳 `app/ai-history.tsx`；入口是 `AiChatPage` 顶栏的「对话历史」按钮。
 * 数据来源：`GET /api/v1/app/ai/conversations?pageNum=&pageSize=`（列表 + 摘要，一次查完）、
 *           `DELETE /api/v1/app/ai/conversations/{id}`（G2 软删）。
 * 注意：
 *   1. **时区固定 Asia/Shanghai，不跟设备时区**（工作流 G1 第 3 条）。服务端 `lastMessageAt`
 *      是 UTC 无偏移串（`2026-11-06T17:29:13`），`new Date(裸串)` 会按设备本地时区解释、
 *      整体偏 8 小时；这里先用 `parseServerTime` 补 `Z` 解析成时间戳，再**加固定 8 小时**
 *      后用 `getUTC*` 读日历字段——全程不依赖 `Intl`（Hermes 的 locale/时区数据覆盖不确定，
 *      技术路线 §13.7.1 已裁决不用 Intl）。新疆等非东八区设备上分组也一致。
 *   2. **分组不按后端字段**：后端只给时间串，group 是前端算的（工作流 G1 第 3 条）。
 *   3. **删除是软删且用户侧不可恢复**（工作流 G2 第 4、5 条）：成功后只从本页列表移除，
 *      不提供「恢复」入口；后台 A-28 仍能查到（标「用户已删除」）。
 *   4. **失败不乐观删除**：请求失败时条目保留、只弹 `AppToast`（网络错误 / `401008` 无权操作），
 *      避免「先消失再冒出来」的闪烁。
 *   5. 左滑用 `react-native-gesture-handler` 的 `Swipeable` + `renderRightActions`；
 *      RTL 由 RN 自动镜像（`Swipeable` 内部按 `I18nManager.isRTL` 调 `flexDirection`），
 *      **不手工反转**。前提是根布局包了 `GestureHandlerRootView`（`app/_layout.tsx` 已加）。
 *   6. `testID`：`ai-history-item-<index>` / `ai-history-empty` / `ai-history-retry` /
 *      `ai-history-delete-<index>`（121 E2E 用）。
 *   7. **F2 衔接点**：点击条目跳 `/(tabs)/agent?conversationId=<id>`（`aiChatConversationHref`），
 *      「读参数并恢复历史消息」属于 F2 的活（本页只负责跳转，未在此实现读取）。
 */

import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { FlatList, Pressable, StyleSheet, View } from 'react-native';
import { Swipeable } from 'react-native-gesture-handler';

import { deleteConversation, listConversations } from '@/features/ai/api/ai';
import type { AiConversationBrief } from '@/features/ai/api/ai';
import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ConfirmDialog } from '@/shared/components/ConfirmDialog';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { aiChatConversationHref } from '@/shared/navigation/routes';
import { color, size, spacing, typography } from '@/shared/theme';
import { parseServerTime } from '@/shared/utils/serverTime';

/** 每页条数（与后端默认口径无关，前端显式传）。 */
const PAGE_SIZE = 20;

/**
 * Asia/Shanghai 的固定偏移（UTC+8）。
 *
 * 中国自 1991 年起不再使用夏令时，固定 8 小时即可；**不用 `Intl`** 的理由见文件头第 1 条。
 */
const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000;

/** 一天的毫秒数（算「昨天」用）。 */
const MS_PER_DAY = 24 * 60 * 60 * 1000;

/** 业务码：会话归属不符（工作流 G2 第 3 条，不泄漏存在性）。 */
const CODE_FORBIDDEN = 401008;

/** 日期分组。 */
type DayGroup = 'today' | 'yesterday' | 'earlier';

/** 分组 → 分组标题文案 key。 */
const GROUP_LABEL_KEYS: Readonly<Record<DayGroup, MessageKey>> = {
  today: 'ai.history.groupToday',
  yesterday: 'ai.history.groupYesterday',
  earlier: 'ai.history.groupEarlier',
};

/** 上海时区下的日历字段（全部由 `getUTC*` 读取，见文件头第 1 条）。 */
type ShanghaiFields = {
  /** 年。 */
  year: number;
  /** 月（1~12）。 */
  month: number;
  /** 日（1~31）。 */
  day: number;
  /** 时（0~23）。 */
  hour: number;
  /** 分（0~59）。 */
  minute: number;
};

/** 页面阶段（四态里的三种，`ready` 再按列表是否为空分正常 / 空态）。 */
type PagePhase = 'loading' | 'error' | 'ready';

/**
 * 补零到两位。
 *
 * @param value 非负整数
 * @returns 两位字符串
 */
function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

/**
 * 把时间戳换算成 Asia/Shanghai 的日历字段。
 *
 * 做法是「先整体后移 8 小时，再用 `getUTC*` 读」，而不是 `getFullYear()` 等本地方法——
 * 后者会跟随设备时区（新疆设备会是 UTC+6，分组就错了）。
 *
 * @param timeMs 时间戳（毫秒，UTC 语义）
 * @returns 上海时区下的年月日时分
 */
function shanghaiFieldsOf(timeMs: number): ShanghaiFields {
  const shifted = new Date(timeMs + SHANGHAI_OFFSET_MS);
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
  };
}

/**
 * 取「上海时区的一天」的排序键（`YYYY-MM-DD`）。
 *
 * 用可比较的字符串做同日判断：`dayKey >= 今天` 归今天（容忍服务端时钟略快），
 * `= 昨天` 归昨天，其余归更早。
 *
 * @param fields 上海时区日历字段
 * @returns 形如 `2026-11-06` 的键
 */
function dayKeyOf(fields: ShanghaiFields): string {
  return `${String(fields.year)}-${pad2(fields.month)}-${pad2(fields.day)}`;
}

/**
 * 判断一条会话落在哪个日期分组。
 *
 * @param timeMs       会话最后一条消息时间戳；解析失败传 null（归「更早」）
 * @param todayKey     今天（上海）的 dayKey
 * @param yesterdayKey 昨天（上海）的 dayKey
 * @returns 日期分组
 */
function groupOf(timeMs: number | null, todayKey: string, yesterdayKey: string): DayGroup {
  if (timeMs === null) {
    return 'earlier';
  }
  const key = dayKeyOf(shanghaiFieldsOf(timeMs));
  // 未来时间（服务端时钟偏快）也归「今天」，否则会突然掉进「更早」。
  if (key >= todayKey) {
    return 'today';
  }
  if (key === yesterdayKey) {
    return 'yesterday';
  }
  return 'earlier';
}

/**
 * 格式化条目右侧的时间。
 *
 * 今天 / 昨天显示 `HH:mm`（上海时区），更早显示「M月D日」（`ai.history.timeMonthDay`，
 * 月日是占位符，换了语言也能翻译）。解析失败显示占位符 `—`。
 *
 * @param timeMs 时间戳；null 表示解析失败
 * @param group  日期分组
 * @param t      翻译函数
 * @returns 展示用时间串
 */
function timeLabelOf(
  timeMs: number | null,
  group: DayGroup,
  t: (key: MessageKey, vars?: Record<string, string | number>) => string,
): string {
  if (timeMs === null) {
    return t('ai.history.dateUnknown');
  }
  const fields = shanghaiFieldsOf(timeMs);
  if (group === 'today' || group === 'yesterday') {
    return `${pad2(fields.hour)}:${pad2(fields.minute)}`;
  }
  return t('ai.history.timeMonthDay', { month: fields.month, day: fields.day });
}

/**
 * 对话历史页。
 *
 * 副作用：挂载时拉第一页；上拉追加；确认删除时调 DELETE；失败弹 Toast。
 *
 * @returns 历史列表界面（含加载 / 错误 / 空态）
 */
export function AiHistoryPage() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();

  const [items, setItems] = useState<AiConversationBrief[]>([]);
  const [phase, setPhase] = useState<PagePhase>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [pageNum, setPageNum] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  /** 正在等待二次确认的条目；null 表示确认框未打开。 */
  const [deleteTarget, setDeleteTarget] = useState<AiConversationBrief | null>(null);
  /** 删除请求进行中（确认框按钮置 busy，防连点）。 */
  const [deleting, setDeleting] = useState(false);

  /**
   * 拉第一页（重试与首屏共用）。
   *
   * @param silent 静默刷新（聚焦用）：不置 loading、失败保留已有列表
   */
  const loadFirstPage = useCallback(async (silent = false): Promise<void> => {
    if (!silent) {
      setPhase('loading');
    }
    const result = await listConversations(1, PAGE_SIZE);
    if (!result.ok) {
      if (silent) {
        return;
      }
      setError(result.error);
      setPhase('error');
      return;
    }
    setError(null);
    setItems(result.data.list);
    setPageNum(1);
    setHasMore(result.data.hasMore);
    setPhase('ready');
  }, []);

  useEffect(() => {
    void loadFirstPage();
  }, [loadFirstPage]);

  // 从 AI 会话返回时静默刷新历史；已翻页时跳过，避免打回第 1 页。
  useRefreshOnFocus(
    useCallback(() => {
      if (pageNum !== 1) {
        return;
      }
      void loadFirstPage(true);
    }, [loadFirstPage, pageNum]),
  );

  /** 上拉加载下一页（`hasMore=false` 时不再请求）。 */
  const loadNextPage = useCallback(async (): Promise<void> => {
    if (phase !== 'ready' || loadingMore || !hasMore) {
      return;
    }
    setLoadingMore(true);
    try {
      const next = pageNum + 1;
      const result = await listConversations(next, PAGE_SIZE);
      if (!result.ok) {
        // 追加失败只提示，不清空已加载的列表（用户还能看已有记录）。
        toast.show(t('ai.history.loadMoreFailed'));
        return;
      }
      setItems((current) => [...current, ...result.data.list]);
      setPageNum(next);
      setHasMore(result.data.hasMore);
    } finally {
      setLoadingMore(false);
    }
  }, [hasMore, loadingMore, pageNum, phase, t, toast]);

  /**
   * 确认删除：调 DELETE，成功从列表移除，失败保留条目并提示。
   *
   * `401008`（归属不符）按「无权操作」提示；其余失败一律「删除失败，请重试」。
   */
  const handleConfirmDelete = useCallback(async (): Promise<void> => {
    const target = deleteTarget;
    if (target === null || deleting) {
      return;
    }
    setDeleting(true);
    const result = await deleteConversation(target.id);
    setDeleting(false);
    setDeleteTarget(null);
    if (!result.ok) {
      const forbidden = result.error.kind === 'business' && result.error.code === CODE_FORBIDDEN;
      toast.show(t(forbidden ? 'ai.history.deleteForbidden' : 'ai.history.deleteFailed'));
      return;
    }
    setItems((current) => current.filter((row) => row.id !== target.id));
  }, [deleteTarget, deleting, t, toast]);

  // 今天 / 昨天用同一时刻算：跨零点时最多差一次渲染，不做定时器刷新（列表本来就要下拉才更新）。
  const now = Date.now();
  const todayKey = dayKeyOf(shanghaiFieldsOf(now));
  const yesterdayKey = dayKeyOf(shanghaiFieldsOf(now - MS_PER_DAY));

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('ai.history.title')} />

      {phase === 'loading' ? (
        <View style={styles.pad}>
          <LoadingState rows={5} />
        </View>
      ) : null}

      {phase === 'error' && error !== null ? (
        <View style={styles.pad}>
          <ErrorState
            error={error}
            retryTestID="ai-history-retry"
            onRetry={() => {
              void loadFirstPage();
            }}
          />
        </View>
      ) : null}

      {phase === 'ready' && items.length === 0 ? (
        <View testID="ai-history-empty" style={styles.emptyBox}>
          <EmptyState i18nKey="ai.history.emptyTitle" />
          <LocalizedText numberOfLines={2} style={styles.emptyDesc}>
            {t('ai.history.emptyDesc')}
          </LocalizedText>
        </View>
      ) : null}

      {phase === 'ready' && items.length > 0 ? (
        <FlatList
          data={items}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.list}
          onEndReached={() => {
            void loadNextPage();
          }}
          onEndReachedThreshold={0.3}
          ListFooterComponent={
            loadingMore ? (
              <View style={styles.footer}>
                <LoadingState rows={1} />
              </View>
            ) : null
          }
          renderItem={({ item, index }) => {
            const timeMs = parseServerTime(item.lastMessageAt);
            const group = groupOf(timeMs, todayKey, yesterdayKey);
            const previousItem = index > 0 ? items[index - 1] : undefined;
            const previousGroup =
              previousItem === undefined
                ? null
                : groupOf(parseServerTime(previousItem.lastMessageAt), todayKey, yesterdayKey);
            const timeLabel = timeLabelOf(timeMs, group, t);
            // title / summary 都允许为 null（后端列允许 NULL；真实 HTTP 实测 summary 会为 null）。
            const title =
              item.title === null || item.title.trim() === ''
                ? t('ai.history.untitled')
                : item.title;
            const summary =
              item.summary === null || item.summary.trim() === ''
                ? t('ai.history.noSummary')
                : item.summary;

            return (
              <View style={styles.itemWrap}>
                {group !== previousGroup ? (
                  <LocalizedText numberOfLines={1} style={styles.groupTitle}>
                    {t(GROUP_LABEL_KEYS[group])}
                  </LocalizedText>
                ) : null}

                <Swipeable
                  renderRightActions={() => (
                    <Pressable
                      testID={`ai-history-delete-${String(index)}`}
                      onPress={() => setDeleteTarget(item)}
                      accessibilityRole="button"
                      accessibilityLabel={t('ai.history.delete')}
                      style={styles.deleteAction}
                    >
                      <LocalizedText numberOfLines={1} style={styles.deleteActionText}>
                        {t('ai.history.delete')}
                      </LocalizedText>
                    </Pressable>
                  )}
                >
                  <Pressable
                    testID={`ai-history-item-${String(index)}`}
                    onPress={() => router.push(aiChatConversationHref(item.id))}
                    accessibilityRole="button"
                    accessibilityLabel={title}
                    style={styles.item}
                  >
                    <View style={styles.itemHead}>
                      <LocalizedText numberOfLines={1} style={styles.itemTitle}>
                        {title}
                      </LocalizedText>
                      <LtrText numberOfLines={1} style={styles.itemTime}>
                        {timeLabel}
                      </LtrText>
                    </View>
                    <LocalizedText numberOfLines={1} style={styles.itemSummary}>
                      {summary}
                    </LocalizedText>
                  </Pressable>
                </Swipeable>
              </View>
            );
          }}
        />
      ) : null}

      <ConfirmDialog
        visible={deleteTarget !== null}
        title={t('ai.history.deleteTitle')}
        description={t('ai.history.deleteBody')}
        confirmLabel={t('ai.history.deleteConfirm')}
        cancelLabel={t('ai.history.deleteCancel')}
        danger
        busy={deleting}
        onConfirm={() => {
          void handleConfirmDelete();
        }}
        onCancel={() => {
          if (!deleting) {
            setDeleteTarget(null);
          }
        }}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  pad: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
  },
  emptyBox: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.xxxl,
    alignItems: 'center',
  },
  emptyDesc: {
    ...typography.bodySmall,
    color: color.text.muted,
    textAlign: 'center',
  },
  list: {
    paddingBottom: spacing.xxxl,
  },
  itemWrap: {
    // 分组标题落在页面底色上（设计稿的分组标题不在白卡内），白底只给条目本身。
    backgroundColor: 'transparent',
  },
  groupTitle: {
    ...typography.caption,
    color: color.text.muted,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xs,
  },
  item: {
    paddingStart: spacing.xl,
    paddingEnd: spacing.lg,
    paddingVertical: spacing.lg,
    backgroundColor: color.background.surface,
    // 分割线用物理 bottom（RTL 下仍是底部，不镜像），ESLint 只拦 left/right 物理方向。
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  itemHead: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: spacing.sm,
  },
  itemTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
    flex: 1,
  },
  itemTime: {
    ...typography.micro,
    color: color.text.muted,
  },
  itemSummary: {
    marginTop: spacing.xxs,
    ...typography.bodySmall,
    color: color.text.muted,
  },
  deleteAction: {
    width: 88,
    backgroundColor: color.state.danger,
    alignItems: 'center',
    justifyContent: 'center',
  },
  deleteActionText: {
    ...typography.body,
    fontWeight: typography.h3.fontWeight,
    color: color.text.onBrand,
  },
  footer: {
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
  },
});
