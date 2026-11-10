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
 *   4. `reloadSilently()` 是给 `useRefreshOnFocus` 用的**静默刷新**：不置 `loading`（不闪骨架）、
 *      失败时保留已有成功数据（不把 success 打成 error），成功才替换数据。首屏加载行为与它无关。
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
  /**
   * 额外依赖：这些值变化时重新加载。
   *
   * 为什么需要它：本 hook 原本只按「页面级单次加载」设计，loader 放 ref 里、
   * 不参与依赖，所以 `useLawItemByRef(lawItemId)` 这种「参数变了要重新取」的场景
   * 会拿不到新数据。加了这个字段就能在不改变原有行为的前提下按参数重载。
   *
   * **只接受原始值**（string / number / boolean / null / undefined）：对象每次渲染都是新引用，
   * 会导致无限重载。多条依赖传多个元素。
   */
  deps?: ReadonlyArray<string | number | boolean | null | undefined>;
};

/** `useAsyncResource` 的返回值。 */
export type AsyncResource<T> = {
  /** 当前四态。 */
  state: AsyncState<T>;
  /** 重新加载（错误态的重试按钮用）。会先进入 `loading`，失败进 `error`。 */
  reload: () => void;
  /**
   * 静默重新加载（聚焦刷新用）。
   *
   * 不进入 `loading`（不闪骨架）；失败时保留旧数据、不改成 `error`；成功才替换。
   */
  reloadSilently: () => void;
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
  /** 本次 effect 是否为静默刷新：`reloadSilently` 置位，effect 消费后复位。 */
  const silentRef = useRef(false);
  const mountedRef = useRef(true);
  const enabled = options.enabled !== false;
  // deps 拼成一个稳定字符串再进依赖数组：直接 spread 会被 react-hooks 规则告警，
  // 而对象引用做依赖会每次渲染都变、导致无限重载。
  const depKey = (options.deps ?? []).join('\u0001');

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    if (!enabled) {
      silentRef.current = false;
      return;
    }
    // 静默刷新：不进入 loading，失败不覆盖已有数据。首屏仍走原来的 loading → success/error 流程。
    const silent = silentRef.current;
    silentRef.current = false;
    let cancelled = false;
    if (!silent) {
      setState({ phase: 'loading' });
    }
    void (async () => {
      const result = await loaderRef.current();
      if (cancelled || !mountedRef.current) {
        return;
      }
      if (!result.ok) {
        if (silent) {
          // 静默刷新失败：保留已展示的数据，不把 success 打成 error（下次聚焦再试）。
          return;
        }
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
  }, [nonce, enabled, depKey]);

  const reload = useCallback(() => {
    setNonce((value) => value + 1);
  }, []);

  const reloadSilently = useCallback(() => {
    silentRef.current = true;
    setNonce((value) => value + 1);
  }, []);

  const setData = useCallback((data: T) => {
    setState({ phase: 'success', data });
  }, []);

  return { state, reload, reloadSilently, setData };
}
