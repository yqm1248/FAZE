/**
 * 四态容器（加载 / 空 / 错误 / 正常）。
 *
 * 职责：把「页面只写正常态、其余三态自动有」这件事做成通用实现。
 * 调用方：段 1 起所有需要异步数据的页面；段 0 的探针页与登录页。
 * 注意：
 *   1. **只有四态，没有第五态**：不设 `idle`——它与 `loading` 在 UI 上无法区分，
 *      多一个状态等于多一处「到底该显示什么」的争论。
 *   2. 组件本身**不发请求、不管理状态**：`state` 与 `onRetry` 都由调用方给，
 *      这样才能被任何数据源复用（B4 的通用性要求）。
 *   3. 错误文案映射见 `ErrorState`；空态文案可被 `emptyHintKey` 覆盖。
 */

import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';

import type { AppError } from '@/shared/api/types';
import { spacing } from '@/shared/theme';

import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';
import { LoadingState } from './LoadingState';

/** 异步数据的四态。 */
export type AsyncState<T> =
  | { phase: 'loading' }
  | { phase: 'empty' }
  | { phase: 'error'; error: AppError }
  | { phase: 'success'; data: T };

/** `AsyncBoundary` 的属性。 */
export type AsyncBoundaryProps<T> = {
  /** 当前状态。 */
  state: AsyncState<T>;
  /** 重试回调（错误态使用）。 */
  onRetry: () => void;
  /** 正常态渲染函数——**页面只写这一支**。 */
  children: (data: T) => ReactNode;
  /** 空态文案覆盖。 */
  emptyHintKey?: Parameters<typeof EmptyState>[0]['i18nKey'];
  /** 骨架屏行数。 */
  loadingRows?: number;
};

/**
 * 按状态分发渲染。
 *
 * 副作用：无。
 *
 * @param props 状态、重试回调、正常态渲染函数
 * @returns 对应状态的节点
 */
export function AsyncBoundary<T>({
  state,
  onRetry,
  children,
  emptyHintKey,
  loadingRows,
}: AsyncBoundaryProps<T>) {
  if (state.phase === 'loading') {
    return (
      <View style={styles.container}>
        <LoadingState rows={loadingRows} />
      </View>
    );
  }
  if (state.phase === 'empty') {
    return (
      <View style={styles.container}>
        <EmptyState i18nKey={emptyHintKey} />
      </View>
    );
  }
  if (state.phase === 'error') {
    return (
      <View style={styles.container}>
        <ErrorState error={state.error} onRetry={onRetry} />
      </View>
    );
  }
  return <>{children(state.data)}</>;
}

const styles = StyleSheet.create({
  container: {
    paddingHorizontal: spacing.lg,
  },
});
