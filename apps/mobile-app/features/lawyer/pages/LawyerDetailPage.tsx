/**
 * 律师详情页。
 *
 * 职责：G3.2 的律师详情——资料卡 + 业务擅长 + 个人简介 + 服务与价格 + 用户评价 +
 * 关注 + 「立即咨询」下单入口。
 * 调用方：路由壳 `app/lawyer-detail.tsx`；从律师列表点卡片进来。
 * 数据来源：`GET /app/lawyers/{id}`、`GET /app/lawyers/{id}/reviews`、
 *          `POST/DELETE /app/lawyers/{id}/follow`、`POST /app/orders`（下单）。
 * 注意：
 *   1. **设计稿 `14-lawyer-detail.html` 只画了资料 + 擅长 + 简介 + 联系方式**，没有价格区与评价区；
 *      但工作流 G3.2 明确要求「价格」与「评价列表分页正确」（后者是 `2-A4` 的判据），
 *      所以本页按工作流补了这两块，视觉沿用同一套卡片范式。**偏差已登记**。
 *   2. **「立即咨询」= 下单，不是进会话**：工作流拍板第 6 项「下单与会话解耦」，
 *      进 IM 是 `POST /app/conversations`（G6.1）的事。设计稿的 `href="17-chat.html"`
 *      是原型阶段的简化。
 *   3. 下单金额**不在前端算**：只传 `lawyerId` + `serviceType`，服务端按律师价格取价。
 *   4. 未上架的服务（`serviceTextOn=false` 等）不给下单入口：后端还会再拦一次（405009）。
 *   5. 匿名评价的昵称由前端 `t()` 显示「匿名用户」，服务端传 null。
 */

import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import {
  followLawyer,
  getLawyerDetail,
  getLawyerReviews,
  unfollowLawyer,
} from '@/features/lawyer/api/lawyers';
import type { LawyerDetail, LawyerReview } from '@/features/lawyer/api/lawyers';
import { createOrder } from '@/features/trade/api/orders';
import { ActionBar, useActionBarSpace } from '@/shared/components/ActionBar';
import { AppCard } from '@/shared/components/AppCard';
import { AppHeader } from '@/shared/components/AppHeader';
import { useToast } from '@/shared/components/AppToast';
import { AsyncBoundary } from '@/shared/components/AsyncBoundary';
import { LocalizedText } from '@/shared/components/LocalizedText';
import { MoneyText } from '@/shared/components/MoneyText';
import { PrimaryButton } from '@/shared/components/PrimaryButton';
import { Screen } from '@/shared/components/Screen';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { useAsyncResource } from '@/shared/hooks/useAsyncResource';
import { useRefreshOnFocus } from '@/shared/hooks/useRefreshOnFocus';
import { payHref } from '@/shared/navigation/routes';
import { color, radius, size, spacing, typography } from '@/shared/theme';
import { centsFromServer } from '@/shared/utils/money';
import { formatServerDateTime } from '@/shared/utils/serverTime';

/** 可下单的服务类型（与后端 ServiceType 对应）。 */
type OrderableService = 'consult_text' | 'consult_phone' | 'document';

/** 服务定义：类型、名称 key、价格字段、上架开关字段。 */
const SERVICES: ReadonlyArray<{
  type: OrderableService;
  labelKey: MessageKey;
  priceKey: 'consultPriceCents' | 'phonePriceCents' | 'documentPriceCents';
  switchKey: 'serviceTextOn' | 'servicePhoneOn' | 'serviceDocOn';
}> = [
  {
    type: 'consult_text',
    labelKey: 'lawyer.detail.serviceConsultText',
    priceKey: 'consultPriceCents',
    switchKey: 'serviceTextOn',
  },
  {
    type: 'consult_phone',
    labelKey: 'lawyer.detail.serviceConsultPhone',
    priceKey: 'phonePriceCents',
    switchKey: 'servicePhoneOn',
  },
  {
    type: 'document',
    labelKey: 'lawyer.detail.serviceDocument',
    priceKey: 'documentPriceCents',
    switchKey: 'serviceDocOn',
  },
];

/** 单页评价条数。 */
const REVIEW_PAGE_SIZE = 5;

/**
 * 渲染一条评价。
 *
 * @param props.review 评价行
 * @returns 评价卡片内容
 */
function ReviewItem({ review }: { review: LawyerReview }) {
  const { t } = useI18n();
  return (
    <View style={styles.review}>
      <View style={styles.reviewHead}>
        <LocalizedText numberOfLines={1} style={styles.reviewUser}>
          {review.anonymous ? t('lawyer.detail.anonymous') : review.userNickname ?? t('lawyer.detail.anonymous')}
        </LocalizedText>
        <LocalizedText numberOfLines={1} style={styles.reviewTime}>
          {formatServerDateTime(review.createdAt)}
        </LocalizedText>
      </View>
      <View style={styles.reviewStars}>
        {[1, 2, 3, 4, 5].map((star) => (
          <Ionicons
            key={star}
            name={star <= review.rating ? 'star' : 'star-outline'}
            size={12}
            color={star <= review.rating ? color.state.warning : color.border.outline}
          />
        ))}
      </View>
      {review.tags.length === 0 ? null : (
        <View style={styles.reviewTags}>
          {review.tags.map((tag) => (
            <View key={tag} style={styles.reviewTag}>
              <LocalizedText numberOfLines={1} style={styles.reviewTagText}>
                {tag}
              </LocalizedText>
            </View>
          ))}
        </View>
      )}
      {review.content === null ? null : (
        <LocalizedText numberOfLines={6} style={styles.reviewContent}>
          {review.content}
        </LocalizedText>
      )}
      {review.lawyerReply === null ? null : (
        <View style={styles.reply}>
          <LocalizedText numberOfLines={4} style={styles.replyText}>
            {t('lawyer.detail.replyPrefix') + review.lawyerReply}
          </LocalizedText>
        </View>
      )}
    </View>
  );
}

/**
 * 律师详情页。
 *
 * 副作用：挂载拉详情与评价首页；关注/取关、下单都会写服务端。
 *
 * @returns 律师资料 + 评价 + 底部操作栏
 */
export function LawyerDetailPage() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const params = useLocalSearchParams<{ id?: string }>();
  const lawyerId = typeof params.id === 'string' ? params.id : '';
  const barSpace = useActionBarSpace();
  const resource = useAsyncResource<LawyerDetail>(() => getLawyerDetail(lawyerId), {
    enabled: lawyerId !== '',
  });
  // 从下单 / 会话返回时静默刷新资料与关注状态（不闪骨架）。
  useRefreshOnFocus(resource.reloadSilently);
  const [reviews, setReviews] = useState<LawyerReview[]>([]);
  const [reviewTotal, setReviewTotal] = useState(0);
  const [reviewPage, setReviewPage] = useState(0);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [following, setFollowing] = useState(false);
  const [followBusy, setFollowBusy] = useState(false);
  const [orderBusy, setOrderBusy] = useState(false);

  // 详情加载成功后同步一次关注状态（服务端已返回 followed）
  useEffect(() => {
    if (resource.state.phase === 'success') {
      setFollowing(resource.state.data.followed);
    }
  }, [resource.state]);

  /**
   * 拉一页评价（追加）。
   *
   * @param targetPage 页码（从 1 开始）
   */
  const loadReviews = useCallback(
    async (targetPage: number): Promise<void> => {
      if (lawyerId === '') {
        return;
      }
      setReviewLoading(true);
      try {
        const result = await getLawyerReviews(lawyerId, targetPage, REVIEW_PAGE_SIZE);
        if (!result.ok) {
          return;
        }
        setReviewTotal(result.data.total);
        setReviewPage(result.data.pageNum);
        setReviews((prev) => (targetPage === 1 ? result.data.list : [...prev, ...result.data.list]));
      } finally {
        setReviewLoading(false);
      }
    },
    [lawyerId],
  );

  useEffect(() => {
    void loadReviews(1);
  }, [loadReviews]);

  /**
   * 关注 / 取关。
   *
   * 副作用：写 social_action 并切换按钮态。
   */
  const toggleFollow = async (): Promise<void> => {
    if (followBusy) {
      return;
    }
    setFollowBusy(true);
    try {
      const result = following ? await unfollowLawyer(lawyerId) : await followLawyer(lawyerId);
      if (!result.ok) {
        toast.show(t('error.error.auth.token-invalid' as MessageKey));
        return;
      }
      setFollowing(!following);
      toast.show(t(following ? 'lawyer.detail.followOff' : 'lawyer.detail.followOn'));
    } finally {
      setFollowBusy(false);
    }
  };

  /**
   * 下单（立即咨询）。
   *
   * 副作用：创建 PENDING 订单成功后跳到支付页。
   *
   * @param serviceType 服务类型
   */
  const startOrder = async (serviceType: OrderableService): Promise<void> => {
    if (orderBusy) {
      return;
    }
    setOrderBusy(true);
    try {
      // 幂等键：一次点击一个 UUID，服务端据此保证连点只产生一单
      const idempotencyKey = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
      const result = await createOrder({ lawyerId, serviceType }, idempotencyKey);
      if (!result.ok) {
        if (result.error.kind === 'business' && result.error.code === 402003) {
          toast.show(t('lawyer.detail.loginRequired'));
          return;
        }
        toast.show(t('trade.payResult.reasonUnknown'));
        return;
      }
      toast.show(t('lawyer.detail.orderCreated'));
      router.push(payHref(result.data.id));
    } finally {
      setOrderBusy(false);
    }
  };

  return (
    <Screen edges={['top', 'left', 'right', 'bottom']}>
      <AppHeader title={t('lawyer.browse.title')} />
      <AsyncBoundary state={resource.state} onRetry={resource.reload}>
        {(lawyer) => {
          const rating = lawyer.rating === null ? null : Number(lawyer.rating);
          const ratingText = rating === null || Number.isNaN(rating) ? null : rating.toFixed(1);
          const hasMoreReviews = reviews.length < reviewTotal;
          return (
            <>
              <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: barSpace }]}>
                <AppCard style={styles.profileCard}>
                  <View style={styles.profileTop}>
                    <View style={styles.avatar}>
                      <LocalizedText numberOfLines={1} style={styles.avatarText}>
                        {(lawyer.realName ?? t('lawyer.browse.defaultName')).slice(0, 1)}
                      </LocalizedText>
                    </View>
                    <View style={styles.profileIntro}>
                      <LocalizedText numberOfLines={1} style={styles.profileName}>
                        {lawyer.realName}
                      </LocalizedText>
                      <LocalizedText numberOfLines={1} style={styles.profileFirm}>
                        {lawyer.lawFirm ?? t('lawyer.detail.emptyValue')}
                      </LocalizedText>
                      <LocalizedText numberOfLines={1} style={styles.profileRegion}>
                        {lawyer.region ?? t('lawyer.detail.emptyValue')}
                      </LocalizedText>
                    </View>
                  </View>
                  <View style={styles.stats}>
                    <View style={styles.stat}>
                      <LocalizedText numberOfLines={1} style={styles.statValue}>
                        {lawyer.practiceYears === null
                          ? t('lawyer.detail.emptyValue')
                          : `${lawyer.practiceYears}${t('lawyer.detail.yearsUnit')}`}
                      </LocalizedText>
                      <LocalizedText numberOfLines={1} style={styles.statLabel}>
                        {t('lawyer.detail.statYears')}
                      </LocalizedText>
                    </View>
                    <View style={styles.stat}>
                      <LocalizedText numberOfLines={1} style={styles.statValue}>
                        {ratingText === null ? t('lawyer.detail.emptyValue') : ratingText}
                      </LocalizedText>
                      <LocalizedText numberOfLines={1} style={styles.statLabel}>
                        {t('lawyer.detail.statRating')}
                      </LocalizedText>
                    </View>
                    <View style={styles.stat}>
                      <LocalizedText numberOfLines={1} style={styles.statValue}>
                        {String(lawyer.orderCount ?? 0)}
                      </LocalizedText>
                      <LocalizedText numberOfLines={1} style={styles.statLabel}>
                        {t('lawyer.detail.statConsulted')}
                      </LocalizedText>
                    </View>
                  </View>
                </AppCard>

                <AppCard style={styles.block}>
                  <LocalizedText numberOfLines={1} style={styles.blockTitle}>
                    {t('lawyer.detail.skills')}
                  </LocalizedText>
                  <View style={styles.skillRow}>
                    {lawyer.specialties.length === 0 ? (
                      <LocalizedText numberOfLines={1} style={styles.emptyText}>
                        {t('lawyer.detail.emptyValue')}
                      </LocalizedText>
                    ) : (
                      lawyer.specialties.map((skill) => (
                        <View key={skill} style={styles.skill}>
                          <LocalizedText numberOfLines={1} style={styles.skillText}>
                            {skill}
                          </LocalizedText>
                        </View>
                      ))
                    )}
                  </View>
                </AppCard>

                <AppCard style={styles.block}>
                  <LocalizedText numberOfLines={1} style={styles.blockTitle}>
                    {t('lawyer.detail.bio')}
                  </LocalizedText>
                  <LocalizedText numberOfLines={20} style={styles.bioText}>
                    {lawyer.bio ?? t('lawyer.detail.bioEmpty')}
                  </LocalizedText>
                </AppCard>

                <AppCard style={styles.block}>
                  <LocalizedText numberOfLines={1} style={styles.blockTitle}>
                    {t('lawyer.detail.services')}
                  </LocalizedText>
                  {SERVICES.map((service) => {
                    const on = lawyer[service.switchKey] === true;
                    const priceRaw = lawyer[service.priceKey];
                    const price = priceRaw === null ? null : centsFromServer(priceRaw);
                    const usable = on && price !== null && price > 0;
                    return (
                      <View key={service.type} style={styles.serviceRow}>
                        <LocalizedText numberOfLines={1} style={styles.serviceName}>
                          {t(service.labelKey)}
                        </LocalizedText>
                        {usable ? (
                          <MoneyText cents={price} style={styles.servicePrice} />
                        ) : (
                          <LocalizedText numberOfLines={1} style={styles.serviceOff}>
                            {t('lawyer.detail.priceUnavailable')}
                          </LocalizedText>
                        )}
                        <Pressable
                          onPress={() => {
                            if (usable) {
                              void startOrder(service.type);
                            }
                          }}
                          accessibilityRole="button"
                          accessibilityLabel={t('lawyer.detail.cta')}
                          disabled={!usable || orderBusy}
                          style={[styles.serviceCta, usable ? null : styles.serviceCtaOff]}
                        >
                          <LocalizedText numberOfLines={1} style={styles.serviceCtaText}>
                            {orderBusy ? t('lawyer.detail.ordering') : t('lawyer.detail.cta')}
                          </LocalizedText>
                        </Pressable>
                      </View>
                    );
                  })}
                </AppCard>

                <AppCard style={styles.block}>
                  <View style={styles.reviewTitleRow}>
                    <LocalizedText numberOfLines={1} style={styles.blockTitle}>
                      {t('lawyer.detail.reviews')}
                    </LocalizedText>
                    <LocalizedText numberOfLines={1} style={styles.reviewTotal}>
                      {`${reviewTotal}`}
                    </LocalizedText>
                  </View>
                  {reviews.length === 0 ? (
                    <View style={styles.reviewEmpty}>
                      <LocalizedText numberOfLines={1} style={styles.emptyText}>
                        {t('lawyer.detail.reviewsEmpty')}
                      </LocalizedText>
                      <LocalizedText numberOfLines={2} style={styles.reviewEmptyDesc}>
                        {t('lawyer.detail.reviewsEmptyDesc')}
                      </LocalizedText>
                    </View>
                  ) : (
                    reviews.map((review) => <ReviewItem key={review.id} review={review} />)
                  )}
                  {hasMoreReviews ? (
                    <PrimaryButton
                      label={
                        reviewLoading ? t('common.state.loading') : t('lawyer.detail.loadMoreReviews')
                      }
                      variant="outline"
                      onPress={() => {
                        void loadReviews(reviewPage + 1);
                      }}
                      disabled={reviewLoading}
                      style={styles.loadMoreReviews}
                    />
                  ) : reviews.length === 0 ? null : (
                    <LocalizedText numberOfLines={1} style={styles.noMore}>
                      {t('lawyer.detail.noMoreReviews')}
                    </LocalizedText>
                  )}
                </AppCard>
              </ScrollView>

              <ActionBar>
                <View style={styles.actionRow}>
                  <PrimaryButton
                    label={following ? t('lawyer.detail.followed') : t('lawyer.detail.follow')}
                    variant="outline"
                    onPress={() => {
                      void toggleFollow();
                    }}
                    disabled={followBusy}
                    style={styles.actionGhost}
                  />
                  <PrimaryButton
                    label={orderBusy ? t('lawyer.detail.ordering') : t('lawyer.detail.cta')}
                    onPress={() => {
                      void startOrder('consult_text');
                    }}
                    disabled={orderBusy}
                    style={styles.actionPrimary}
                  />
                </View>
              </ActionBar>
            </>
          );
        }}
      </AsyncBoundary>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: {
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.md,
    gap: spacing.md,
  },
  profileCard: {
    gap: spacing.md,
  },
  profileTop: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  avatar: {
    width: 56,
    height: 56,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {
    ...typography.h2,
    color: color.brand.primary,
  },
  profileIntro: {
    flex: 1,
    gap: spacing.xxs,
  },
  profileName: {
    ...typography.h2,
    color: color.text.primary,
  },
  profileFirm: {
    ...typography.caption,
    color: color.text.secondary,
  },
  profileRegion: {
    ...typography.micro,
    color: color.text.muted,
  },
  stats: {
    flexDirection: 'row',
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
    paddingTop: spacing.md,
  },
  stat: {
    flex: 1,
    alignItems: 'center',
    gap: spacing.xxs,
  },
  statValue: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  statLabel: {
    ...typography.micro,
    color: color.text.muted,
  },
  block: {
    gap: spacing.sm,
  },
  blockTitle: {
    ...typography.bodyLarge,
    color: color.text.primary,
  },
  skillRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
  },
  skill: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    backgroundColor: color.brand.primarySoft,
  },
  skillText: {
    ...typography.micro,
    color: color.brand.primary,
  },
  bioText: {
    ...typography.caption,
    color: color.text.secondary,
    lineHeight: 22,
  },
  emptyText: {
    ...typography.caption,
    color: color.text.muted,
  },
  serviceRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: spacing.sm,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
  },
  serviceName: {
    ...typography.caption,
    flex: 1,
    color: color.text.body,
  },
  servicePrice: {
    ...typography.caption,
    color: color.text.primary,
  },
  serviceOff: {
    ...typography.micro,
    color: color.text.muted,
  },
  serviceCta: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xxs,
    borderRadius: radius.pill,
    backgroundColor: color.brand.primary,
  },
  serviceCtaOff: {
    backgroundColor: color.background.input,
  },
  serviceCtaText: {
    ...typography.micro,
    color: color.text.onBrand,
  },
  reviewTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  reviewTotal: {
    ...typography.caption,
    color: color.text.muted,
  },
  review: {
    gap: spacing.xxs,
    paddingVertical: spacing.md,
    borderTopWidth: size.hairline,
    borderTopColor: color.border.divider,
  },
  reviewHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
  },
  reviewUser: {
    ...typography.caption,
    flex: 1,
    color: color.text.body,
  },
  reviewTime: {
    ...typography.micro,
    color: color.text.muted,
  },
  reviewStars: {
    flexDirection: 'row',
    gap: 2,
  },
  starOn: {
    ...typography.caption,
    color: color.state.warning,
  },
  starOff: {
    ...typography.caption,
    color: color.border.outline,
  },
  reviewTags: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
  },
  reviewTag: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  reviewTagText: {
    ...typography.micro,
    color: color.text.secondary,
  },
  reviewContent: {
    ...typography.caption,
    color: color.text.body,
    lineHeight: 21,
  },
  reply: {
    padding: spacing.sm,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
  },
  replyText: {
    ...typography.micro,
    color: color.text.secondary,
  },
  reviewEmpty: {
    alignItems: 'center',
    gap: spacing.xxs,
    paddingVertical: spacing.lg,
  },
  reviewEmptyDesc: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
  },
  loadMoreReviews: {
    marginTop: spacing.sm,
  },
  noMore: {
    ...typography.micro,
    color: color.text.muted,
    textAlign: 'center',
    marginTop: spacing.sm,
  },
  actionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  actionGhost: {
    flex: 1,
  },
  actionPrimary: {
    flex: 1,
  },
});
