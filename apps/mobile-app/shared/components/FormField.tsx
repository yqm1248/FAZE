/**
 * 表单输入行（标签 + 输入框 + 可选尾部插槽 + 错误文案）。
 *
 * 职责：统一段 1 全部表单页的输入行外观与可访问性，避免每页各写一套 TextInput 样式。
 * 调用方：注册、忘记密码、修改密码、换绑手机、实名认证、律师入驻、资料编辑等页面。
 * 数据来源：受控值由页面持有。
 * 注意：
 *   1. `placeholder` 与 `accessibilityLabel` 都**必须由页面传 t() 的结果**（AGENTS §4.4）。
 *   2. 文案方向：RN 的 `textAlign` 没有 start/end，这里用 `'auto'` 跟随语言方向，
 *      验证码/手机号等数字串不受影响（数字在 BiDi 中本身按 LTR 排列）。
 *   3. 尾部插槽（`trailing`）用于「获取验证码」「显示/隐藏密码」这类行内按钮。
 */

import type { ReactNode } from 'react';
import { StyleSheet, TextInput, View, type KeyboardTypeOptions } from 'react-native';

import { color, radius, size, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `FormField` 的属性。 */
export type FormFieldProps = {
  /** 字段标签。 */
  label: string;
  /** 受控值。 */
  value: string;
  /** 值变化回调。 */
  onChangeText: (text: string) => void;
  /** 占位文案（必传，且必须是 t() 的结果）。 */
  placeholder: string;
  /** 是否密码输入。 */
  secureTextEntry?: boolean;
  /** 键盘类型。 */
  keyboardType?: KeyboardTypeOptions;
  /** 最大长度。 */
  maxLength?: number;
  /** 是否多行（简介、备注）。 */
  multiline?: boolean;
  /** 字段级错误文案（已翻译）。 */
  error?: string | null;
  /** 行内尾部插槽（验证码按钮 / 眼睛按钮）。 */
  trailing?: ReactNode;
  /** 是否可编辑，默认 true。 */
  editable?: boolean;
};

/**
 * 渲染一个表单输入行。
 *
 * 副作用：无（受控组件）。
 *
 * @param props 标签、值、回调与样式开关
 * @returns 标签 + 输入框 + 错误文案
 */
export function FormField({
  label,
  value,
  onChangeText,
  placeholder,
  secureTextEntry = false,
  keyboardType,
  maxLength,
  multiline = false,
  error,
  trailing,
  editable = true,
}: FormFieldProps) {
  return (
    <View style={styles.field}>
      <LocalizedText numberOfLines={1} style={styles.label}>
        {label}
      </LocalizedText>
      <View style={[styles.inputRow, multiline ? styles.inputRowMultiline : null]}>
        <TextInput
          value={value}
          onChangeText={onChangeText}
          placeholder={placeholder}
          accessibilityLabel={label}
          secureTextEntry={secureTextEntry}
          keyboardType={keyboardType}
          maxLength={maxLength}
          multiline={multiline}
          editable={editable}
          style={[styles.input, multiline ? styles.inputMultiline : null]}
          placeholderTextColor={color.text.muted}
        />
        {trailing === undefined ? null : <View style={styles.trailing}>{trailing}</View>}
      </View>
      {error ? (
        <LocalizedText numberOfLines={2} style={styles.error}>
          {error}
        </LocalizedText>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  field: {
    marginTop: spacing.lg,
  },
  label: {
    ...typography.bodySmall,
    color: color.text.secondary,
    marginBottom: spacing.xs,
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: size.inputHeight,
    borderRadius: radius.sm,
    backgroundColor: color.background.input,
    paddingHorizontal: spacing.md,
  },
  inputRowMultiline: {
    alignItems: 'flex-start',
    paddingVertical: spacing.sm,
  },
  input: {
    ...typography.body,
    flex: 1,
    height: size.inputHeight,
    color: color.text.body,
    paddingVertical: 0,
    // RN 无 start/end：auto 跟随语言方向。
    textAlign: 'auto',
  },
  inputMultiline: {
    height: size.inputHeight * 2,
    textAlignVertical: 'top',
  },
  trailing: {
    marginStart: spacing.sm,
  },
  error: {
    ...typography.caption,
    color: color.state.danger,
    marginTop: spacing.xxs,
  },
});
