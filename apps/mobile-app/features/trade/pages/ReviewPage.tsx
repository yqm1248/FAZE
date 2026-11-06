/**
 * 评价页（段 2 G5.1，设计稿 `34-review.html`）。
 *
 * 职责：对已完成的订单给律师打分（星级必填）、选标签（可多选）、写正文（选填）、决定是否匿名，
 * 提交后切换为「我的评价」只读态；本单已评价过时直接进只读态。
 * 调用方：路由壳 `app/review.tsx`；入口在 `OrderDetailPage` 的「评价服务 / 查看评价」动作。
 * 数据来源：`GET /api/v1/app/orders/{id}`（律师与服务信息）、
 *          `GET|POST /api/v1/app/orders/{id}/review`（评价读写）。
 * 注意：
 *   1. **星级初始为 0，不预填 5 星**（设计稿明确）：预填高分等于用界面替用户表态。
 *      未打分时底部「发布评价」置灰不可点。
 *   2. **只有星级必填**，标签与正文都能空：评价门槛越低，拿到的真实评价越多。
 *   3. **提交给服务端的标签是固定中文业务口径**（不随界面语言变）：后台统计与律师主页按同一套值聚合，
 *      界面文案仍走 `t()` 按当前语言显示（与退款原因同一处理方式）。
 *   4. **发布后不能修改/删除**：页面在提交前就把这条说清楚（设计稿的 review-tip）。
 *      因此页面没有「编辑」入口，已评价只读展示。
 *   5. 服务端在提交的同一事务里重算律师评分（G5.2），前端**不缓存、不自行累加**。
 */

import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { getOrderDetail } from '@/features/trade/api/orders';
import type { OrderDetail } from '@/features/trade/api/orders';
import { getOrderReview, submitReview } from '@/features/trade/api/reviews';
import type { ReviewRecord, ReviewSubmitPayload } from '@/features/trade/api/reviews';
import type { AppError } from '@/shared/api/types';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { ErrorState } from '@/shared/components/ErrorState';
import { FormField } from '@/shared/components/FormField';
import { LoadingState } from '@/shared/components/LoadingState';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { LtrText } from '@/shared/components/LtrText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { TagChip } from '@/shared/components/TagChip';
import { ToggleRow } from '@/shared/components/ToggleRow';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { orderDetailHref } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 星级可选值。 */
const STARS: ReadonlyArray<number> = [1, 2, 3, 4, 5];

/** 星级 → 文案 key（随语言变化）。 */
const STAR_LABEL_KEYS: Record<number, MessageKey> = {
  1: 'trade.review.star1',
  2: 'trade.review.star2',
  3: 'trade.review.star3',
  4: 'trade.review.star4',
  5: 'trade.review.star5',
};

/**
 * 预设标签：显示走 i18n，提交给服务端的是固定中文业务口径（同退款原因的处理）。
 */
const TAGS: ReadonlyArray<{ key: string; labelKey: MessageKey; value: string }> = [
  { key: 'professional', labelKey: 'trade.review.tagProfessional', value: '专业能力强' },
  { key: 'reply', labelKey: 'trade.review.tagReply', value: '回复及时' },
  { key: 'patient', labelKey: 'trade.review.tagPatient', value: '态度耐心' },
  { key: 'clear', labelKey: 'trade.review.tagClear', value: '讲解清楚' },
  { key: 'transparent', labelKey: 'trade.review.tagTransparent', value: '收费透明' },
  { key: 'practical', labelKey: 'trade.review.tagPractical', value: '建议实用' },
];

/** 正文长度上限：设计稿 34-review.html 的 maxlength=200（服务端上限 500，更严在前端）。 */
const CONTENT_MAX = 200;

/** 服务类型 → 文案 key（与 OrderDetailPage 的映射同源；两页各自持有一份，避免页面互相 import）。 */
const SERVICE_KEYS: Record<string, MessageKey> = {
  consult_text: 'trade.order.serviceConsultText',
  consult_phone: 'trade.order.serviceConsultPhone',
  document: 'trade.order.serviceDocument',
  case: 'trade.order.serviceCase',
};

/**
 * 评价页。
 *
 * 副作用：挂载时并行拉订单详情与已有评价；提交成功后切到只读态。
 *
 * @returns 评价表单或我的评价（只读）
 */
export function ReviewPage() {
  const params = useLocalSearchParams<{ id?: string }>();
  const orderId = typeof params.id === 'string' ? params.id : '';
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const actionBarSpace = useActionBarSpace();

  const [phase, setPhase] = useState<'loading' | 'error' | 'ready'>('loading');
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [existing, setExisting] = useState<ReviewRecord | null>(null);
  const [error, setError] = useState<AppError | null>(null);

  const [rating, setRating] = useState(0);
  const [selectedTags, setSelectedTags] = useState<string[]>([]);
  const [content, setContent] = useState('');
  const [anonymous, setAnonymous] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  /** 拉订单与已有评价。 */
  const load = useCallback(async (): Promise<void> => {
    setPhase('loading');
    const [orderResult, reviewResult] = await Promise.all([
      getOrderDetail(orderId),
      getOrderReview(orderId),
    ]);
    if (!orderResult.ok) {
      setError(orderResult.error);
      setPhase('error');
      return;
    }
    if (!reviewResult.ok) {
      setError(reviewResult.error);
      setPhase('error');
      return;
    }
    setError(null);
    setOrder(orderResult.data);
    setExisting(reviewResult.data);
    setPhase('ready');
  }, [orderId]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 切换一个标签。 */
  const toggleTag = (value: string): void => {
    setSelectedTags((current) =>
      current.includes(value) ? current.filter((item) => item !== value) : [...current, value],
    );
  };

  /** 提交评价。 */
  const submit = async (): Promise<void> => {
    if (rating < 1) {
      toast.show(t('trade.review.ratingMissing'));
      return;
    }
    const payload: ReviewSubmitPayload = {
      rating,
      tags: selectedTags,
      isAnonymous: anonymous,
    };
    const trimmed = content.trim();
    if (trimmed !== '') {
      payload.content = trimmed;
    }
    setSubmitting(true);
    try {
      const result = await submitReview(orderId, payload);
      if (!result.ok) {
        // msg 不展示（接口规范 §5.1），按 msgKey 走 t()
        toast.show(t(result.error.msgKey as MessageKey));
        return;
      }
      toast.show(t('trade.review.submitted'));
      setExisting(result.data);
    } finally {
      setSubmitting(false);
    }
  };

  /** 本单能否评价：完成态 + 尚未评价。 */
  const canReview = order !== null && existing === null && order.status === 'completed';

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader
        title={existing === null ? t('trade.review.title') : t('trade.review.doneTitle')}
      />

      {phase === 'loading' ? (
        <View style={styles.pad}>
          <LoadingState rows={4} />
        </View>
      ) : null}

      {phase === 'error' && error !== null ? (
        <View style={styles.pad}>
          <ErrorState
            error={error}
            onRetry={() => {
              void load();
            }}
          />
        </View>
      ) : null}

      {phase === 'ready' && order !== null ? (
        <ScrollView contentContainerStyle={[styles.body, { paddingBottom: actionBarSpace }]}>
          <View style={styles.hero}>
            <View style={styles.avatar}>
              <LocalizedText numberOfLines={1} style={styles.avatarText}>
                {(order.lawyerName ?? '法').slice(0, 1)}
              </LocalizedText>
            </View>
            <LocalizedText numberOfLines={2} style={styles.heroName}>
              {order.lawyerName ?? t('trade.order.lawyerMissing')}
            </LocalizedText>
            <LocalizedText numberOfLines={2} style={styles.heroMeta}>
              {`${t(SERVICE_KEYS[order.serviceType] ?? 'trade.order.serviceUnknown')} · ${formatServerDateTime(
                order.finishedAt ?? order.createdAt,
              )}`}
            </LocalizedText>
          </View>

          {existing === null ? (
            <>
              <AppCard>
                <View style={styles.cardHead}>
                  <LocalizedText numberOfLines={1} style={styles.cardTitle}>
                    {t('trade.review.sectionRating')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.cardNote}>
                    {t('trade.review.noteRequired')}
                  </LocalizedText>
                </View>
                <View style={styles.stars}>
                  {STARS.map((star) => {
                    const on = star <= rating;
                    return (
                      <Pressable
                        key={star}
                        onPress={() => setRating(star)}
                        accessibilityRole="button"
                        accessibilityLabel={t(STAR_LABEL_KEYS[star] ?? 'trade.review.starHint')}
                        accessibilityState={{ selected: on }}
                        style={styles.star}
                      >
                        <Ionicons
                          name={on ? 'star' : 'star-outline'}
                          size={32}
                          color={on ? color.state.warning : color.border.outline}
                        />
                      </Pressable>
                    );
                  })}
                </View>
                <LocalizedText numberOfLines={2} style={styles.starLabel}>
                  {rating === 0 ? t('trade.review.starHint') : t(STAR_LABEL_KEYS[rating] ?? 'trade.review.starHint')}
                </LocalizedText>
              </AppCard>

              <AppCard>
                <View style={styles.cardHead}>
                  <LocalizedText numberOfLines={2} style={styles.cardTitle}>
                    {t('trade.review.sectionTags')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.cardNote}>
                    {t('trade.review.noteTagOptional')}
                  </LocalizedText>
                </View>
                <View style={styles.tags}>
                  {TAGS.map((tag) => {
                    const active = selectedTags.includes(tag.value);
                    return (
                      <Pressable
                        key={tag.key}
                        onPress={() => toggleTag(tag.value)}
                        accessibilityRole="checkbox"
                        accessibilityLabel={t(tag.labelKey)}
                        accessibilityState={{ checked: active }}
                        style={styles.tag}
                      >
                        <TagChip
                          label={t(tag.labelKey)}
                          variant={active ? 'brand' : 'outline'}
                          size="md"
                        />
                      </Pressable>
                    );
                  })}
                </View>
              </AppCard>

              <AppCard>
                <View style={styles.cardHead}>
                  <LocalizedText numberOfLines={1} style={styles.cardTitle}>
                    {t('trade.review.sectionContent')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={1} style={styles.cardNote}>
                    {t('trade.review.noteContentOptional')}
                  </LocalizedText>
                </View>
                <FormField
                  label={t('trade.review.contentLabel')}
                  value={content}
                  onChangeText={setContent}
                  placeholder={t('trade.review.contentPlaceholder')}
                  maxLength={CONTENT_MAX}
                  multiline
                />
                <LtrText numberOfLines={1} style={styles.counter}>
                  {`${content.length}/${CONTENT_MAX}`}
                </LtrText>
              </AppCard>

              <ToggleRow
                label={t('trade.review.anonymous')}
                description={t('trade.review.anonymousDesc')}
                value={anonymous}
                onChange={setAnonymous}
              />

              <LocalizedText numberOfLines={4} style={styles.tip}>
                {t('trade.review.tip')}
              </LocalizedText>

              {canReview ? null : (
                <LocalizedText numberOfLines={3} style={styles.tip}>
                  {t('trade.review.notApplicable')}
                </LocalizedText>
              )}
            </>
          ) : (
            <ReviewView review={existing} />
          )}

          <PrimaryButton
            label={t('trade.review.backToOrder')}
            variant="text"
            onPress={() => router.replace(orderDetailHref(orderId))}
          />
        </ScrollView>
      ) : null}

      {phase === 'ready' && canReview ? (
        <ActionBar>
          <PrimaryButton
            label={submitting ? t('trade.review.submitting') : t('trade.review.submit')}
            onPress={() => {
              void submit();
            }}
            disabled={submitting || rating < 1}
          />
        </ActionBar>
      ) : null}
    </Screen>
  );
}

/**
 * 已评价的只读展示。
 *
 * @param props.review 评价
 * @returns 星级 + 标签 + 正文 + 律师回复
 */
function ReviewView({ review }: { review: ReviewRecord }) {
  const { t } = useI18n();
  return (
    <>
      <AppCard>
        <View style={styles.cardHead}>
          <LocalizedText numberOfLines={1} style={styles.cardTitle}>
            {t('trade.review.sectionRating')}
          </LocalizedText>
          <LocalizedText numberOfLines={1} style={styles.cardNote}>
            {formatServerDateTime(review.createdAt)}
          </LocalizedText>
        </View>
        <View style={styles.stars}>
          {STARS.map((star) => (
            <View key={star} style={styles.star}>
              <Ionicons
                name={star <= review.rating ? 'star' : 'star-outline'}
                size={32}
                color={star <= review.rating ? color.state.warning : color.border.outline}
              />
            </View>
          ))}
        </View>
        <LocalizedText numberOfLines={2} style={styles.starLabel}>
          {t(STAR_LABEL_KEYS[review.rating] ?? 'trade.review.starHint')}
        </LocalizedText>
        {review.anonymous ? (
          <LocalizedText numberOfLines={1} style={styles.anonymousBadge}>
            {t('trade.review.anonymousBadge')}
          </LocalizedText>
        ) : null}
      </AppCard>

      {review.tags.length === 0 ? null : (
        <AppCard>
          <View style={styles.tags}>
            {review.tags.map((tag) => (
              <TagChip key={tag} label={tag} variant="soft" size="md" />
            ))}
          </View>
        </AppCard>
      )}

      {review.content === null ? null : (
        <AppCard>
          <LocalizedText numberOfLines={8} style={styles.reviewContent}>
            {review.content}
          </LocalizedText>
        </AppCard>
      )}

      {review.lawyerReply === null ? null : (
        <AppCard style={styles.replyCard}>
          <LocalizedText numberOfLines={1} style={styles.cardTitle}>
            {t('trade.review.lawyerReply')}
          </LocalizedText>
          <LocalizedText numberOfLines={8} style={styles.reviewContent}>
            {review.lawyerReply}
          </LocalizedText>
        </AppCard>
      )}
    </>
  );
}

const styles = StyleSheet.create({
  pad: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
  },
  body: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.sm,
    gap: spacing.md,
  },
  hero: {
    alignItems: 'center',
    paddingVertical: spacing.md,
    gap: spacing.xs,
  },
  avatar: {
    width: 56,
    height: 56,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: color.brand.primarySoft,
  },
  avatarText: {
    ...typography.h2,
    color: color.brand.primary,
  },
  heroName: {
    ...typography.h3,
    color: color.text.primary,
    textAlign: 'center',
  },
  heroMeta: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
  },
  cardHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  cardTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
    flexShrink: 1,
  },
  cardNote: {
    ...typography.caption,
    color: color.text.muted,
  },
  stars: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  star: {
    // 触控区 45（≥44 最小点击区，设计稿要求 44×44），星形图标 32 居中
    width: size.inputHeight,
    height: size.inputHeight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  starLabel: {
    ...typography.bodySmall,
    color: color.text.secondary,
    textAlign: 'center',
    marginTop: spacing.xs,
  },
  tags: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginTop: spacing.sm,
  },
  tag: {
    minHeight: size.inputHeight,
    justifyContent: 'center',
  },
  counter: {
    ...typography.caption,
    color: color.text.muted,
    // RN 的 textAlign 没有 end；auto 跟随语言方向（RTL 下自动靠右）
    textAlign: 'auto',
    marginTop: spacing.xs,
  },
  tip: {    ...typography.caption,
    color: color.text.muted,
    lineHeight: 22,
    paddingHorizontal: spacing.xs,
  },
  anonymousBadge: {
    ...typography.caption,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.xs,
  },
  reviewContent: {
    ...typography.body,
    color: color.text.body,
    marginTop: spacing.sm,
  },
  replyCard: {
    backgroundColor: color.background.canvas,
  },
});
