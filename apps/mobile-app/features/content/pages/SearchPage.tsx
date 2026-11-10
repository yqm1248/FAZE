/**
 * 全局搜索（设计稿 `design/ui/40-search.html`，段 5 G3）。
 *
 * 职责：跨律师 / 文章 / 案例 / 文书模板四域检索，分域展示、各自可跳转。
 * 调用方：路由壳 `app/search.tsx`（案例列表顶部的搜索框、首页搜索框）。
 * 数据来源：`GET /app/search?kw=&type=`（四域一次返回）。
 * 注意：
 *   1. **结果必须分域展示**（四个分组，每组带命中数与「查看全部」），
 *      不许拼成一个大列表——5-A8 的失败判据就是这个。
 *   2. 三个隐藏态：`idle`（未输入：历史 + 热门）、`empty`（无结果：提示 + 推荐）、
 *      `error`（设计稿未画，本段补：提示 + 重试）。
 *   3. 搜索历史**存本地**（`prefStore`，最多 10 条），不上服务端；
 *      「大家都在搜」V1 用前端固定词表（没有热门统计接口）。
 *   4. 「文书」域搜的是**模板**，条目不显示价格（V1 不作文书付费）。
 *   5. 搜索有 400ms 防抖：每敲一个字就请求会把法条检索的限流额度打满
 *      （`/pub/law-items` 在详情页也用，是同一个 IP 限流）。
 *   6. 空态文案带占位符（`content.search.emptyTitle` 的 `{{kw}}`），**必须传 `vars`**，
 *      否则 `{{kw}}` 原样上屏。
 *
 * testID 清单：search-screen / search-input / search-cancel / search-tab-{all|lawyer|article|case|document} /
 *              search-idle / search-history / search-history-clear / search-hot /
 *              search-hint-{word} / search-group-{lawyer|article|case|document} /
 *              search-viewall-{domain} / search-row-{domain}-{id} / search-empty /
 *              search-error / search-retry
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { useRouter } from 'expo-router';
import type { Href } from 'expo-router';

import { searchAll } from '@/features/content/api/search';
import type {
  SearchGroup,
  SearchResult,
  SearchType,
} from '@/features/content/api/search';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { readStringListPref, writeStringListPref } from '@/shared/utils/prefStore';
import type { AppError } from '@/shared/api/types';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 本地搜索历史的存储键。 */
const HISTORY_KEY = 'faze.search.history';

/** 历史最多保留条数。 */
const HISTORY_MAX = 10;

/** 输入防抖毫秒数。 */
const DEBOUNCE_MS = 400;

/** 类型 Tab（综合 + 四单域）。 */
const TABS: { type: SearchType | null; key: MessageKey; testID: string }[] = [
  { type: null, key: 'content.search.tabAll', testID: 'search-tab-all' },
  { type: 'lawyer', key: 'content.search.tabLawyer', testID: 'search-tab-lawyer' },
  { type: 'article', key: 'content.search.tabArticle', testID: 'search-tab-article' },
  { type: 'case', key: 'content.search.tabCase', testID: 'search-tab-case' },
  { type: 'document', key: 'content.search.tabDocument', testID: 'search-tab-document' },
];

/** 「大家都在搜」固定词表（V1 没有热门统计接口）。 */
const HOT_WORDS = ['民间借贷', '劳动仲裁', '离婚财产', '交通事故', '合同违约'];

/**
 * 渲染搜索页。
 *
 * 副作用：输入变化时防抖请求；提交时写本地历史；点条目跳转对应详情页。
 *
 * @returns 全局搜索页
 */
export function SearchPage() {
  const { t } = useI18n();
  const router = useRouter();
  const [keyword, setKeyword] = useState('');
  const [type, setType] = useState<SearchType | null>(null);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [phase, setPhase] = useState<'idle' | 'loading' | 'success' | 'empty' | 'error'>('idle');
  const [error, setError] = useState<AppError | null>(null);
  const [history, setHistory] = useState<string[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastQueryRef = useRef('');

  useEffect(() => {
    setHistory(readStringListPref(HISTORY_KEY));
  }, []);

  /**
   * 执行搜索。
   *
   * @param kw 关键词
   * @param kind 单域类型（null = 综合）
   */
  const runSearch = useCallback(async (kw: string, kind: SearchType | null): Promise<void> => {
    const trimmed = kw.trim();
    if (trimmed === '') {
      setPhase('idle');
      setResult(null);
      return;
    }
    setPhase('loading');
    const res = await searchAll(trimmed, kind);
    if (!res.ok) {
      setError(res.error);
      setPhase('error');
      return;
    }
    const data = res.data;
    const totalHits = (data.lawyers?.total ?? 0) + (data.articles?.total ?? 0)
      + (data.cases?.total ?? 0) + (data.documents?.total ?? 0);
    setError(null);
    setResult(data);
    setPhase(totalHits === 0 ? 'empty' : 'success');
  }, []);

  /**
   * 记录搜索历史（去重 + 置顶 + 截断）。
   *
   * @param kw 关键词
   */
  const pushHistory = useCallback((kw: string): void => {
    const trimmed = kw.trim();
    if (trimmed === '') {
      return;
    }
    setHistory((prev) => {
      const next = [trimmed, ...prev.filter((item) => item !== trimmed)].slice(0, HISTORY_MAX);
      writeStringListPref(HISTORY_KEY, next);
      return next;
    });
  }, []);

  /**
   * 输入变化：防抖后搜索（不写历史，历史只在提交时写）。
   *
   * @param text 输入文本
   */
  const handleChange = (text: string): void => {
    setKeyword(text);
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
    }
    timerRef.current = setTimeout(() => {
      lastQueryRef.current = text;
      void runSearch(text, type);
    }, DEBOUNCE_MS);
  };

  /**
   * 提交搜索（回车或点历史/热词）。
   *
   * @param kw 关键词
   */
  const submit = (kw: string): void => {
    setKeyword(kw);
    pushHistory(kw);
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
    }
    lastQueryRef.current = kw;
    void runSearch(kw, type);
  };

  /**
   * 切换类型 Tab：已有关键词则立即重查。
   *
   * @param next 目标类型
   */
  const handleTab = (next: SearchType | null): void => {
    setType(next);
    if (lastQueryRef.current.trim() !== '') {
      void runSearch(lastQueryRef.current, next);
    }
  };

  /** 清空历史。 */
  const clearHistory = (): void => {
    writeStringListPref(HISTORY_KEY, []);
    setHistory([]);
  };

  /**
   * 渲染一个结果分组。
   *
   * @param titleKey   分组标题 key
   * @param group      分组数据
   * @param domain     域标识（testID 与跳转用）
   * @param renderRow  条目渲染
   * @returns 分组节点；无命中返回 null（整组隐藏）
   */
  const renderGroup = <T,>(
    titleKey: MessageKey,
    group: SearchGroup<T> | undefined,
    domain: string,
    renderRow: (item: T) => ReactElement,
  ): ReactElement | null => {
    if (group === undefined || group.list.length === 0) {
      return null;
    }
    return (
      <View style={styles.group} testID={`search-group-${domain}`}>
        <View style={styles.groupHead}>
          <LocalizedText numberOfLines={1} style={styles.groupTitle}>
            {t(titleKey)}
          </LocalizedText>
          <LocalizedText
            numberOfLines={1}
            style={styles.groupCount}
            testID={`search-viewall-${domain}`}
          >
            {t('content.search.viewAll', { count: group.total })}
          </LocalizedText>
        </View>
        {group.list.map(renderRow)}
      </View>
    );
  };

  const showIdle = phase === 'idle';

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('content.search.title')} />
      <View style={styles.root} testID="search-screen">
        <View style={styles.searchRow}>
          <TextInput
            testID="search-input"
            value={keyword}
            onChangeText={handleChange}
            onSubmitEditing={() => submit(keyword)}
            placeholder={t('content.search.placeholder')}
            accessibilityLabel={t('content.search.placeholder')}
            placeholderTextColor={color.text.muted}
            returnKeyType="search"
            style={styles.input}
          />
          <Pressable
            testID="search-cancel"
            accessibilityRole="button"
            accessibilityLabel={t('content.search.cancel')}
            onPress={() => router.back()}
            style={styles.cancelBtn}
          >
            <LocalizedText numberOfLines={1} style={styles.cancelText}>
              {t('content.search.cancel')}
            </LocalizedText>
          </Pressable>
        </View>

        <View style={styles.tabRow}>
          {TABS.map((tab) => {
            const active = type === tab.type;
            return (
              <Pressable
                key={tab.testID}
                testID={tab.testID}
                accessibilityRole="button"
                accessibilityState={{ selected: active }}
                accessibilityLabel={t(tab.key)}
                onPress={() => handleTab(tab.type)}
                style={[styles.tab, active ? styles.tabActive : null]}
              >
                <LocalizedText
                  numberOfLines={1}
                  style={[styles.tabText, active ? styles.tabTextActive : null]}
                >
                  {t(tab.key)}
                </LocalizedText>
              </Pressable>
            );
          })}
        </View>

        <ScrollView contentContainerStyle={styles.scroll}>
          {showIdle ? (
            <View testID="search-idle">
              {history.length > 0 ? (
                <View testID="search-history">
                  <View style={styles.groupHead}>
                    <LocalizedText numberOfLines={1} style={styles.groupTitle}>
                      {t('content.search.history')}
                    </LocalizedText>
                    <Pressable
                      testID="search-history-clear"
                      accessibilityRole="button"
                      onPress={clearHistory}
                    >
                      <LocalizedText numberOfLines={1} style={styles.groupCount}>
                        {t('content.search.clear')}
                      </LocalizedText>
                    </Pressable>
                  </View>
                  <View style={styles.chipWrap}>
                    {history.map((word) => (
                      <Pressable
                        key={word}
                        accessibilityRole="button"
                        accessibilityLabel={word}
                        onPress={() => submit(word)}
                        style={styles.chip}
                      >
                        <LocalizedText numberOfLines={1} style={styles.chipText}>
                          {word}
                        </LocalizedText>
                      </Pressable>
                    ))}
                  </View>
                </View>
              ) : null}

              <View style={styles.group} testID="search-hot">
                <LocalizedText numberOfLines={1} style={styles.groupTitle}>
                  {t('content.search.hot')}
                </LocalizedText>
                <View style={styles.chipWrap}>
                  {HOT_WORDS.map((word, index) => (
                    <Pressable
                      key={word}
                      testID={`search-hint-${word}`}
                      accessibilityRole="button"
                      accessibilityLabel={word}
                      onPress={() => submit(word)}
                      style={styles.chip}
                    >
                      <LocalizedText
                        numberOfLines={1}
                        style={[styles.chipText, index < 3 ? styles.chipHot : null]}
                      >
                        {word}
                      </LocalizedText>
                    </Pressable>
                  ))}
                </View>
              </View>
            </View>
          ) : null}

          {phase === 'loading' ? <LoadingState rows={4} /> : null}

          {phase === 'error' && error !== null ? (
            <View testID="search-error">
              <ErrorState
                error={error}
                onRetry={() => void runSearch(keyword, type)}
                retryTestID="search-retry"
              />
            </View>
          ) : null}

          {phase === 'empty' ? (
            <View testID="search-empty">
              <EmptyState i18nKey="content.search.emptyTitle" vars={{ kw: keyword }} />
              <LocalizedText numberOfLines={2} style={styles.emptyHint}>
                {t('content.search.emptyHint')}
              </LocalizedText>
              <LocalizedText numberOfLines={1} style={styles.groupTitle}>
                {t('content.search.suggestions')}
              </LocalizedText>
              <View style={styles.chipWrap}>
                {HOT_WORDS.slice(0, 4).map((word) => (
                  <Pressable
                    key={`sug-${word}`}
                    accessibilityRole="button"
                    accessibilityLabel={word}
                    onPress={() => submit(word)}
                    style={styles.chip}
                  >
                    <LocalizedText numberOfLines={1} style={styles.chipText}>
                      {word}
                    </LocalizedText>
                  </Pressable>
                ))}
              </View>
            </View>
          ) : null}

          {phase === 'success' && result !== null ? (
            <View>
              {renderGroup('content.search.sectionLawyer', result.lawyers, 'lawyer', (item) => (
                <Pressable
                  key={item.id}
                  testID={`search-row-lawyer-${item.id}`}
                  accessibilityRole="button"
                  accessibilityLabel={item.name ?? ''}
                  onPress={() => router.push(`/lawyer-detail?id=${item.id}`)}
                  style={({ pressed }) => [styles.row, pressed ? styles.pressed : null]}
                >
                  <LocalizedText numberOfLines={1} style={styles.rowTitle}>
                    {item.name ?? ''}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.rowSub}>
                    {t('content.search.lawyerMeta', {
                      firm: item.lawFirm ?? t('content.search.noFirm'),
                      specialties: item.specialtiesText ?? '',
                    })}
                  </LocalizedText>
                </Pressable>
              ))}

              {renderGroup('content.search.sectionArticle', result.articles, 'article', (item) => (
                <Pressable
                  key={item.id}
                  testID={`search-row-article-${item.id}`}
                  accessibilityRole="button"
                  accessibilityLabel={item.title ?? ''}
                  onPress={() => router.push(`/article?id=${item.id}`)}
                  style={({ pressed }) => [styles.row, pressed ? styles.pressed : null]}
                >
                  <LocalizedText numberOfLines={2} style={styles.rowTitle}>
                    {item.title ?? ''}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.rowSub}>
                    {t('content.search.articleMeta', {
                      date: (item.publishedAt ?? '').slice(0, 10),
                    })}
                  </LocalizedText>
                </Pressable>
              ))}

              {renderGroup('content.search.sectionCase', result.cases, 'case', (item) => (
                <Pressable
                  key={item.id}
                  testID={`search-row-case-${item.id}`}
                  accessibilityRole="button"
                  accessibilityLabel={item.title ?? ''}
                  onPress={() => router.push(`/case-detail?id=${item.id}`)}
                  style={({ pressed }) => [styles.row, pressed ? styles.pressed : null]}
                >
                  <LocalizedText numberOfLines={2} style={styles.rowTitle}>
                    {item.title ?? ''}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.rowSub}>
                    {t('content.search.caseMeta', {
                      category: item.categoryLabel ?? '',
                      source: item.source ?? '',
                    })}
                  </LocalizedText>
                </Pressable>
              ))}

              {renderGroup('content.search.sectionDocument', result.documents, 'document', (item) => (
                <Pressable
                  key={item.id}
                  testID={`search-row-document-${item.id}`}
                  accessibilityRole="button"
                  accessibilityLabel={item.name ?? ''}
                  onPress={() => router.push(`${String(PAGE_ROUTES.docForm)}?templateId=${item.id}` as Href)}
                  style={({ pressed }) => [styles.row, pressed ? styles.pressed : null]}
                >
                  <LocalizedText numberOfLines={1} style={styles.rowTitle}>
                    {item.name ?? ''}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.rowSub}>
                    {t('content.search.documentMeta', { category: item.category ?? '' })}
                  </LocalizedText>
                </Pressable>
              ))}
            </View>
          ) : null}
        </ScrollView>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.sm,
  },
  input: {
    flex: 1,
    height: 40,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
    paddingHorizontal: spacing.lg,
    ...typography.bodySmall,
    color: color.text.body,
  },
  cancelBtn: { paddingVertical: spacing.xs },
  cancelText: { ...typography.bodySmall, color: color.text.secondary },
  tabRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.sm,
  },
  tab: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    borderWidth: size.hairline,
    borderColor: color.border.outline,
  },
  tabActive: { borderColor: color.brand.primary },
  tabText: { ...typography.caption, color: color.text.secondary },
  tabTextActive: { color: color.brand.primary },
  scroll: { paddingHorizontal: spacing.xl, paddingBottom: spacing.xxxl },
  group: { marginBottom: spacing.xl, gap: spacing.xs },
  groupHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.xs,
  },
  groupTitle: { ...typography.bodyLarge, color: color.text.primary },
  groupCount: { ...typography.caption, color: color.brand.primary },
  chipWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.pill,
    backgroundColor: color.background.surface,
  },
  chipText: { ...typography.caption, color: color.text.secondary },
  chipHot: { color: color.brand.primary },
  row: {
    backgroundColor: color.background.surface,
    borderRadius: radius.md,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    gap: spacing.xxs,
  },
  rowTitle: { ...typography.body, color: color.text.primary },
  rowSub: { ...typography.micro, color: color.text.muted },
  pressed: { opacity: 0.7 },
  emptyHint: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: -spacing.lg,
    marginBottom: spacing.lg,
  },
});
