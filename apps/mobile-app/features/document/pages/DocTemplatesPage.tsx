/**
 * 文书模板列表页（段 4 E1，设计稿 `design/ui/23-doc-templates.html`）。
 *
 * 职责：分类筛选 + 模板卡片网格；卡片显示模板名、分类与「免费生成」标识；
 *       点卡片按 `code` 前缀分流到起诉状表单页或其它文书表单页。
 * 调用方：`app/doc` 目录下的模板列表路由壳（`templates.tsx`）；「我的」页「我的文书」入口跳到这里。
 * 数据来源：`GET /app/doc-templates`（只返回 `active`，见 `features/document/api/client.ts`）。
 * 注意：
 *   1. **分类不写死在 App 里**：筛选条从接口数据里收集 distinct `category`，
 *      只把 4 个已知分类做成翻译映射（`CATEGORY_KEYS`），未来新增分类直接按原值显示，
 *      不会因为「App 不认识这个分类」把模板藏起来。
 *   2. 不实现设计稿的搜索框（全局搜索在段 5，工作流 E1 明确删除）。
 *   3. 卡片分流规则：`code` 以 `LAWSUIT-` 开头 → `/doc/lawsuit?templateId=`，
 *      其余 → `/doc/form?templateId=`。**不要按分类判断**，分类可以变、code 前缀是契约。
 *   4. 四态齐全：加载（骨架）/ 空（暂无可用模板 + 说明）/ 错误（可重试）/ 正常。
 *   5. 视觉全部取 `tokens`；RTL 用 `start/end` 与自动镜像的 `flexDirection: 'row'`。
 *
 * testID 清单（全部真实挂载）：
 *   templates-screen / templates-loading / templates-error / templates-retry /
 *   templates-empty / templates-filter-{all|分类下标} / templates-grid /
 *   templates-card-{code} / templates-tip
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import type { Href } from 'expo-router';
import { useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { fetchTemplates } from '@/features/document/api/client';
import type { DocTemplateBrief } from '@/features/document/api/client';
import { AppHeader } from '@/shared/components/AppHeader';
import { DirectionalIcon } from '@/shared/components/DirectionalIcon';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { TagChip } from '@/shared/components/TagChip';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';

/** 模板 code 前缀：命中则进起诉状表单页（工作流 E1 的分流规则）。 */
const LAWSUIT_CODE_PREFIX = 'LAWSUIT-';

/** 4 个已知分类 → 文案 key。未知分类按后端原值显示（数据驱动，不写死分类集合）。 */
const CATEGORY_KEYS: Record<string, MessageKey> = {
  诉讼文书: 'document.templates.categoryLitigation',
  合同文书: 'document.templates.categoryContract',
  劳动文书: 'document.templates.categoryLabor',
  跨境电商: 'document.templates.categoryCrossBorder',
};

/** 分类 → 图标（仅视觉，不影响数据）。未知分类用通用文书图标。 */
const CATEGORY_ICONS: Record<string, React.ComponentProps<typeof Ionicons>['name']> = {
  诉讼文书: 'scale-outline',
  合同文书: 'document-text-outline',
  劳动文书: 'briefcase-outline',
  跨境电商: 'globe-outline',
};

/** 「全部」筛选项的下标值（分类用后端原值，避免字符串冲突）。 */
const ALL_CATEGORIES = '__all__';

/**
 * 取分类的展示文案。
 *
 * @param category 后端 `doc_template.category` 原值（可空）
 * @param t        翻译函数
 * @returns 已翻译文案；未知分类按原值显示
 */
function categoryLabel(
  category: string | null,
  t: ReturnType<typeof useI18n>['t'],
): string {
  if (category === null || category.trim() === '') {
    return '';
  }
  const key = CATEGORY_KEYS[category];
  return key === undefined ? category : t(key);
}

/**
 * 拼表单页路由（带 `templateId`）。
 *
 * @param code       模板 code（决定进哪一页）
 * @param templateId 模板主键
 * @returns 可传给 `router.push` 的地址
 */
function formHref(code: string, templateId: string): Href {
  const base = code.startsWith(LAWSUIT_CODE_PREFIX)
    ? PAGE_ROUTES.docLawsuit
    : PAGE_ROUTES.docForm;
  return `${String(base)}?templateId=${encodeURIComponent(templateId)}` as Href;
}

/**
 * 模板卡片。
 *
 * @param props.template    模板
 * @param props.categoryText 已翻译分类
 * @param props.onPress     点击
 * @returns 一张模板卡
 */
function TemplateCard({
  template,
  categoryText,
  onPress,
}: {
  template: DocTemplateBrief;
  categoryText: string;
  onPress: () => void;
}) {
  const { t } = useI18n();
  const icon = (template.category === null ? undefined : CATEGORY_ICONS[template.category]) ?? 'document-outline';

  return (
    <Pressable
      testID={`templates-card-${template.code}`}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={template.name}
      style={({ pressed }) => [styles.card, pressed ? styles.pressed : null]}
    >
      <View style={styles.cardIcon}>
        <Ionicons name={icon} size={22} color={color.brand.primary} />
      </View>
      <LocalizedText numberOfLines={2} style={styles.cardName}>
        {template.name}
      </LocalizedText>
      {categoryText === '' ? null : (
        <TagChip label={categoryText} variant="brand" style={styles.cardTag} />
      )}
      <View style={styles.cardFoot}>
        <LocalizedText numberOfLines={1} style={styles.cardFree}>
          {t('document.templates.free')}
        </LocalizedText>
        <DirectionalIcon name="chevron-forward" size={16} color={color.text.muted} />
      </View>
    </Pressable>
  );
}

/**
 * 文书模板列表页。
 *
 * 副作用：挂载时拉一次模板列表；筛选只在本地过滤，不重新请求。
 *
 * @returns 分类筛选 + 模板卡片网格 + 底部说明
 */
export function DocTemplatesPage() {
  const { t } = useI18n();
  const router = useRouter();
  const resource = useAsyncResource(fetchTemplates, {
    emptyWhen: (list) => list.length === 0,
  });
  // 从表单 / 预览返回时静默刷新模板列表（不闪骨架）。
  useRefreshOnFocus(resource.reloadSilently);
  const [activeCategory, setActiveCategory] = useState<string>(ALL_CATEGORIES);

  /**
   * 成功态的模板列表；非成功态给空数组。
   *
   * 包一层 `useMemo`：直接写条件表达式会让下面两个 `useMemo` 的依赖每次渲染都变，
   * 触发 exhaustive-deps 告警（而且会让过滤每次都重算）。
   */
  const templates = useMemo(
    () => (resource.state.phase === 'success' ? resource.state.data : []),
    [resource.state],
  );

  /** 从数据里收集出现过的分类（保持接口顺序），不预置 4 个分类。 */
  const categories = useMemo(() => {
    const seen = new Set<string>();
    const list: string[] = [];
    for (const template of templates) {
      const category = template.category;
      if (category === null || category.trim() === '' || seen.has(category)) {
        continue;
      }
      seen.add(category);
      list.push(category);
    }
    return list;
  }, [templates]);

  const visibleTemplates = useMemo(
    () =>
      activeCategory === ALL_CATEGORIES
        ? templates
        : templates.filter((template) => template.category === activeCategory),
    [templates, activeCategory],
  );

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('document.templates.title')} />
      <View style={styles.root} testID="templates-screen">
        <ScrollView contentContainerStyle={styles.scroll}>
          <LocalizedText numberOfLines={1} style={styles.heading}>
            {t('document.templates.heading')}
          </LocalizedText>
          <LocalizedText numberOfLines={2} style={styles.subheading}>
            {t('document.templates.subheading')}
          </LocalizedText>

          {resource.state.phase === 'success' ? (
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.filterRow}
            >
              <Pressable
                testID="templates-filter-all"
                onPress={() => setActiveCategory(ALL_CATEGORIES)}
                accessibilityRole="button"
                accessibilityState={{ selected: activeCategory === ALL_CATEGORIES }}
                accessibilityLabel={t('document.templates.filterAll')}
                style={[
                  styles.chip,
                  activeCategory === ALL_CATEGORIES ? styles.chipActive : null,
                ]}
              >
                <LocalizedText
                  numberOfLines={1}
                  style={[
                    styles.chipText,
                    activeCategory === ALL_CATEGORIES ? styles.chipTextActive : null,
                  ]}
                >
                  {t('document.templates.filterAll')}
                </LocalizedText>
              </Pressable>
              {categories.map((category, index) => {
                const active = activeCategory === category;
                return (
                  <Pressable
                    key={category}
                    testID={`templates-filter-${index}`}
                    onPress={() => setActiveCategory(category)}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    accessibilityLabel={categoryLabel(category, t)}
                    style={[styles.chip, active ? styles.chipActive : null]}
                  >
                    <LocalizedText
                      numberOfLines={1}
                      style={[styles.chipText, active ? styles.chipTextActive : null]}
                    >
                      {categoryLabel(category, t)}
                    </LocalizedText>
                  </Pressable>
                );
              })}
            </ScrollView>
          ) : null}

          {resource.state.phase === 'loading' ? (
            <View testID="templates-loading">
              <LoadingState rows={4} />
            </View>
          ) : null}

          {resource.state.phase === 'error' ? (
            <View testID="templates-error">
              <ErrorState
                error={resource.state.error}
                onRetry={resource.reload}
                retryTestID="templates-retry"
              />
            </View>
          ) : null}

          {resource.state.phase === 'empty' ? (
            <View testID="templates-empty">
              <EmptyState i18nKey="document.templates.empty" />
              <LocalizedText numberOfLines={2} style={styles.emptyDesc}>
                {t('document.templates.emptyDesc')}
              </LocalizedText>
            </View>
          ) : null}

          {resource.state.phase === 'success' ? (
            visibleTemplates.length === 0 ? (
              <View testID="templates-empty">
                <EmptyState i18nKey="document.templates.empty" />
                <LocalizedText numberOfLines={2} style={styles.emptyDesc}>
                  {t('document.templates.emptyDesc')}
                </LocalizedText>
              </View>
            ) : (
              <>
                <View style={styles.grid} testID="templates-grid">
                  {visibleTemplates.map((template) => (
                    <TemplateCard
                      key={template.id}
                      template={template}
                      categoryText={categoryLabel(template.category, t)}
                      onPress={() => router.push(formHref(template.code, template.id))}
                    />
                  ))}
                </View>
                <View style={styles.tip} testID="templates-tip">
                  <Ionicons name="information-circle-outline" size={16} color={color.text.muted} />
                  <LocalizedText numberOfLines={3} style={styles.tipText}>
                    {t('document.templates.tip')}
                  </LocalizedText>
                </View>
              </>
            )
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
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  heading: {
    ...typography.h2,
    color: color.text.primary,
  },
  subheading: {
    ...typography.bodySmall,
    color: color.text.muted,
    marginTop: spacing.xxs,
  },
  filterRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    paddingVertical: spacing.md,
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
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
    gap: spacing.md,
    marginTop: spacing.xs,
  },
  card: {
    // 宽度固定 48%（不是 flexBasis + flexGrow）：筛选后只剩一张卡时，
    // flexGrow 会把它拉成整行宽，与两列网格的视觉不一致。
    width: '48%',
    padding: spacing.md,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    gap: spacing.xxs,
  },
  cardIcon: {
    width: size.cardIcon,
    height: size.cardIcon,
    borderRadius: radius.sm,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.xs,
  },
  cardName: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  cardTag: {
    marginTop: spacing.xxs,
  },
  cardFoot: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: spacing.xs,
  },
  cardFree: {
    ...typography.micro,
    color: color.brand.primary,
  },
  tip: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.xs,
    marginTop: spacing.xl,
    padding: spacing.md,
    borderRadius: radius.sm,
    backgroundColor: color.background.surface,
  },
  tipText: {
    ...typography.caption,
    flex: 1,
    color: color.text.muted,
  },
  emptyDesc: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    paddingHorizontal: spacing.xl,
  },
  pressed: {
    opacity: 0.8,
  },
});
