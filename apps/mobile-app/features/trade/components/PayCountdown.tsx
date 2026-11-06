/**
 * 支付倒计时。
 *
 * 职责：把服务端给的 `payExpireAt`（UTC）渲染成「剩余支付时间 mm:ss」，到点显示已过期文案。
 * 调用方：`features/trade/pages/OrderListPage.tsx`（待支付卡片）、`OrderDetailPage.tsx`（状态头）。
 * 数据来源：`OrderItem.payExpireAt`（服务端按 `OrderTimeoutPolicy` 算，前端不自己加 15 分钟）。
 * 注意：
 *   1. **倒计时以服务端截止时间为准**：前端只用 `Date.now()` 算差值，
 *      不依赖「页面打开时刻 + 15 分钟」，否则刷新页面倒计时会重置。
 *   2. 每秒 setInterval 只更新本组件；卸载时清理（`clearInterval`）。
 *   3. 到点只显示「支付时间已过」，**不在这里发请求改状态**：关单是服务端定时任务的事，
 *      客户端发什么请求都不该能改订单状态。
 *   4. `expireAt` 为空（非待支付）时不渲染任何东西，避免空行占位。
 */

import { useEffect, useState } from 'react';
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';

import { LocalizedText } from '@/shared/components/LocalizedText';
import { useI18n } from '@/shared/i18n';
import { color, typography } from '@/shared/theme';
import { parseServerTime } from '@/shared/utils/serverTime';

/**
 * 把剩余毫秒格式化成 `mm:ss`（不足两位补零）。
 *
 * @param remainMs 剩余毫秒（已保证 >= 0）
 * @returns 形如 `14:32`
 */
function formatRemain(remainMs: number): string {
  const totalSeconds = Math.floor(remainMs / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

/**
 * 渲染支付倒计时。
 *
 * 副作用：每秒触发一次本组件重渲染，卸载时清理定时器。
 *
 * @param props.expireAt 支付截止时间（服务端 UTC 串），为空时不渲染
 * @param props.style    额外样式
 * @returns 一行倒计时文案
 */
export function PayCountdown({
  expireAt,
  style,
}: {
  expireAt: string | null;
  style?: StyleProp<ViewStyle>;
}) {
  const { t } = useI18n();
  const deadline = parseServerTime(expireAt);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (deadline === null) {
      return;
    }
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [deadline]);

  if (deadline === null) {
    return null;
  }
  const remain = deadline - now;
  return (
    <View style={[styles.wrap, style]}>
      <LocalizedText numberOfLines={1} style={styles.text}>
        {remain <= 0
          ? t('trade.order.countdownExpired')
          : t('trade.order.countdownRemaining', { time: formatRemain(remain) })}
      </LocalizedText>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    alignSelf: 'flex-start',
  },
  text: {
    ...typography.caption,
    color: color.state.warning,
  },
});
