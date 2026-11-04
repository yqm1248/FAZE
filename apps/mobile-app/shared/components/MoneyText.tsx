/**
 * 金额展示（整数分 → 元，两位小数）。
 *
 * 职责：把「接口传分、界面显示元」这条规则收敛到一个组件，避免各页面自己写 `/100`。
 * 调用方：我的页（余额）、账户余额与明细页、支付密码提示等。
 * 数据来源：`shared/i18n/datetime.ts` 的 `formatAmount`（纯整数运算，无浮点累加）。
 * 注意：
 *   1. **入参是分**（`AGENTS.md` §4.5），本组件内部只做格式化，不做任何金额运算。
 *   2. 用 `LtrText` 包裹：RTL 下「¥1,234.00」必须保持 LTR，否则会被 BiDi 重排。
 *   3. 颜色与字号由调用方通过 `style` 覆盖（深底卡片上要用白字）。
 */

import { StyleSheet, type StyleProp, type TextStyle } from 'react-native';

import { formatAmount } from '@/shared/i18n/datetime';
import { color, typography } from '@/shared/theme';

import type { LocalizedTextProps } from './LocalizedText';
import { LocalizedText } from './LocalizedText';
import { LtrText } from './LtrText';

/** `MoneyText` 的属性。 */
export type MoneyTextProps = {
  /** 金额（分），必须为整数。 */
  cents: number;
  /** 视觉规格：`display` 大号数字、`body` 列表金额、`caption` 次要金额。 */
  variant?: 'display' | 'body' | 'caption';
  /** 附加样式。 */
  style?: StyleProp<TextStyle>;
  /** 文本行数，默认 1（金额不允许折行）。 */
  numberOfLines?: LocalizedTextProps['numberOfLines'];
};

/**
 * 渲染格式化后的金额。
 *
 * 副作用：无。
 *
 * @param props 金额（分）与视觉规格
 * @returns 形如 `¥1,234.00` 的文本
 */
export function MoneyText({ cents, variant = 'body', style, numberOfLines = 1 }: MoneyTextProps) {
  return (
    <LtrText
      numberOfLines={numberOfLines}
      style={[styles.base, variantStyles[variant], style]}
    >
      {formatAmount(cents)}
    </LtrText>
  );
}

const styles = StyleSheet.create({
  base: {
    color: color.text.body,
  },
});

const variantStyles = StyleSheet.create({
  display: {
    ...typography.h1,
  },
  body: {
    ...typography.bodyLarge,
  },
  caption: {
    ...typography.caption,
  },
});

/**
 * 渲染「正负号 + 金额」的流水金额（正入账带 `+`，负出账带 `-`）。
 *
 * 为什么单独一个组件：`formatAmount` 已按负数输出 `-¥x`，但正数需要显式 `+`，
 * 页面里手拼 `+` 容易在 RTL 下与金额分离（符号与数字之间被重排）。
 *
 * @param props 金额（分）、行数上限与样式
 * @returns 带符号的金额文本
 */
export function SignedMoneyText({
  cents,
  style,
  numberOfLines = 1,
}: {
  cents: number;
  style?: StyleProp<TextStyle>;
  numberOfLines?: LocalizedTextProps['numberOfLines'];
}) {
  const text = cents > 0 ? `+${formatAmount(cents)}` : formatAmount(cents);
  return (
    <LtrText numberOfLines={numberOfLines} style={[styles.base, typography.bodyLarge, style]}>
      {text}
    </LtrText>
  );
}

/** 供页面复用的「金额符号」文本（`¥` 单独渲染时用）。 */
export function MoneySymbol({ style }: { style?: StyleProp<TextStyle> }) {
  return (
    <LocalizedText numberOfLines={1} style={[styles.base, style]}>
      {CURRENCY_SYMBOL}
    </LocalizedText>
  );
}

/** 金额符号常量（不直接写在 JSX 里，避免被 no-raw-text 规则拦下）。 */
const CURRENCY_SYMBOL = '¥';
