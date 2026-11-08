/**
 * 案例详情（设计稿 `design/ui/19-case-detail.html`，段 5 G2）。
 *
 * 职责：标题区 / 五个分节（案情简介 → 争议焦点 → 裁判结果 → 相关法条 → 案例启示）/
 *        免责声明 / 底部固定栏（收藏 · 我有类似问题）/ 法条原文弹层。
 * 调用方：路由壳 `app/case-detail.tsx`（从案例列表点进来，带 `?id=`）。
 * 数据来源：`GET /app/case-library/{id}?lang=`、`POST/DELETE /app/case-library/{id}/favorite`、
 *          `GET /pub/law-items?kw=`（点法条查原文）。
 * 注意：
 *   1. **不显示案号、不显示法院全名**（设计稿红线）：接口根本没返回这两个字段，
 *      页面也不许从别处拼。
 *   2. 法条渲染成「《中华人民共和国民法典》第七百三十三条」：`lawName` + `articleNo`，
 *      中文书名号与条号走 `LtrText`（RTL 下条文号不能被镜像）。
 *   3. 「我有类似问题」跳 AI 对话页，**不带案例内容**（不把案例当咨询上下文，工作流 G2 第 2 条）。
 *   4. 四态：加载（骨架）/ 空（已下架 + 返回）/ 错误（重试）/ 正常；
 *      缺译时顶部提示「暂无该语言版本，以下为简体中文内容」。
 *   5. 底部固定栏用 `position: absolute` 贴在屏幕底部，列表底部留出等高占位，
 *      滚到底也不会被压住（设计自查项）。
 *
 * testID 清单：case-detail-screen / case-title / case-meta / case-summary /
 *              case-dispute / case-judgment / case-laws / case-insight / case-disclaimer /
 *              case-favorite / case-ask / case-offline / case-error / case-retry /
 *              case-lang-fallback / law-popup
 */

import { useCallback, useEffect, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';

import { fetchCaseDetail } from '@/features/content/api/caseLibrary';
import type { CaseDetail, CaseLawRef } from '@/features/content/api/caseLibrary';
import { searchLawItems } from '@/features/content/api/search';
import { AppHeader } from '@/shared/components/AppHeader';
import { EmptyState } from '@/shared/components/EmptyState';
import { ErrorState } from '@/shared/components/ErrorState';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { toggleCaseFavorite } from '@/features/content/api/favorite';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import type { AppError } from '@/shared/api/types';
import { color, radius, shadow, size, spacing, typography } from '@/shared/theme';

/** 正文分节配置（顺序即设计稿的阅读顺序）。 */
const SECTIONS: { labelKey: MessageKey; field: 'summary' | 'disputeFocus' | 'judgment' | 'insight' }[] = [
  { labelKey: 'content.case.labelSummary', field: 'summary' },
  { labelKey: 'content.case.labelDispute', field: 'disputeFocus' },
  { labelKey: 'content.case.labelJudgment', field: 'judgment' },
  { labelKey: 'content.case.labelInsight', field: 'insight' },
];

/** 底部固定栏高度（含上下内边距），用于给滚动内容留出避让空间。 */
const FOOTER_HEIGHT = 72;

/**
 * 渲染案例详情页。
 *
 * 副作用：挂载时按 `id` 拉详情；点法条时请求条文原文；点收藏时写 social_action。
 *
 * @returns 案例详情页
 */
export function CaseDetailPage() {
  const { t, lang } = useI18n();
  const router = useRouter();
  const params = useLocalSearchParams<{ id?: string }>();
  const caseId = typeof params.id === 'string' ? params.id : '';
  const [detail, setDetail] = useState<CaseDetail | null>(null);
  const [phase, setPhase] = useState<'loading' | 'success' | 'empty' | 'error'>('loading');
  const [error, setError] = useState<AppError | null>(null);
  const [favoriting, setFavoriting] = useState(false);
  const [lawPopup, setLawPopup] = useState<{ title: string; content: string } | null>(null);

  /** 拉详情。 */
  const load = useCallback(async (): Promise<void> => {
    if (caseId === '') {
      setPhase('empty');
      return;
    }
    setPhase('loading');
    const res = await fetchCaseDetail(caseId, lang);
    if (!res.ok) {
      // 409002 = 不存在或已下架：设计稿的空态是「这个案例已经下架了」
      if (res.error.kind === 'business' && res.error.code === 409002) {
        setPhase('empty');
        return;
      }
      setError(res.error);
      setPhase('error');
      return;
    }
    setDetail(res.data);
    setError(null);
    setPhase('success');
  }, [caseId, lang]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 切换收藏。 */
  const handleFavorite = async (): Promise<void> => {
    if (detail === null || favoriting) {
      return;
    }
    setFavoriting(true);
    const next = !(detail.favorited === true);
    const res = await toggleCaseFavorite(detail.id, next);
    setFavoriting(false);
    if (res.ok) {
      setDetail({ ...detail, favorited: next });
    }
  };

  /**
   * 点法条：查条文原文并弹层。
   *
   * @param ref 法条引用
   */
  const openLaw = async (ref: CaseLawRef): Promise<void> => {
    const keyword = `${ref.lawName} ${ref.articleNo}`;
    const res = await searchLawItems(keyword, 5);
    if (!res.ok) {
      setLawPopup({ title: `${ref.lawName} ${ref.articleNo}`, content: t('content.case.lawPopupEmpty') });
      return;
    }
    const hit = res.data.list.find((item) => item.articleNo === ref.articleNo)
      ?? res.data.list[0];
    setLawPopup({
      title: hit?.articleNo === null || hit?.articleNo === undefined
        ? `${ref.lawName} ${ref.articleNo}`
        : `${hit.lawName ?? ref.lawName} ${hit.articleNo}`,
      content: hit?.content ?? t('content.case.lawPopupEmpty'),
    });
  };

  /** 标题区的「来源 · 年份 · 阅读量」文案。 */
  const metaText = ((): string => {
    if (detail === null) {
      return '';
    }
    const source = detail.source ?? '';
    const count = detail.viewCount ?? 0;
    if (detail.judgmentYear === null) {
      return t('content.case.metaNoYear', { source, count });
    }
    return t('content.case.metaLine', { source, year: detail.judgmentYear, count });
  })();

  return (
    <Screen edges={['top', 'left', 'right']}>
      <AppHeader title={t('content.case.detailTitle')} />
      <View style={styles.root} testID="case-detail-screen">
        {phase === 'loading' ? <LoadingState rows={6} /> : null}

        {phase === 'error' && error !== null ? (
          <View style={styles.center} testID="case-error">
            <ErrorState error={error} onRetry={() => void load()} retryTestID="case-retry" />
          </View>
        ) : null}

        {phase === 'empty' ? (
          <View style={styles.center} testID="case-offline">
            <EmptyState i18nKey="content.case.offline" />
            <PrimaryButton
              label={t('content.case.backToList')}
              variant="outline"
              onPress={() => router.back()}
            />
          </View>
        ) : null}

        {phase === 'success' && detail !== null ? (
          <>
            <ScrollView contentContainerStyle={styles.scroll}>
              {detail.translated === false && lang !== 'zh-Hans' ? (
                <LocalizedText numberOfLines={2} style={styles.fallback} testID="case-lang-fallback">
                  {t('content.case.langFallback')}
                </LocalizedText>
              ) : null}

              <LocalizedText numberOfLines={3} style={styles.title} testID="case-title">
                {detail.title}
              </LocalizedText>
              <LocalizedText numberOfLines={1} style={styles.meta} testID="case-meta">
                {metaText}
              </LocalizedText>

              {SECTIONS.map((section) => {
                const body = detail[section.field];
                if (body === null || body === '') {
                  return null;
                }
                return (
                  <View key={section.field} style={styles.section} testID={`case-${section.field}`}>
                    <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
                      {t(section.labelKey)}
                    </LocalizedText>
                    <LocalizedText numberOfLines={40} style={styles.sectionBody}>
                      {body}
                    </LocalizedText>
                  </View>
                );
              })}

              {detail.lawRefs.length > 0 ? (
                <View style={styles.section} testID="case-laws">
                  <LocalizedText numberOfLines={1} style={styles.sectionTitle}>
                    {t('content.case.labelLaws')}
                  </LocalizedText>
                  {detail.lawRefs.map((ref) => (
                    <Pressable
                      key={`${ref.lawName}-${ref.articleNo}`}
                      accessibilityRole="button"
                      onPress={() => void openLaw(ref)}
                      style={styles.lawRow}
                    >
                      <LtrText numberOfLines={2} style={styles.lawText}>
                        {`《${ref.lawName}》${ref.articleNo}`}
                      </LtrText>
                    </Pressable>
                  ))}
                </View>
              ) : null}

              <LocalizedText numberOfLines={4} style={styles.disclaimer} testID="case-disclaimer">
                {detail.disclaimer}
              </LocalizedText>
            </ScrollView>

            <View style={[styles.footer, shadow.cardRaised]}>
              <Pressable
                testID="case-favorite"
                accessibilityRole="button"
                accessibilityState={{ selected: detail.favorited === true }}
                onPress={() => void handleFavorite()}
                style={({ pressed }) => [styles.ghostBtn, pressed ? styles.pressed : null]}
              >
                <LocalizedText numberOfLines={1} style={styles.ghostText}>
                  {detail.favorited === true
                    ? t('content.case.favorited')
                    : t('content.case.favorite')}
                </LocalizedText>
              </Pressable>
              <View style={styles.footerPrimary}>
                <PrimaryButton
                  testID="case-ask"
                  label={t('content.case.askSimilar')}
                  onPress={() => router.push('/agent')}
                />
              </View>
            </View>
          </>
        ) : null}
      </View>

      <Modal
        visible={lawPopup !== null}
        transparent
        animationType="fade"
        onRequestClose={() => setLawPopup(null)}
      >
        <View style={styles.mask}>
          <Pressable style={StyleSheet.absoluteFill} onPress={() => setLawPopup(null)} accessibilityRole="button" />
          <View style={[styles.popup, shadow.cardRaised]} testID="law-popup">
            <LocalizedText numberOfLines={2} style={styles.popupTitle}>
              {lawPopup?.title ?? t('content.case.lawPopupTitle')}
            </LocalizedText>
            <ScrollView style={styles.popupBody}>
              <LocalizedText numberOfLines={60} style={styles.popupText}>
                {lawPopup?.content ?? ''}
              </LocalizedText>
            </ScrollView>
            <PrimaryButton
              label={t('content.case.lawPopupClose')}
              variant="outline"
              onPress={() => setLawPopup(null)}
            />
          </View>
        </View>
      </Modal>
    </Screen>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  center: { flex: 1, paddingHorizontal: spacing.xl, gap: spacing.lg, justifyContent: 'center' },
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
    // 给底部固定栏留出避让空间（否则最后一段被压住）
    paddingBottom: FOOTER_HEIGHT + spacing.xl,
  },
  fallback: {
    ...typography.caption,
    color: color.state.warning,
    marginBottom: spacing.sm,
  },
  title: { ...typography.h1, color: color.text.primary, marginBottom: spacing.xs },
  meta: { ...typography.micro, color: color.text.muted, marginBottom: spacing.lg },
  section: { marginBottom: spacing.xl, gap: spacing.xs },
  sectionTitle: { ...typography.bodyLarge, color: color.text.primary },
  sectionBody: { ...typography.paragraph, color: color.text.body },
  lawRow: {
    backgroundColor: color.background.surface,
    borderRadius: radius.sm,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    marginTop: spacing.xs,
  },
  lawText: { ...typography.bodySmall, color: color.brand.primary },
  disclaimer: {
    ...typography.micro,
    color: color.text.muted,
    marginTop: spacing.lg,
    lineHeight: 18,
  },
  footer: {
    position: 'absolute',
    // RTL：用 start/end 而不是 left/right，避免与 I18nManager 的自动镜像叠加成「翻两遍」
    start: 0,
    end: 0,
    bottom: 0,
    height: FOOTER_HEIGHT,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.sm,
    backgroundColor: color.background.surface,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
  },
  ghostBtn: {
    height: 44,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.lg,
    borderWidth: size.hairline,
    borderColor: color.border.subtle,
    alignItems: 'center',
    justifyContent: 'center',
  },
  ghostText: { ...typography.bodySmall, color: color.text.secondary },
  footerPrimary: { flex: 1 },
  pressed: { opacity: 0.7 },
  mask: {
    flex: 1,
    backgroundColor: color.overlay.scrim,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.xl,
  },
  popup: {
    width: '100%',
    maxWidth: 380,
    maxHeight: '70%',
    borderRadius: radius.lg,
    backgroundColor: color.background.surface,
    padding: spacing.xl,
    gap: spacing.md,
  },
  popupTitle: { ...typography.h3, color: color.text.primary },
  popupBody: { maxHeight: 320 },
  popupText: { ...typography.paragraph, color: color.text.body },
});
