/**
 * 客服中心页（APP 55）。
 *
 * 职责：三个自助/求助入口（常见问题 → 在线客服 → 客服热线）+ 「大家常问」5 条直达 + 页尾说明。
 * 调用方：路由壳 `app/support.tsx`；入口在「我的」页的「我的与服务」分组。
 * 数据来源：`GET /api/v1/pub/app/config`（在线时段 + 电话，免登录）+
 *           `GET /api/v1/pub/faq`（「大家常问」取前 5 条）。
 * 注意：
 *   1. **顺序是这一页最重要的设计决定**：常见问题在最上、占最大面积。人工客服是成本最高的
 *      通道，把自助放前面不是省成本，是让用户在 30 秒内拿到答案，而不是排 20 分钟队
 *      （设计稿 55 的文件头原话）。
 *   2. **号码与时段一律从服务端读**，页面不许写死——工作流 §十 要求「改了 `sys_config`
 *      后页面跟着变」。配置读失败时电话区退化成「客服电话待配置」，入口不隐藏。
 *   3. **本段不做真人在线客服（IM）**（工作流 §零之二 第 7 条）：时段内点「在线客服」
 *      只提示「当前无在线客服，请留言或拨打 {电话}」；非时段直接去反馈页留言。
 *   4. 反馈页的 `type` 白名单只有 `feature/bug/experience/other`（后端 `FeedbackSubmitBO`），
 *      **没有 `service`**，所以「留言」复用反馈页时用 `other`，并带 `from=support` 让页面
 *      换一套引导文案。工作流原文写的 `type=service` 不可行，已记段 7 §十一。
 *   5. 服务时段用 `isWithinServiceHours()` 判断，每分钟重算一次——页面开着跨过 20:00
 *      时，状态要跟着变。
 */

import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Linking, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { AppHeader } from '@/shared/components/AppHeader';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import type { AsyncState } from '@/shared/components/AsyncBoundary';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { Screen } from '@/shared/components/Screen';
import { SectionTitle } from '@/shared/components/SectionTitle';
import { useToast } from '@/shared/components/AppToast';
import { useI18n } from '@/shared/i18n';
import { feedbackHref, PAGE_ROUTES } from '@/shared/navigation/routes';
import { color, radius, shadow, size, spacing, typography } from '@/shared/theme';

import { getFaqs } from '../api/faq';
import { getAppPublicConfig, isWithinServiceHours } from '../api/support';
import type { AppPublicConfig, FaqItem } from '../types';

/** 「大家常问」展示条数（稿面固定 5 条）。 */
const HOT_FAQ_LIMIT = 5;

/** 时段刷新的间隔（毫秒）：每分钟重算一次在线状态。 */
const CLOCK_REFRESH_MS = 60_000;

/** 热线服务时段（稿面文案，固定值；只影响展示，不参与逻辑）。 */
const PHONE_HOURS = '9:00–18:00';

/**
 * 三个入口的图标与配色（图标名来自 Ionicons）。
 *
 * 设计稿里三个入口是「主色 / 青 / 橙」三种底色。令牌层没有 `state.warningSoft`
 * （只有 `state.warning` 一个橙色），新增浅橙令牌要同时改 `tokens.json`、
 * `docs/design/设计令牌.md` 并重算文档基线指纹——为一个图标底色不值得。
 * 折中：电话入口保留主色浅底，但把**图标色**换成警告橙，三者在视觉上仍可区分。
 * 这处折中记在段 7 工作流 §十一。
 */
const ENTRY_STYLE = {
  faq: { icon: 'help-circle-outline', background: color.brand.primarySoft, tint: color.brand.primaryPressed },
  chat: { icon: 'chatbubble-ellipses-outline', background: color.state.successSoft, tint: color.state.success },
  phone: { icon: 'call-outline', background: color.brand.primarySoft, tint: color.state.warning },
} as const;

/**
 * 客服中心页。
 *
 * 副作用：挂载时拉配置与 FAQ；点电话时调起系统拨号盘。
 *
 * @returns 客服中心界面
 */
export function SupportPage() {
  const { t } = useI18n();
  const router = useRouter();
  const toast = useToast();

  const [state, setState] = useState<AsyncState<AppPublicConfig>>({ phase: 'loading' });
  const [hotFaqs, setHotFaqs] = useState<FaqItem[]>([]);
  const [now, setNow] = useState(() => new Date());

  /** 拉配置：失败时要区分「网络挂了」与「后端没配这两个键」，前者可重试。 */
  const load = useCallback(async () => {
    setState({ phase: 'loading' });
    const result = await getAppPublicConfig();
    if (!result.ok) {
      setState({ phase: 'error', error: result.error });
      return;
    }
    setState({ phase: 'success', data: result.data });
  }, []);

  /** 拉「大家常问」：失败不阻塞主入口，静默降级为空列表。 */
  const loadHot = useCallback(async () => {
    const result = await getFaqs();
    if (!result.ok) {
      setHotFaqs([]);
      return;
    }
    setHotFaqs(result.data.slice(0, HOT_FAQ_LIMIT));
  }, []);

  useEffect(() => {
    void load();
    void loadHot();
  }, [load, loadHot]);

  // 页面可能长时间停留：每分钟重算一次「现在是否在服务时段」。
  useEffect(() => {
    const timer = setInterval(() => {
      setNow(new Date());
    }, CLOCK_REFRESH_MS);
    return () => {
      clearInterval(timer);
    };
  }, []);

  /**
   * 拨号。号码为空时不拨，给一句提示而不是让按钮没反应。
   *
   * @param phone 服务端返回的客服电话
   */
  const handleCall = useCallback(
    (phone: string) => {
      if (phone === '') {
        toast.show(t('ops.support.entryPhoneMissing'));
        return;
      }
      // 号码可能带分隔符，拨号串里只保留数字与前置 +。
      void Linking.openURL(`tel:${phone.replace(/[^\d+]/g, '')}`);
    },
    [t, toast],
  );

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('ops.support.title')} />
      <ScrollView contentContainerStyle={styles.scroll}>
        <AsyncBoundary state={state} onRetry={() => void load()} loadingRows={3}>
          {(config) => {
            const online = isWithinServiceHours(config.onlineHours, now);
            const hours = config.onlineHours === '' ? t('ops.support.hoursFallback') : config.onlineHours;
            return (
              <View style={styles.body}>
                <LocalizedText numberOfLines={3} style={styles.lead}>
                  {t('ops.support.lead')}
                </LocalizedText>

                <View style={styles.entryList}>
                  {/* 入口 1：常见问题 —— 面积最大、排最上 */}
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={t('ops.support.entryFaq')}
                    onPress={() => {
                      router.push(PAGE_ROUTES.faq);
                    }}
                    style={({ pressed }) => [styles.entry, pressed ? styles.pressed : null]}
                  >
                    <View style={[styles.entryIcon, { backgroundColor: ENTRY_STYLE.faq.background }]}>
                      <Ionicons name={ENTRY_STYLE.faq.icon} size={22} color={ENTRY_STYLE.faq.tint} />
                    </View>
                    <View style={styles.entryBody}>
                      <LocalizedText numberOfLines={1} style={styles.entryTitle}>
                        {t('ops.support.entryFaq')}
                      </LocalizedText>
                      <LocalizedText numberOfLines={2} style={styles.entryDesc}>
                        {t('ops.support.entryFaqDesc')}
                      </LocalizedText>
                    </View>
                    <Ionicons name="chevron-forward" size={16} color={color.text.muted} />
                  </Pressable>

                  {/* 入口 2：在线客服 —— 本段只做「提示 + 留言」，不做 IM */}
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={t('ops.support.entryChat')}
                    onPress={() => {
                      if (online) {
                        toast.show(
                          t('ops.support.entryChatDescAway') + ' ' + t('ops.support.entryPhone') + ' ' + config.phone,
                        );
                        return;
                      }
                      router.push(feedbackHref('support'));
                    }}
                    style={({ pressed }) => [styles.entry, pressed ? styles.pressed : null]}
                  >
                    <View style={[styles.entryIcon, { backgroundColor: ENTRY_STYLE.chat.background }]}>
                      <Ionicons name={ENTRY_STYLE.chat.icon} size={22} color={ENTRY_STYLE.chat.tint} />
                    </View>
                    <View style={styles.entryBody}>
                      <View style={styles.entryTitleRow}>
                        <LocalizedText numberOfLines={1} style={styles.entryTitle}>
                          {t('ops.support.entryChat')}
                        </LocalizedText>
                        <View style={styles.liveDot} />
                        <LocalizedText numberOfLines={1} style={styles.liveText}>
                          {online ? t('ops.support.entryLive') : t('ops.support.entryAway')}
                        </LocalizedText>
                      </View>
                      <LocalizedText numberOfLines={2} style={styles.entryDesc}>
                        {online
                          ? t('ops.support.entryChatDesc', { hours })
                          : t('ops.support.entryChatDescAway')}
                      </LocalizedText>
                    </View>
                    <Ionicons name="chevron-forward" size={16} color={color.text.muted} />
                  </Pressable>

                  {/* 入口 3：客服热线 —— 做成可点拨号，不是「仅展示号码」 */}
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={t('ops.support.entryPhone')}
                    onPress={() => {
                      handleCall(config.phone);
                    }}
                    style={({ pressed }) => [styles.entry, pressed ? styles.pressed : null]}
                  >
                    <View style={[styles.entryIcon, { backgroundColor: ENTRY_STYLE.phone.background }]}>
                      <Ionicons name={ENTRY_STYLE.phone.icon} size={22} color={ENTRY_STYLE.phone.tint} />
                    </View>
                    <View style={styles.entryBody}>
                      <LocalizedText numberOfLines={1} style={styles.entryTitle}>
                        {t('ops.support.entryPhone')}
                      </LocalizedText>
                      <LocalizedText numberOfLines={1} style={styles.entryPhone}>
                        {config.phone === '' ? t('ops.support.entryPhoneMissing') : config.phone}
                      </LocalizedText>
                      <LocalizedText numberOfLines={2} style={styles.entryDesc}>
                        {t('ops.support.entryPhoneDesc', { hours: PHONE_HOURS })}
                      </LocalizedText>
                    </View>
                    <Ionicons name="chevron-forward" size={16} color={color.text.muted} />
                  </Pressable>
                </View>

                <SectionTitle text={t('ops.support.hotTitle')} style={styles.hotTitle} />

                <View style={styles.hotList}>
                  {hotFaqs.length === 0 ? (
                    <LocalizedText numberOfLines={2} style={styles.hotEmpty}>
                      {t('ops.support.hotEmpty')}
                    </LocalizedText>
                  ) : (
                    hotFaqs.map((item, index) => (
                      <Pressable
                        key={item.id}
                        accessibilityRole="button"
                        accessibilityLabel={item.question}
                        onPress={() => {
                          router.push(PAGE_ROUTES.faq);
                        }}
                        style={({ pressed }) => [
                          styles.hot,
                          index === hotFaqs.length - 1 ? styles.hotLast : null,
                          pressed ? styles.hotPressed : null,
                        ]}
                      >
                        <LocalizedText numberOfLines={1} style={styles.hotText}>
                          {item.question}
                        </LocalizedText>
                        <Ionicons name="chevron-forward" size={15} color={color.text.muted} />
                      </Pressable>
                    ))
                  )}
                </View>

                <LocalizedText numberOfLines={5} style={styles.note}>
                  {t('ops.support.note')}
                </LocalizedText>
              </View>
            );
          }}
        </AsyncBoundary>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    paddingBottom: spacing.xxxl,
  },
  body: {
    gap: 0,
  },
  lead: {
    ...typography.body,
    color: color.text.secondary,
    paddingVertical: spacing.xs,
    marginBottom: spacing.md,
  },
  entryList: {
    gap: spacing.md,
  },
  entry: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    ...shadow.card,
  },
  entryIcon: {
    width: 42,
    height: 42,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
  },
  entryBody: {
    flex: 1,
    gap: spacing.xxs,
  },
  entryTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  entryTitle: {
    ...typography.h3,
    color: color.text.primary,
  },
  entryDesc: {
    ...typography.caption,
    color: color.text.muted,
  },
  entryPhone: {
    ...typography.h2,
    color: color.text.primary,
  },
  liveDot: {
    width: 6,
    height: 6,
    borderRadius: radius.pill,
    backgroundColor: color.state.success,
  },
  liveText: {
    ...typography.micro,
    color: color.state.success,
  },
  hotTitle: {
    paddingTop: spacing.xl,
    paddingBottom: spacing.sm,
  },
  hotList: {
    borderRadius: radius.md,
    backgroundColor: color.background.surface,
    overflow: 'hidden',
  },
  hot: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    minHeight: 52,
    paddingVertical: 13,
    paddingHorizontal: spacing.lg,
    borderBottomWidth: size.hairline,
    borderBottomColor: color.border.divider,
  },
  hotLast: {
    borderBottomWidth: 0,
  },
  hotPressed: {
    backgroundColor: color.background.canvas,
  },
  hotText: {
    ...typography.body,
    color: color.text.body,
    flex: 1,
  },
  hotEmpty: {
    ...typography.caption,
    color: color.text.muted,
    padding: spacing.lg,
  },
  note: {
    ...typography.caption,
    color: color.text.muted,
    lineHeight: 21,
    marginTop: spacing.lg,
    paddingHorizontal: spacing.xxs,
  },
  pressed: {
    opacity: 0.7,
  },
});
