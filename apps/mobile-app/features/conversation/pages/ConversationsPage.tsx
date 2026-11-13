/*
 * 职责：用户端「我的咨询」——列出当前用户与所有律师的一对一会话，按最近消息倒序，
 *       点一行直接进聊天页（按 conversationId 直达）。
 * 调用方：路由壳 `app/conversations.tsx`。入口两处：
 *         1) 消息中心页顶部「我的咨询」入口条（`features/content/pages/MessagesPage.tsx`）；
 *         2) 我的页菜单「我的咨询」（`features/profile/pages/MyPage.tsx`）。
 * 数据来源：`GET /api/v1/app/conversations?pageNum=&pageSize=`（页码分页）。
 * 视觉依据：照律师端同类页 `design/ui/63-consultations.html` 的列表范式改造——同族页面
 *           （头像 + 未读角标 + 姓名 + 相对时间 + 摘要），用户端**不加**「订单状态标签」
 *           （那是律师关心「客户付没付钱」，用户看自己的对话不需要）。
 * 注意：
 *   1. **摘要是 ASCII 标记不是文案**：服务端 `lastMessage` 对图片 / 文件 / 文书存
 *      `[image]` / `[file]` / `[doc]`（服务端 `MessageService.SUMMARY_*`，注释写明
 *      「中文文案由客户端 i18n 渲染」）。直接上屏会露英文方括号标记，必须翻。
 *   2. **按 conversationId 直达，不按 lawyerId**：`chatHref(lawyerId)` 走的是「创建或获取」，
 *      列表里会话已经存在，用 `chatByConversationHref` 才对——少一次建会话，而且退款后的
 *      会话也能进（服务端对已存在会话不校验购买）。
 *   3. 头像是 `peerAvatarUrl`，**可能为 null**（演示律师就没有头像）→ 用姓名首字兜底，
 *      与订单详情页的律师头像口径一致。
 *   4. **页码分页不是游标**（`pageNum` / `pageSize` / `hasMore`）：必须做「加载更多」，
 *      否则第 21 条以后的会话永远看不到。加载更多失败只 toast，不清空已加载的数据。
 *   5. 四态齐全：加载（骨架）/ 空（还没有咨询记录 + 去找律师）/ 错误（重试）/ 正常。
 *   6. 从聊天页返回时**静默刷新**（`useRefreshOnFocus`）：未读数和排序可能变了，
 *      但重新闪一次骨架会让用户以为页面重载了。
 *
 * testID 清单：conversations-screen / conversations-list / conversations-row-{id} /
 *              conversations-unread-{id} / conversations-empty / conversations-error /
 *              conversations-retry / conversations-loading / conversations-load-more
 */

import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { FlatList, Image, Pressable, RefreshControl, StyleSheet, View } from 'react-native';

import { getConversations } from '@/features/conversation/api/conversations';
import type { ConversationItem } from '@/features/conversation/api/conversations';
import type { AppError } from '@/shared/api/types';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useToast } from '@/shared/components/AppToast';
import { useErrorMessage } from '@/shared/hooks/useErrorMessage';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { useI18n } from '@/shared/i18n';
import { chatByConversationHref, PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { formatRelativeMessageTime } from '@/shared/utils/relativeTime';

/** 每页条数（与后端 `pageSize` 默认口径一致）。 */
const PAGE_SIZE = 20;

/** 列表状态。 */
type ListState =
  | { phase: 'loading' }
  | { phase: 'error'; error: AppError }
  | { phase: 'ready'; items: ConversationItem[]; hasMore: boolean };

/**
 * 把服务端摘要里的 ASCII 标记翻成当前语言。
 *
 * 副作用：无。
 *
 * @param raw 服务端 `lastMessage`（可能是 `[image]` / `[file]` / `[doc]` / 普通文本 / null）
 * @param imageText 图片占位文案（`conversation.imageMessage`，自带方括号）
 * @param fileText 文件占位文案（`conversation.list.summaryFile`，自带方括号）
 * @param docText 文书占位文案（`conversation.list.summaryDoc`，自带方括号）
 * @returns 可直接上屏的摘要；空摘要返回 null（调用方用「还没有消息」兜底）
 */
function translateSummary(
  raw: string | null,
  imageText: string,
  fileText: string,
  docText: string,
): string | null {
  if (raw === null || raw.trim() === '') {
    return null;
  }
  if (raw === '[image]') {
    return imageText;
  }
  if (raw === '[file]') {
    return fileText;
  }
  if (raw === '[doc]') {
    return docText;
  }
  return raw;
}

/**
 * 用户端会话列表页。
 *
 * 副作用：挂载时拉第一页；下拉刷新、从聊天页返回时重新拉第一页；点「加载更多」追加下一页。
 *
 * @returns 我的咨询列表界面
 */
export function ConversationsPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();
  const errorText = useErrorMessage();
  const [state, setState] = useState<ListState>({ phase: 'loading' });
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  /** 已加载到第几页（加载更多时 +1）。 */
  const [loadedPage, setLoadedPage] = useState(1);

  /**
   * 拉第一页（首屏、错误重试、下拉刷新、聚焦刷新共用）。
   *
   * @param silent 静默刷新（聚焦用）：不置 loading、失败保留已有列表
   */
  const loadFirst = useCallback(async (silent = false): Promise<void> => {
    if (!silent) {
      setState({ phase: 'loading' });
    }
    const result = await getConversations(1, PAGE_SIZE);
    if (!result.ok) {
      if (silent) {
        setRefreshing(false);
        return;
      }
      setState({ phase: 'error', error: result.error });
      setRefreshing(false);
      return;
    }
    setLoadedPage(1);
    setState({ phase: 'ready', items: result.data.list, hasMore: result.data.hasMore });
    setRefreshing(false);
  }, []);

  useEffect(() => {
    void loadFirst();
  }, [loadFirst]);

  // 从聊天页返回：未读与排序可能变了，静默刷新（不闪骨架）。
  useRefreshOnFocus(
    useCallback(() => {
      void loadFirst(true);
    }, [loadFirst]),
  );

  /**
   * 加载下一页并追加。
   *
   * 副作用：失败只 toast，**不清空已加载的数据**（把用户已看到的列表清掉比不加载更糟）。
   */
  const loadMore = async (): Promise<void> => {
    if (state.phase !== 'ready' || loadingMore || !state.hasMore) {
      return;
    }
    setLoadingMore(true);
    const nextPage = loadedPage + 1;
    const result = await getConversations(nextPage, PAGE_SIZE);
    setLoadingMore(false);
    if (!result.ok) {
      toast.show(errorText(result.error));
      return;
    }
    setLoadedPage(nextPage);
    setState((current) => {
      if (current.phase !== 'ready') {
        return current;
      }
      // 按 id 去重：翻页期间有人发了新消息会把它往前挤，不去重会出现两行同一条。
      const seen = new Set(current.items.map((item) => item.id));
      const merged = [...current.items, ...result.data.list.filter((item) => !seen.has(item.id))];
      return { phase: 'ready', items: merged, hasMore: result.data.hasMore };
    });
  };

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('conversation.list.title')} />
      <View style={styles.root} testID="conversations-screen">
        <LocalizedText numberOfLines={1} style={styles.sortHint}>
          {t('conversation.list.sortHint')}
        </LocalizedText>

        {state.phase === 'loading' ? (
          <View style={styles.stateWrap} testID="conversations-loading">
            <LoadingState rows={5} />
          </View>
        ) : null}

        {state.phase === 'error' ? (
          <View style={styles.stateWrap} testID="conversations-error">
            <ErrorState
              error={state.error}
              onRetry={() => void loadFirst()}
              retryTestID="conversations-retry"
            />
          </View>
        ) : null}

        {state.phase === 'ready' && state.items.length === 0 ? (
          <View style={styles.stateWrap} testID="conversations-empty">
            <EmptyState i18nKey="conversation.list.emptyTitle" />
            <LocalizedText numberOfLines={3} style={styles.emptyHint}>
              {t('conversation.list.emptyDesc')}
            </LocalizedText>
            <View style={styles.emptyAction}>
              <PrimaryButton
                label={t('conversation.list.emptyAction')}
                variant="outline"
                onPress={() => router.push(PAGE_ROUTES.lawyerTab)}
                testID="conversations-empty-action"
              />
            </View>
          </View>
        ) : null}

        {state.phase === 'ready' && state.items.length > 0 ? (
          <FlatList
            testID="conversations-list"
            data={state.items}
            keyExtractor={(item) => item.id}
            contentContainerStyle={styles.list}
            refreshControl={
              <RefreshControl
                refreshing={refreshing}
                onRefresh={() => {
                  setRefreshing(true);
                  void loadFirst(true);
                }}
              />
            }
            ListFooterComponent={
              state.hasMore ? (
                <View style={styles.footer}>
                  <PrimaryButton
                    label={
                      loadingMore ? t('conversation.list.loadingMore') : t('conversation.list.loadMore')
                    }
                    variant="outline"
                    disabled={loadingMore}
                    onPress={() => {
                      void loadMore();
                    }}
                    testID="conversations-load-more"
                  />
                </View>
              ) : (
                <LocalizedText numberOfLines={1} style={styles.noMore}>
                  {t('conversation.list.noMore')}
                </LocalizedText>
              )
            }
            renderItem={({ item }) => {
              const unread = item.unread > 0;
              const time = formatRelativeMessageTime(item.lastMsgAt);
              const summary = translateSummary(
                item.lastMessage,
                t('conversation.imageMessage'),
                t('conversation.list.summaryFile'),
                t('conversation.list.summaryDoc'),
              );
              const name = item.peerName ?? t('conversation.list.unnamed');
              return (
                <Pressable
                  testID={`conversations-row-${item.id}`}
                  accessibilityRole="button"
                  accessibilityLabel={name}
                  onPress={() => router.push(chatByConversationHref(item.id))}
                  style={({ pressed }) => [styles.row, pressed ? styles.pressed : null]}
                >
                  <View style={styles.avatar}>
                    {item.peerAvatarUrl === null ? (
                      <LocalizedText numberOfLines={1} style={styles.avatarText}>
                        {name.slice(0, 1)}
                      </LocalizedText>
                    ) : (
                      <Image source={{ uri: item.peerAvatarUrl }} style={styles.avatarImage} />
                    )}
                    {unread ? (
                      <View style={styles.badge} testID={`conversations-unread-${item.id}`}>
                        <LocalizedText numberOfLines={1} style={styles.badgeText}>
                          {String(item.unread > 99 ? 99 : item.unread)}
                        </LocalizedText>
                      </View>
                    ) : null}
                  </View>
                  <View style={styles.rowBody}>
                    <View style={styles.rowHead}>
                      <LocalizedText numberOfLines={1} style={styles.name}>
                        {name}
                      </LocalizedText>
                      <LocalizedText numberOfLines={1} style={styles.time}>
                        {time === 'yesterday' ? t('conversation.list.yesterday') : time}
                      </LocalizedText>
                    </View>
                    <LocalizedText
                      numberOfLines={1}
                      style={[styles.summary, unread ? styles.summaryUnread : null]}
                    >
                      {summary ?? t('conversation.list.noMessage')}
                    </LocalizedText>
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
  emptyAction: { marginTop: spacing.xl },
  list: { paddingHorizontal: spacing.xl, paddingBottom: spacing.xxxl, gap: spacing.sm },
  footer: { marginTop: spacing.md },
  noMore: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
    paddingVertical: spacing.lg,
  },
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
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarImage: {
    width: size.avatarSmall,
    height: size.avatarSmall,
    borderRadius: radius.pill,
  },
  avatarText: {
    ...typography.bodyLarge,
    color: color.brand.primary,
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
  rowHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  name: { ...typography.bodyLarge, color: color.text.primary, flexShrink: 1 },
  time: { ...typography.micro, color: color.text.muted },
  summary: { ...typography.caption, color: color.text.muted },
  summaryUnread: { color: color.text.primary, fontWeight: '700' },
});
