/**
 * 订单状态胶囊。
 *
 * 职责：把 `orders.status` 的 8 个 code 映射成「文案 + 色调」，订单列表与订单详情共用。
 * 调用方：`features/trade/pages/OrderListPage.tsx`、`OrderDetailPage.tsx`。
 * 数据来源：后端返回的 `status` 字符串（`OrderStatus` 的 code）。
 * 注意：
 *   1. **文案走 i18n，不在组件里写中文**：四语言都要翻译（RTL 语言尤其不能漏）。
 *   2. 色调只表达状态语义：待支付/等待接单用主色、服务中用状态青绿、
 *      退款相关用警示橙、终态用灰。**不要用危险红**——退款中不是错误。
 *   3. 未知状态码（比如段 5 以后新增的 `disputed`）显示原始 code 而不是崩溃：
 *      老版本 App 遇到新状态必须还能打开页面。
 *   4. 退款中的浅橙底在令牌里没有对应项（`color.state.warning` 只有前景色），
 *      这里用 `background.input` 作底 + 橙字，视觉上略灰但与设计稿语义一致，
 *      已在段 2 证据里登记为偏差（不擅自给设计令牌加值：改令牌要同步三处）。
 */

import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { color, radius, spacing, typography } from '@/shared/theme';

/** 状态胶囊的色调。 */
type PillTone = 'brand' | 'success' | 'warning' | 'muted';

/** 状态 code → 色调。 */
const TONE_BY_STATUS: Record<string, PillTone> = {
  pending: 'brand',
  paid: 'brand',
  in_service: 'success',
  completed: 'muted',
  cancelled: 'muted',
  refunding: 'warning',
  refunded: 'muted',
  refund_rejected: 'warning',
};

/** 状态 code → 文案 key。 */
const KEY_BY_STATUS: Record<string, MessageKey> = {
  pending: 'trade.order.statusPending',
  paid: 'trade.order.statusPaid',
  in_service: 'trade.order.statusInService',
  completed: 'trade.order.statusCompleted',
  cancelled: 'trade.order.statusCancelled',
  refunding: 'trade.order.statusRefunding',
  refunded: 'trade.order.statusRefunded',
  refund_rejected: 'trade.order.statusRefundRejected',
};

/** 四种色调的底色与文字色。 */
const TONE_STYLE: Record<PillTone, { bg: string; fg: string }> = {
  brand: { bg: color.brand.primarySoft, fg: color.brand.primary },
  success: { bg: color.state.successSoft, fg: color.state.success },
  warning: { bg: color.background.input, fg: color.state.warning },
  muted: { bg: color.background.input, fg: color.text.muted },
};

/**
 * 渲染状态胶囊。
 *
 * 副作用：无。
 *
 * @param props.status 订单状态 code
 * @param props.style  额外的外边距等样式
 * @returns 一枚圆角标签
 */
export function OrderStatusPill({ status, style }: { status: string; style?: StyleProp<ViewStyle> }) {
  const { t } = useI18n();
  const tone = TONE_BY_STATUS[status] ?? 'muted';
  const key = KEY_BY_STATUS[status];
  return (
    <View style={[styles.pill, { backgroundColor: TONE_STYLE[tone].bg }, style]}>
      <LocalizedText numberOfLines={1} style={[styles.text, { color: TONE_STYLE[tone].fg }]}>
        {key === undefined ? status : t(key)}
      </LocalizedText>
    </View>
  );
}

const styles = StyleSheet.create({
  pill: {
    alignSelf: 'flex-start',
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
  },
  text: {
    ...typography.micro,
  },
});
