/**
 * 异步资源 Hook（四态 + 重试）。
 *
 * 职责：把一个「一次加载型」接口（GET 详情 / 列表首页 / 当前用户资料）包装成
 * `AsyncState<T>`，页面只写正常态，加载 / 空 / 错误交给 `AsyncBoundary`。
 * 调用方：段 1 G 组的 18 个页面（我的、余额、支付密码、入驻状态、实名、账号安全等）。
 * 数据来源：`shared/api/client.ts` 的 `ApiResult<T>`（永不抛异常）。
 * 注意：
 *   1. **加载函数放在 ref 里**：调用方通常传内联箭头函数，直接进 deps 会每次渲染都重新请求。
 *      本项目按「页面级单次加载」使用，需要重新请求时调 `reload()`。
 *   2. 卸载后不再 setState：避免「返回上一页时组件已卸载」告警。
 *   3. `emptyWhen` 由调用方决定「什么算空」——列表用 `list.length === 0`，
 *      详情类接口一般不需要（不传即永远不进空态）。
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type { ApiResult } from '@/shared/api/types';

import type { AsyncState } from '@/shared/components/AsyncBoundary';

/** `useAsyncResource` 的可选项。 */
export type AsyncResourceOptions<T> = {
  /** 判断「成功但没有数据」的谓词；不传则永远不进空态。 */
  emptyWhen?: (data: T) => boolean;
  /** 是否立即加载，默认 true。 */
  enabled?: boolean;
};

/** `useAsyncResource` 的返回值。 */
export type AsyncResource<T> = {
  /** 当前四态。 */
  state: AsyncState<T>;
  /** 重新加载（错误态的重试按钮用）。 */
  reload: () => void;
  /** 用本地结果覆盖当前数据（例如保存成功后刷新表单回显）。 */
  setData: (data: T) => void;
};

/**
 * 加载一次异步资源并维护四态。
 *
 * @template T 业务数据类型
 * @param loader 加载函数（返回 `ApiResult<T>`）
 * @param options 空态判定与启用开关
 * @returns 四态、重试、手动覆盖
 */
export function useAsyncResource<T>(
  loader: () => Promise<ApiResult<T>>,
  options: AsyncResourceOptions<T> = {},
): AsyncResource<T> {
  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  const emptyWhenRef = useRef(options.emptyWhen);
  emptyWhenRef.current = options.emptyWhen;
  const [state, setState] = useState<AsyncState<T>>({ phase: 'loading' });
  const [nonce, setNonce] = useState(0);
  const mountedRef = useRef(true);
  const enabled = options.enabled !== false;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    let cancelled = false;
    setState({ phase: 'loading' });
    void (async () => {
      const result = await loaderRef.current();
      if (cancelled || !mountedRef.current) {
        return;
      }
      if (!result.ok) {
        setState({ phase: 'error', error: result.error });
        return;
      }
      const predicate = emptyWhenRef.current;
      if (predicate !== undefined && predicate(result.data)) {
        setState({ phase: 'empty' });
        return;
      }
      setState({ phase: 'success', data: result.data });
    })();
    return () => {
      cancelled = true;
    };
  }, [nonce, enabled]);

  const reload = useCallback(() => {
    setNonce((value) => value + 1);
  }, []);

  const setData = useCallback((data: T) => {
    setState({ phase: 'success', data });
  }, []);

  return { state, reload, setData };
}
