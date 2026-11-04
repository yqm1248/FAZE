/**
 * 六位数字密码输入（支付密码专用）。
 *
 * 职责：设计稿把支付密码画成 6 个方格，这里用「透明 TextInput + 6 个方格回显」实现：
 *       键盘输入仍交给系统，视觉与设计稿一致。
 * 调用方：`features/account/pages/PayPasswordPage.tsx`。
 * 数据来源：受控值由页面持有。
 * 注意：
 *   1. 输入框设为绝对定位且透明——点方格区域通过 `Pressable` 把焦点交给隐藏输入框，
 *      这样 Web / 原生表现一致（Web 上直接点透明输入框会命中它，其余端靠 focus()）。
 *   2. 圆点用模块常量而不是 JSX 字面量（ESLint 的 no-raw-text 会拦字符串字面量）。
 *   3. `numberOfLines` 显式给：阿拉伯系字体必须给行数上限（i18n 规范 §7.1）。
 */

import { useRef, useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';

import { color, radius, size, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** 密码位数的默认值。 */
const DEFAULT_LENGTH = 6;
/** 密码圆点（掩码）。 */
const BULLET = '\u25CF';

/** `PinField` 的属性。 */
export type PinFieldProps = {
  /** 当前值。 */
  value: string;
  /** 值变化回调。 */
  onChangeText: (text: string) => void;
  /** 位数，默认 6。 */
  length?: number;
  /** 无障碍标签（已翻译）。 */
  accessibilityLabel: string;
  /** 是否禁用（提交中）。 */
  editable?: boolean;
};

/**
 * 渲染六位密码输入。
 *
 * 副作用：无（受控）。
 *
 * @param props 值、回调与位数
 * @returns 方格 + 隐藏输入框
 */
export function PinField({
  value,
  onChangeText,
  length = DEFAULT_LENGTH,
  accessibilityLabel,
  editable = true,
}: PinFieldProps) {
  const inputRef = useRef<TextInput>(null);
  const [focused, setFocused] = useState(false);
  const cells = Array.from({ length }, (_, index) => value[index] ?? '');

  return (
    <Pressable
      onPress={() => inputRef.current?.focus()}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      style={styles.wrap}
    >
      <View style={styles.boxes}>
        {cells.map((cell, index) => {
          const active = focused && index === value.length;
          return (
            <View key={index} style={[styles.box, active ? styles.boxActive : null]}>
              <LocalizedText numberOfLines={1} style={styles.dot}>
                {cell === '' ? '' : BULLET}
              </LocalizedText>
            </View>
          );
        })}
      </View>
      <TextInput
        ref={inputRef}
        value={value}
        onChangeText={(text) => onChangeText(text.replace(/\D/g, '').slice(0, length))}
        keyboardType="number-pad"
        maxLength={length}
        secureTextEntry
        editable={editable}
        accessibilityLabel={accessibilityLabel}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        style={styles.hiddenInput}
        caretHidden
      />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  wrap: {
    marginTop: spacing.md,
  },
  boxes: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  box: {
    flex: 1,
    height: size.inputHeight,
    borderRadius: radius.sm,
    borderWidth: 1,
    borderColor: color.border.outline,
    backgroundColor: color.background.input,
    alignItems: 'center',
    justifyContent: 'center',
  },
  boxActive: {
    borderColor: color.brand.primary,
    backgroundColor: color.background.surface,
  },
  dot: {
    ...typography.h3,
    color: color.text.primary,
  },
  hiddenInput: {
    position: 'absolute',
    start: 0,
    end: 0,
    top: 0,
    bottom: 0,
    opacity: 0,
  },
});
