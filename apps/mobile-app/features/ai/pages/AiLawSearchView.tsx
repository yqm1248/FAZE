/**
 * 法条检索降级视图（F4 / 锚点 `3-A7`）。
 *
 * 职责：`ai_enabled=false` 时接管 AI 对话页——顶部标题变「法条检索」、一句「AI 顾问暂不可用，
 * 可先查询法条」、输入区换成搜索框，结果按法条卡片展示。**不白屏、不显示「AI 不可用」错误页**
 * （降级不是故障，工作流 F4 第 2 条）。
 * 调用方：`features/ai/pages/AiChatPage.tsx`（读 `/pub/sys-config` 判定 `ai_enabled === false`）。
 * 数据来源：`GET /api/v1/pub/law-items?kw=`（B2，公开无鉴权）。
 * 注意：
 *   1. **不调豆包**：本视图只有法条库查询（3-A7「不调用大模型」的落点）。
 *   2. 四态齐全：加载（骨架）/ 空（提示）/ 错误（可重试）/ 正常。
 *   3. `testID`：`ai-degraded-search`（输入框）/ `ai-degraded-result-<index>`（结果行）。
 *   4. 法条原文**永不翻译**（技术路线 §11.4），`lawName` / `articleNo` / `content` 原样展示。
 */

import { useCallback, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { searchLawItems } from '@/features/ai/api/ai';
import type { LawItemBrief } from '@/features/ai/api/ai';
import { AppCard } from '@/shared/components/AppCard';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LoadingState } from '@/shared/components/LoadingState';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 检索状态。 */
type SearchPhase = 'idle' | 'loading' | 'ready' | 'error';

/**
 * 渲染法条检索视图。
 *
 * 副作用：提交时发一次 `GET /pub/law-items`。
 *
 * @returns 降级检索界面
 */
export function AiLawSearchView() {
  const { t } = useI18n();
  const [keyword, setKeyword] = useState('');
  const [phase, setPhase] = useState<SearchPhase>('idle');
  const [results, setResults] = useState<LawItemBrief[]>([]);

  /** 执行检索（空关键词也允许：展示前 20 条）。 */
  const search = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const result = await searchLawItems(keyword);
    if (!result.ok) {
      setPhase('error');
      return;
    }
    setResults(result.data.list);
    setPhase('ready');
  }, [keyword]);

  return (
    <Screen edges={['top', 'left', 'right']}>
      <View style={styles.appbar}>
        <LocalizedText numberOfLines={1} style={styles.appbarTitle}>
          {t('ai.chat.degradedTitle')}
        </LocalizedText>
      </View>

      <View style={styles.banner}>
        <LocalizedText numberOfLines={2} style={styles.bannerText}>
          {t('ai.chat.degradedHint')}
        </LocalizedText>
      </View>

      <View style={styles.searchRow}>
        <TextInput
          testID="ai-degraded-search"
          value={keyword}
          onChangeText={setKeyword}
          onSubmitEditing={() => void search()}
          placeholder={t('ai.chat.searchPlaceholder')}
          placeholderTextColor={color.text.muted}
          accessibilityLabel={t('ai.chat.searchPlaceholder')}
          style={styles.input}
        />
        <Pressable
          onPress={() => void search()}
          accessibilityRole="button"
          accessibilityLabel={t('ai.chat.search')}
          style={styles.searchButton}
        >
          <LocalizedText numberOfLines={1} style={styles.searchButtonText}>
            {t('ai.chat.search')}
          </LocalizedText>
        </Pressable>
      </View>

      {phase === 'loading' ? <LoadingState rows={3} /> : null}

      {phase === 'error' ? (
        <Pressable onPress={() => void search()} accessibilityRole="button" style={styles.retry}>
          <LocalizedText numberOfLines={2} style={styles.retryText}>
            {t('ai.chat.searchError')}
          </LocalizedText>
        </Pressable>
      ) : null}

      {phase === 'ready' && results.length === 0 ? (
        <View style={styles.empty}>
          <LocalizedText numberOfLines={2} style={styles.emptyText}>
            {t('ai.chat.searchEmpty')}
          </LocalizedText>
        </View>
      ) : null}

      {phase === 'ready' && results.length > 0 ? (
        <ScrollView contentContainerStyle={styles.list}>
          {results.map((item, index) => (
            <AppCard key={item.id} style={styles.card}>
              <View testID={`ai-degraded-result-${String(index)}`} style={styles.cardInner}>
                <LocalizedText numberOfLines={2} style={styles.cardTitle}>
                  {`《${item.lawName}》${item.articleNo}`}
                </LocalizedText>
                <LocalizedText numberOfLines={6} style={styles.cardContent}>
                  {item.content}
                </LocalizedText>
              </View>
            </AppCard>
          ))}
        </ScrollView>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  appbar: {
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
  banner: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.xl,
    backgroundColor: color.brand.primarySoft,
  },
  bannerText: {
    ...typography.caption,
    color: color.brand.primaryPressed,
  },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
  },
  input: {
    flex: 1,
    minHeight: size.inputHeight,
    paddingHorizontal: spacing.md,
    ...typography.body,
    color: color.text.body,
    backgroundColor: color.background.input,
    borderRadius: radius.lg,
  },
  searchButton: {
    height: size.inputHeight,
    paddingHorizontal: spacing.lg,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
  },
  searchButtonText: {
    ...typography.body,
    color: color.text.onBrand,
  },
  list: {
    paddingHorizontal: spacing.xl,
    paddingBottom: spacing.xxl,
    gap: spacing.md,
  },
  card: {
    padding: 0,
  },
  cardInner: {
    padding: spacing.lg,
  },
  cardTitle: {
    ...typography.body,
    color: color.brand.primary,
  },
  cardContent: {
    ...typography.bodySmall,
    color: color.text.body,
    marginTop: spacing.xs,
  },
  retry: {
    marginHorizontal: spacing.xl,
    marginTop: spacing.xl,
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
  retryText: {
    ...typography.body,
    color: color.brand.primary,
  },
  empty: {
    paddingTop: spacing.xxxl,
    paddingHorizontal: spacing.xl,
    alignItems: 'center',
  },
  emptyText: {
    ...typography.body,
    color: color.text.muted,
    textAlign: 'center',
  },
});
