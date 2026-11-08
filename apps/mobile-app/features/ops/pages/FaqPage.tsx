/**
 * 常见问题页（APP 56）。
 *
 * 职责：分类筛选 + 可折叠问答列表 + 「没找到答案？」兜底入口。
 * 调用方：路由壳 `app/faq.tsx`；入口在 55 客服中心（常见问题 + 大家常问）与我的页。
 * 数据来源：`GET /api/v1/pub/faq?category=`（**7 条全部从库里读，前端不写死任何一条**）。
 * 注意：
 *   1. 工作流 C5 的验收原文：「改库里的答案，页面跟着变」——所以答案一个字都不许进代码。
 *   2. `answer` 里可能含 `\n`（后端 `FaqVO` 注释明写），必须**按行渲染**；
 *      整段塞进一个 `<Text>` 会把换行吃掉，长答案变成一整坨。
 *   3. 折叠用受控 `expandedId`（同一时间只展开一条）：多条同时展开会让 7 条答案
 *      把屏幕撑成无底洞，用户反而找不到别的题。
 *   4. 分类栏是**本地过滤**还是**重新请求**：走重新请求（后端按 category 过滤），
 *      这样「改库里的分类」也能立刻生效，前端不缓存一份可能过期的全量数据。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import type { AsyncState } from '@/shared/components/AsyncBoundary';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';

import { getFaqs } from '../api/faq';
import { getAppPublicConfig } from '../api/support';
import type { FaqCategory, FaqItem } from '../types';

/**
 * 分类筛选项。
 *
 * 这里写**字面量 key** 而不是 `` `ops.faq.category${x}` ``：i18n 规范 §3 明令禁止拼 key
 * （拼出来的 key `t()` 的类型收窄会失效，写错一个字母就退化成运行时 `⟦key⟧`）。
 */
const CATEGORY_TABS: { key: FaqCategory | undefined; labelKey: MessageKey }[] = [
  { key: undefined, labelKey: 'ops.faq.categoryAll' },
  { key: 'account', labelKey: 'ops.faq.categoryAccount' },
  { key: 'pay', labelKey: 'ops.faq.categoryPay' },
  { key: 'lawyer', labelKey: 'ops.faq.categoryLawyer' },
  { key: 'case', labelKey: 'ops.faq.categoryCase' },
];

/**
 * 常见问题页。
 *
 * 副作用：切换分类时重新请求列表；点「去客服中心」发起导航。
 *
 * @returns 常见问题界面
 */
export function FaqPage() {
  const { t } = useI18n();
  const router = useRouter();

  const [category, setCategory] = useState<FaqCategory | undefined>(undefined);
  const [state, setState] = useState<AsyncState<FaqItem[]>>({ phase: 'loading' });
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [onlineHours, setOnlineHours] = useState('');

  /** 拉列表；空数组走空态而不是正常态（空态文案与正常态不同）。 */
  const load = useCallback(async () => {
    setState({ phase: 'loading' });
    const result = await getFaqs(category);
    if (!result.ok) {
      setState({ phase: 'error', error: result.error });
      return;
    }
    setState(
      result.data.length === 0 ? { phase: 'empty' } : { phase: 'success', data: result.data },
    );
  }, [category]);

  useEffect(() => {
    void load();
  }, [load]);

  // 底部兜底文案要写服务时段：单独拉一次配置，失败就退化成通用文案。
  useEffect(() => {
    void (async () => {
      const result = await getAppPublicConfig();
      if (result.ok) {
        setOnlineHours(result.data.onlineHours);
      }
    })();
  }, []);

  /**
   * 切换展开项。再点一次已展开的那条 = 收起。
   *
   * @param id FAQ 主键
   */
  const toggle = (id: string) => {
    setExpandedId((current) => (current === id ? null : id));
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('ops.faq.title')} />

      {/* 分类栏固定在顶栏下方，滚动列表时仍可切换 */}
      <View style={styles.tabBar}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.tabRow}>
          {CATEGORY_TABS.map((tab) => {
            const active = tab.key === category;
            return (
              <Pressable
                key={tab.labelKey}
                accessibilityRole="button"
                accessibilityState={{ selected: active }}
                accessibilityLabel={t(tab.labelKey)}
                onPress={() => {
                  setCategory(tab.key);
                  setExpandedId(null);
                }}
                style={({ pressed }) => [
                  styles.tab,
                  active ? styles.tabActive : null,
                  pressed ? styles.pressed : null,
                ]}
              >
                <LocalizedText numberOfLines={1} style={active ? styles.tabTextActive : styles.tabText}>
                  {t(tab.labelKey)}
                </LocalizedText>
              </Pressable>
            );
          })}
        </ScrollView>
      </View>

      <ScrollView contentContainerStyle={styles.scroll}>
        <AsyncBoundary
          state={state}
          onRetry={() => void load()}
          emptyHintKey="ops.faq.emptyCategory"
          loadingRows={6}
        >
          {(items) => (
            <View style={styles.list}>
              {items.map((item, index) => {
                const expanded = expandedId === item.id;
                return (
                  <View
                    key={item.id}
                    style={[styles.card, index === items.length - 1 ? styles.cardLast : null]}
                  >
                    <Pressable
                      accessibilityRole="button"
                      accessibilityState={{ expanded }}
                      accessibilityLabel={item.question}
                      onPress={() => {
                        toggle(item.id);
                      }}
                      style={({ pressed }) => [styles.questionRow, pressed ? styles.pressed : null]}
                    >
                      <LocalizedText numberOfLines={3} style={styles.question}>
                        {item.question}
                      </LocalizedText>
                      <Ionicons
                        name={expanded ? 'chevron-up' : 'chevron-down'}
                        size={16}
                        color={color.text.muted}
                      />
                    </Pressable>
                    {expanded ? (
                      <View style={styles.answerBox}>
                        {/* 答案按行渲染：`\n` 分段，空行留一个行距 */}
                        {item.answer.split('\n').map((line, lineIndex) => (
                          <LocalizedText
                            key={`${item.id}-${String(lineIndex)}`}
                            numberOfLines={6}
                            style={line === '' ? styles.answerGap : styles.answer}
                          >
                            {line === '' ? ' ' : line}
                          </LocalizedText>
                        ))}
                      </View>
                    ) : null}
                  </View>
                );
              })}
            </View>
          )}
        </AsyncBoundary>

        {/* 没找到答案：兜底去客服中心 */}
        <View style={styles.notFound}>
          <LocalizedText numberOfLines={2} style={styles.notFoundTitle}>
            {t('ops.faq.notFoundTitle')}
          </LocalizedText>
          <LocalizedText numberOfLines={3} style={styles.notFoundDesc}>
            {onlineHours === ''
              ? t('ops.faq.notFoundDesc', { hours: t('ops.support.hoursFallback') })
              : t('ops.faq.notFoundDesc', { hours: onlineHours })}
          </LocalizedText>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('ops.faq.goSupport')}
            onPress={() => {
              router.push(PAGE_ROUTES.support);
            }}
            style={({ pressed }) => [styles.supportButton, pressed ? styles.pressed : null]}
          >
            <LocalizedText numberOfLines={1} style={styles.supportButtonText}>
              {t('ops.faq.goSupport')}
            </LocalizedText>
          </Pressable>
        </View>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  tabBar: {
    paddingVertical: spacing.sm,
    backgroundColor: color.background.surface,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  tabRow: {
    paddingHorizontal: spacing.xl,
    gap: spacing.sm,
  },
  tab: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
    borderRadius: radius.pill,
    backgroundColor: color.background.input,
  },
  tabActive: {
    backgroundColor: color.brand.primarySoft,
  },
  tabText: {
    ...typography.caption,
    color: color.text.secondary,
  },
  tabTextActive: {
    ...typography.caption,
    color: color.brand.primaryPressed,
  },
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  list: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    overflow: 'hidden',
  },
  card: {
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  cardLast: {
    borderBottomWidth: 0,
  },
  questionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.lg,
    paddingHorizontal: spacing.lg,
    minHeight: 52,
  },
  question: {
    ...typography.bodyLarge,
    color: color.text.primary,
    flex: 1,
  },
  answerBox: {
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.lg,
  },
  answer: {
    ...typography.body,
    color: color.text.secondary,
  },
  answerGap: {
    ...typography.caption,
    color: color.text.secondary,
  },
  notFound: {
    marginTop: spacing.xxl,
    padding: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.xs,
  },
  notFoundTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  notFoundDesc: {
    ...typography.bodySmall,
    color: color.text.secondary,
  },
  supportButton: {
    alignSelf: 'flex-start',
    marginTop: spacing.sm,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
  },
  supportButtonText: {
    ...typography.bodySmall,
    color: color.text.onBrand,
  },
  pressed: {
    opacity: 0.7,
  },
});
