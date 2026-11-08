/**
 * 空态。
 *
 * 职责：四态中的 `empty`——请求成功但没有数据。
 * 调用方：`AsyncBoundary`。
 * 注意：
 *   1. 文案默认取 `common.state.empty`，调用方可覆盖（`i18nKey`）。
 *   2. **空态不是错误态**：不要在这一层给「重试」按钮（重试属于错误态）。
 */

import { StyleSheet, View } from 'react-native';

import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { color, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';

/** `EmptyState` 的属性。 */
export type EmptyStateProps = {
  /** 覆盖默认文案的 key（可选）。 */
  i18nKey?: MessageKey;
};

/**
 * 渲染空态。
 *
 * 副作用：无。
 *
 * @param props 可选文案 key
 * @returns 居中的一行说明
 */
export function EmptyState({ i18nKey }: EmptyStateProps) {
  const { t } = useI18n();
  return (
    <View style={styles.container} accessibilityRole="text">
      <LocalizedText numberOfLines={2} style={styles.text}>
        {t(i18nKey ?? 'common.state.empty')}
      </LocalizedText>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingVertical: spacing.xxxl,
    alignItems: 'center',
    justifyContent: 'center',
  },
  text: {
    ...typography.body,
    color: color.text.muted,
    textAlign: 'center',
  },
});
