/**
 * 错误态（按 `AppError.kind` 选文案 + 重试）。
 *
 * 职责：四态中的 `error`——把 `AppError` 翻译成可读文案，并给「重试」出口。
 * 调用方：`AsyncBoundary`、`app/_layout.tsx` 的 `ErrorBoundary`。
 * 注意：
 *   1. 文案映射：`network` → `error.common.network`；`business` → `t(error.msgKey)`；
 *      `parse` → `error.common.server`。**绝不展示 `R.msg` 原文**（接口规范 §5.1）。
 *   2. `traceId` 有则显示（便于后端定位），但**不显示给普通用户当作主要信息**——
 *      放在小字里即可。
 *   3. 一律带「重试」按钮；`onRetry` 由调用方给（通常是重新发起的那个函数）。
 */

import { StyleSheet, View } from 'react-native';

import type { AppError } from '@/shared/api/types';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';
import { color, spacing, typography } from '@/shared/theme';

import { LocalizedText } from './LocalizedText';
import { PrimaryButton } from './PrimaryButton';

/** `ErrorState` 的属性。 */
export type ErrorStateProps = {
  /** 归一后的错误。 */
  error: AppError;
  /** 重试回调。 */
  onRetry: () => void;
  /** 重试按钮的 testID（E2E 用；可选，不传则不渲染该属性）。 */
  retryTestID?: string;
};

/**
 * 按错误种类取文案 key。
 *
 * @param error 归一错误
 * @returns 文案 key
 */
function textKeyOf(error: AppError): MessageKey {
  if (error.kind === 'business') {
    // 业务错误的 msgKey 来自服务端（类型上是 string），这里收窄成文案 key：
    // 未登记的 key 在 t() 里会走「缺 key」分支并告警，不会静默显示空串。
    return error.msgKey as MessageKey;
  }
  if (error.kind === 'parse') {
    return 'error.common.server';
  }
  return 'error.common.network';
}

/**
 * 渲染错误态。
 *
 * 副作用：无（重试由调用方执行）。
 *
 * @param props 错误与重试回调
 * @returns 说明文案 + 可选 traceId + 重试按钮
 */
export function ErrorState({ error, onRetry, retryTestID }: ErrorStateProps) {
  const { t } = useI18n();
  return (
    <View style={styles.container} accessibilityRole="alert">
      <LocalizedText numberOfLines={3} style={styles.message}>
        {t(textKeyOf(error))}
      </LocalizedText>
      {error.traceId ? (
        <LocalizedText numberOfLines={1} style={styles.trace}>
          {`traceId: ${error.traceId}`}
        </LocalizedText>
      ) : null}
      <View style={styles.retry}>
        <PrimaryButton label={t('common.state.retry')} onPress={onRetry} testID={retryTestID} />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingVertical: spacing.xxl,
    paddingHorizontal: spacing.xl,
    alignItems: 'center',
  },
  message: {
    ...typography.body,
    color: color.text.body,
    textAlign: 'center',
  },
  trace: {
    ...typography.micro,
    color: color.text.muted,
    marginTop: spacing.sm,
  },
  retry: {
    marginTop: spacing.xl,
    alignSelf: 'stretch',
  },
});
