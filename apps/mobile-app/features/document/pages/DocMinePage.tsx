/**
 * 我的文书列表页（段 4 E5，设计稿 `design/ui/27-doc-mine.html`）。
 *
 * 职责：状态筛选（全部 / 草稿 / 已完成 / 失败）+ 类型筛选 + 下拉刷新 + 上拉加载 +
 *       缩略图列表；失败项红标且可就地展开失败原因；空态给「去生成」入口。
 * 调用方：路由壳 `app/doc/mine.tsx`；入口在「我的」页「我的文书」（列表页右上可回模板页）。
 * 数据来源：
 *   - `GET /app/documents?status=&docType=&pageNum=&pageSize=`（C 端 `list` 分页）；
 *   - `GET /app/doc-templates`（只取分类，用于类型筛选；失败不阻塞列表）；
 *   - `GET /app/documents/{id}`（失败项展开原因时取 `errorMsg`）。
 * 注意：
 *   1. **筛选变化必须重置到第 1 页**（工作流 E5 的常见失败项）：本页把筛选放进
 *      `load` 的依赖，筛选一变就重新拉第 1 页，并用请求序号丢弃过期响应，避免
 *      「快速切筛选时旧结果覆盖新结果」。
 *   2. 「已完成」不是单一状态：后端 `status` 支持逗号分隔多值（lead 2026-11-07 修），
 *      传 `generated,signed`——已盖章的文书也属于已完成，不能漏。
 *   3. 分页页码从 1 开始（后端 `pageNum` 语义），是否还有下一页只看服务端 `hasMore`，
 *      不拿 `total` 自己推（避免第 2 页重复/丢数据）。
 *   4. 下拉刷新用 `RefreshControl`；Web 上没有下拉手势，顶栏另给一个刷新按钮覆盖验收。
 *   5. 四态：加载（骨架）/ 空（无文书 + 去生成）/ 错误（可重试）/ 正常。
 *
 * testID 清单（全部真实挂载）：
 *   mine-screen / mine-loading / mine-error / mine-retry / mine-empty / mine-empty-action /
 *   mine-refresh / mine-new / mine-status-{all|draft|done|failed} / mine-type-all /
 *   mine-type-{index} / mine-list / mine-item-{id} / mine-thumb-{id} /
 *   mine-status-badge-{id} / mine-reason-{id} / mine-reason-text-{id} /
 *   mine-load-more / mine-no-more
 */

import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import type { Href } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { Pressable } from 'react-native';
import type { NativeScrollEvent, NativeSyntheticEvent } from 'react-native';

import {
  fetchDocument,
  fetchMyDocuments,
  fetchTemplates,
} from '@/features/document/api/client';
import type { DocumentRow } from '@/features/document/api/client';
import type { AppError } from '@/shared/api/types';
import { AppHeader, HeaderIconButton } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 每页条数（后端上限 50）。 */
const PAGE_SIZE = 20;

/** 距底部多少像素时自动加载下一页。 */
const LOAD_MORE_THRESHOLD = 120;

/** 模板列表页路由。 */
const TEMPLATES_ROUTE = '/doc/templates';

/** 预览页路由（路由常量待 `shared/routes.ts` 统一登记）。 */
const PREVIEW_ROUTE = '/doc/preview';

/** 状态筛选档位。 */
type StatusFilter = 'all' | 'draft' | 'done' | 'failed';

/** 档位 → 后端 `status` 参数。「已完成」= 生成成功 + 已盖章（后端支持逗号多值）。 */
const STATUS_VALUE: Record<StatusFilter, string | undefined> = {
  all: undefined,
  draft: 'draft',
  done: 'generated,signed',
  failed: 'failed',
};

/** 档位文案 key。 */
const STATUS_KEYS: Record<StatusFilter, MessageKey> = {
  all: 'document.mine.filterAll',
  draft: 'document.mine.filterDraft',
  done: 'document.mine.filterDone',
  failed: 'document.mine.filterFailed',
};

/** 档位顺序（与设计稿一致）。 */
const STATUS_ORDER: StatusFilter[] = ['all', 'draft', 'done', 'failed'];

/** 加载模式：替换首屏 / 刷新（保留列表）/ 追加下一页。 */
type LoadMode = 'replace' | 'refresh' | 'append';

/**
 * 状态码 → 文案 key。
 *
 * @param status 后端 `document.status`
 * @returns 文案 key；未知状态返回 null（按原值显示）
 */
function statusKeyOf(status: string): MessageKey | null {
  if (status === 'draft') {
    return 'document.mine.statusDraft';
  }
  if (status === 'generated') {
    return 'document.mine.statusGenerated';
  }
  if (status === 'signed') {
    return 'document.mine.statusSigned';
  }
  if (status === 'failed') {
    return 'document.mine.statusFailed';
  }
  return null;
}

/**
 * 拼预览页路由。
 *
 * @param documentId 文书主键
 * @returns 可传给 `router.push` 的地址
 */
function previewHref(documentId: string): Href {
  return `${PREVIEW_ROUTE}?id=${encodeURIComponent(documentId)}` as Href;
}

/**
 * 列表项状态标（失败用红色，其余区分草稿 / 已完成 / 已盖章）。
 *
 * @param props.status  后端状态码
 * @param props.testID  自动化标识
 * @returns 状态胶囊
 */
function StatusBadge({ status, testID }: { status: string; testID: string }) {
  const { t } = useI18n();
  const key = statusKeyOf(status);
  const label = key === null ? status : t(key);
  const danger = status === 'failed';
  return (
    <View
      testID={testID}
      style={[styles.badge, danger ? styles.badgeDanger : styles.badgeNormal]}
    >
      <LocalizedText
        numberOfLines={1}
        style={[styles.badgeText, danger ? styles.badgeTextDanger : styles.badgeTextNormal]}
      >
        {label}
      </LocalizedText>
    </View>
  );
}

/**
 * 我的文书列表页。
 *
 * 副作用：挂载与筛选变化时拉第 1 页；下拉刷新替换列表；触底 / 按钮加载下一页；
 *        失败项展开时请求详情取失败原因。
 *
 * @returns 筛选条 + 文书列表
 */
export function DocMinePage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();

  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [typeFilter, setTypeFilter] = useState<string | null>(null);
  const [typeOptions, setTypeOptions] = useState<string[]>([]);
  const [items, setItems] = useState<DocumentRow[]>([]);
  const [phase, setPhase] = useState<'loading' | 'error' | 'success' | 'empty'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const [loadingMore, setLoadingMore] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [reasonId, setReasonId] = useState<string | null>(null);
  const [reasonText, setReasonText] = useState<string | null>(null);
  const [reasonLoading, setReasonLoading] = useState(false);

  const itemsRef = useRef<DocumentRow[]>([]);
  const requestSeqRef = useRef(0);

  // 类型筛选的选项来自模板分类：失败时降级为「只有全部类型」，不影响列表。
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const result = await fetchTemplates();
      if (cancelled || !result.ok) {
        return;
      }
      const seen = new Set<string>();
      const options: string[] = [];
      for (const template of result.data) {
        const category = template.category;
        if (category === null || category.trim() === '' || seen.has(category)) {
          continue;
        }
        seen.add(category);
        options.push(category);
      }
      setTypeOptions(options);
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * 拉一页文书。
   *
   * @param targetPage 页码（从 1 开始）
   * @param mode       替换首屏 / 刷新 / 追加
   */
  const load = useCallback(
    async (targetPage: number, mode: LoadMode) => {
      const seq = requestSeqRef.current + 1;
      requestSeqRef.current = seq;
      if (mode === 'replace') {
        setPhase('loading');
      }
      setError(null);
      const result = await fetchMyDocuments({
        status: STATUS_VALUE[statusFilter],
        docType: typeFilter ?? undefined,
        pageNum: targetPage,
        pageSize: PAGE_SIZE,
      });
      // 过期响应直接丢弃：快速切筛选时旧请求可能后到。
      if (requestSeqRef.current !== seq) {
        return;
      }
      if (!result.ok) {
        if (mode === 'append') {
          toast.show(t('document.mine.refreshFailed'));
          return;
        }
        setError(result.error);
        setPhase('error');
        return;
      }
      const next = mode === 'append' ? [...itemsRef.current, ...result.data.list] : result.data.list;
      itemsRef.current = next;
      setItems(next);
      setHasMore(result.data.hasMore);
      setPageNum(result.data.pageNum);
      setPhase(next.length === 0 ? 'empty' : 'success');
    },
    [statusFilter, typeFilter, toast, t],
  );

  // 筛选变化 → 重置到第 1 页（工作流 E5 的硬要求）。
  useEffect(() => {
    void load(1, 'replace');
  }, [load]);

  /**
   * 下拉刷新：保留列表，重新拉第 1 页。
   */
  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      await load(1, 'refresh');
    } finally {
      setRefreshing(false);
    }
  };

  /**
   * 加载下一页。
   */
  const handleLoadMore = async () => {
    if (loadingMore || !hasMore || phase !== 'success') {
      return;
    }
    setLoadingMore(true);
    try {
      await load(pageNum + 1, 'append');
    } finally {
      setLoadingMore(false);
    }
  };

  /**
   * 滚动接近底部时自动加载（上拉加载）。
   *
   * @param event 滚动事件
   */
  const handleScroll = (event: NativeSyntheticEvent<NativeScrollEvent>) => {
    const { layoutMeasurement, contentOffset, contentSize } = event.nativeEvent;
    const distanceToBottom =
      contentSize.height - (contentOffset.y + layoutMeasurement.height);
    if (distanceToBottom < LOAD_MORE_THRESHOLD) {
      void handleLoadMore();
    }
  };

  /**
   * 点列表项：已生成进预览页；失败项就地展开 / 收起原因。
   *
   * @param item 列表行
   */
  const handlePressItem = async (item: DocumentRow) => {
    if (item.status !== 'failed') {
      router.push(previewHref(item.id));
      return;
    }
    if (reasonId === item.id) {
      setReasonId(null);
      setReasonText(null);
      return;
    }
    setReasonId(item.id);
    setReasonText(null);
    setReasonLoading(true);
    try {
      const result = await fetchDocument(item.id);
      if (!result.ok) {
        setReasonText(t('document.mine.failedReasonFallback'));
        return;
      }
      setReasonText(result.data.errorMsg ?? t('document.mine.failedReasonFallback'));
    } finally {
      setReasonLoading(false);
    }
  };

  /** 类型筛选项（首项是「全部类型」）。 */
  const typeChips = useMemo(
    () => (typeOptions.length === 0 ? [] : typeOptions),
    [typeOptions],
  );

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <View style={styles.root} testID="mine-screen">
        <AppHeader
          title={t('document.mine.title')}
          trailing={
            <View style={styles.headerActions}>
              <HeaderIconButton
                name="refresh"
                onPress={() => {
                  void handleRefresh();
                }}
                size={20}
              />
              <HeaderIconButton
                name="add"
                onPress={() => router.push(TEMPLATES_ROUTE as Href)}
                size={22}
              />
            </View>
          }
        />

        <ScrollView
          contentContainerStyle={styles.scroll}
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={() => {
                void handleRefresh();
              }}
              tintColor={color.brand.primary}
            />
          }
          onScroll={handleScroll}
          scrollEventThrottle={200}
          testID="mine-list"
        >
          <View style={styles.filterBlock}>
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.filterRow}
            >
              {STATUS_ORDER.map((key) => {
                const active = statusFilter === key;
                return (
                  <Pressable
                    key={key}
                    testID={`mine-status-${key}`}
                    onPress={() => setStatusFilter(key)}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    accessibilityLabel={t(STATUS_KEYS[key])}
                    style={[styles.chip, active ? styles.chipActive : null]}
                  >
                    <LocalizedText
                      numberOfLines={1}
                      style={[styles.chipText, active ? styles.chipTextActive : null]}
                    >
                      {t(STATUS_KEYS[key])}
                    </LocalizedText>
                  </Pressable>
                );
              })}
            </ScrollView>

            {typeChips.length === 0 ? null : (
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.filterRow}
              >
                <Pressable
                  testID="mine-type-all"
                  onPress={() => setTypeFilter(null)}
                  accessibilityRole="button"
                  accessibilityState={{ selected: typeFilter === null }}
                  accessibilityLabel={t('document.mine.typeAll')}
                  style={[styles.typeChip, typeFilter === null ? styles.typeChipActive : null]}
                >
                  <LocalizedText
                    numberOfLines={1}
                    style={[
                      styles.typeChipText,
                      typeFilter === null ? styles.typeChipTextActive : null,
                    ]}
                  >
                    {t('document.mine.typeAll')}
                  </LocalizedText>
                </Pressable>
                {typeChips.map((category, index) => {
                  const active = typeFilter === category;
                  return (
                    <Pressable
                      key={category}
                      testID={`mine-type-${index}`}
                      onPress={() => setTypeFilter(category)}
                      accessibilityRole="button"
                      accessibilityState={{ selected: active }}
                      accessibilityLabel={category}
                      style={[styles.typeChip, active ? styles.typeChipActive : null]}
                    >
                      <LocalizedText
                        numberOfLines={1}
                        style={[
                          styles.typeChipText,
                          active ? styles.typeChipTextActive : null,
                        ]}
                      >
                        {category}
                      </LocalizedText>
                    </Pressable>
                  );
                })}
              </ScrollView>
            )}
          </View>

          {phase === 'loading' ? (
            <View testID="mine-loading">
              <LoadingState rows={5} />
            </View>
          ) : null}

          {phase === 'error' && error !== null ? (
            <View testID="mine-error">
              <ErrorState
                error={error}
                onRetry={() => {
                  void load(1, 'replace');
                }}
                retryTestID="mine-retry"
              />
            </View>
          ) : null}

          {phase === 'empty' ? (
            <View style={styles.emptyWrap} testID="mine-empty">
              <EmptyState
                i18nKey={
                  statusFilter !== 'all' || typeFilter !== null
                    ? 'document.mine.filterEmpty'
                    : 'document.mine.empty'
                }
              />
              <LocalizedText numberOfLines={2} style={styles.emptyDesc}>
                {statusFilter === 'all' && typeFilter === null
                  ? t('document.mine.emptyDesc')
                  : t('document.mine.filterEmptyDesc')}
              </LocalizedText>
              <PrimaryButton
                label={t('document.mine.emptyAction')}
                variant="outline"
                onPress={() => router.push(TEMPLATES_ROUTE as Href)}
                style={styles.emptyAction}
                testID="mine-empty-action"
              />
            </View>
          ) : null}

          {phase === 'success' ? (
            <View style={styles.list}>
              {items.map((item) => {
                const reasonOpen = reasonId === item.id;
                return (
                  <View key={item.id} style={styles.itemWrap}>
                    <Pressable
                      testID={`mine-item-${item.id}`}
                      onPress={() => {
                        void handlePressItem(item);
                      }}
                      accessibilityRole="button"
                      accessibilityLabel={item.title}
                      style={({ pressed }) => [
                        styles.item,
                        pressed ? styles.pressed : null,
                      ]}
                    >
                      {item.thumbUrl === null ? (
                        <View style={styles.thumbPlaceholder} testID={`mine-thumb-${item.id}`}>
                          <Ionicons
                            name="document-text-outline"
                            size={20}
                            color={color.text.muted}
                          />
                        </View>
                      ) : (
                        <Image
                          testID={`mine-thumb-${item.id}`}
                          source={{ uri: item.thumbUrl }}
                          style={styles.thumb}
                          contentFit="cover"
                        />
                      )}
                      <View style={styles.itemMain}>
                        <LocalizedText numberOfLines={2} style={styles.itemTitle}>
                          {item.title}
                        </LocalizedText>
                        {/* 时间串含数字：RTL 下必须 LTR 隔离，否则会被 BiDi 重排（i18n 规范 §7.3） */}
                        <LtrText numberOfLines={1} style={styles.itemMeta}>
                          {formatServerDateTime(item.createdAt)}
                        </LtrText>
                      </View>
                      <StatusBadge status={item.status} testID={`mine-status-badge-${item.id}`} />
                    </Pressable>

                    {item.status === 'failed' ? (
                      <Pressable
                        testID={`mine-reason-${item.id}`}
                        onPress={() => {
                          void handlePressItem(item);
                        }}
                        accessibilityRole="button"
                        accessibilityLabel={
                          reasonOpen ? t('document.mine.hideReason') : t('document.mine.viewReason')
                        }
                        style={styles.reasonToggle}
                      >
                        <LocalizedText numberOfLines={1} style={styles.reasonToggleText}>
                          {reasonOpen
                            ? t('document.mine.hideReason')
                            : t('document.mine.viewReason')}
                        </LocalizedText>
                      </Pressable>
                    ) : null}

                    {reasonOpen ? (
                      <View style={styles.reasonBox} testID={`mine-reason-box-${item.id}`}>
                        <LocalizedText numberOfLines={1} style={styles.reasonTitle}>
                          {t('document.mine.failedReasonTitle')}
                        </LocalizedText>
                        <LocalizedText
                          numberOfLines={4}
                          style={styles.reasonText}
                          testID={`mine-reason-text-${item.id}`}
                        >
                          {reasonLoading
                            ? t('common.state.loading')
                            : (reasonText ?? t('document.mine.failedReasonFallback'))}
                        </LocalizedText>
                      </View>
                    ) : null}
                  </View>
                );
              })}

              <View style={styles.loadMore}>
                {hasMore ? (
                  <PrimaryButton
                    label={
                      loadingMore ? t('common.state.loading') : t('document.mine.loadMore')
                    }
                    variant="outline"
                    onPress={() => {
                      void handleLoadMore();
                    }}
                    disabled={loadingMore}
                    testID="mine-load-more"
                  />
                ) : (
                  <LocalizedText numberOfLines={1} style={styles.noMore} testID="mine-no-more">
                    {t('document.mine.noMore')}
                  </LocalizedText>
                )}
              </View>
            </View>
          ) : null}
        </ScrollView>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  filterBlock: {
    gap: spacing.xs,
    marginBottom: spacing.md,
  },
  filterRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    paddingVertical: spacing.xs,
  },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  chipActive: {
    backgroundColor: color.brand.primary,
    borderColor: color.brand.primary,
  },
  chipText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  chipTextActive: {
    color: color.text.onBrand,
  },
  typeChip: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  typeChipActive: {
    backgroundColor: color.brand.primarySoft,
  },
  typeChipText: {
    ...typography.micro,
    color: color.text.secondary,
  },
  typeChipTextActive: {
    color: color.brand.primary,
  },
  list: {
    gap: spacing.md,
  },
  itemWrap: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    overflow: 'hidden',
  },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    padding: spacing.md,
  },
  thumb: {
    width: size.avatarSmall,
    height: size.avatarSmall,
    borderRadius: radius.xs,
    backgroundColor: color.background.input,
  },
  thumbPlaceholder: {
    width: size.avatarSmall,
    height: size.avatarSmall,
    borderRadius: radius.xs,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
  },
  itemMain: {
    flex: 1,
    gap: spacing.xxs,
  },
  itemTitle: {
    ...typography.body,
    color: color.text.primary,
  },
  itemMeta: {
    ...typography.micro,
    color: color.text.muted,
  },
  badge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.xs,
  },
  badgeNormal: {
    backgroundColor: color.brand.primarySoft,
  },
  badgeDanger: {
    backgroundColor: color.state.danger,
  },
  badgeText: {
    ...typography.micro,
  },
  badgeTextNormal: {
    color: color.brand.primary,
  },
  badgeTextDanger: {
    color: color.text.onBrand,
  },
  reasonToggle: {
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.sm,
    alignItems: 'flex-start',
  },
  reasonToggleText: {
    ...typography.caption,
    color: color.brand.primary,
  },
  reasonBox: {
    marginHorizontal: spacing.md,
    marginBottom: spacing.md,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.canvas,
    gap: spacing.xxs,
  },
  reasonTitle: {
    ...typography.caption,
    color: color.text.secondary,
  },
  reasonText: {
    ...typography.bodySmall,
    color: color.text.body,
  },
  loadMore: {
    marginTop: spacing.md,
  },
  noMore: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
  },
  emptyWrap: {
    alignItems: 'center',
    paddingTop: spacing.xxl,
  },
  emptyDesc: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    paddingHorizontal: spacing.xl,
  },
  emptyAction: {
    marginTop: spacing.lg,
    paddingHorizontal: spacing.xl,
    minWidth: 160,
  },
  pressed: {
    opacity: 0.8,
  },
});
