/**
 * 页面重新获得焦点时执行刷新回调的 Hook（聚焦刷新）。
 *
 * 职责：解决「从详情页 / 操作页返回列表时，列表还是旧数据」这个问题——
 *       页面挂载时只加载一次，返回时不会再请求；本 hook 在**后续**每次聚焦时补一次刷新。
 * 调用方：P1-6 接入清单里的列表 / 展示类页面（表单与支付类页面不接，见 P1-TASKS.md 批 E）。
 * 数据来源：由调用方传入的刷新函数（通常是 `useAsyncResource` 的 `reloadSilently`
 *          或手写 `load` 的静默分支）。
 * 注意：
 *   1. **首次聚焦跳过**：挂载时页面已经做过首屏加载，再刷一次就是重复请求。
 *   2. 本 hook 只用于**列表 / 详情的数据刷新**，不用于副作用重跑（埋点、订阅等不要放进来）。
 *   3. 回调必须稳定（`useCallback` 包过，或直接用 hook 返回值）：否则每次渲染都会重新订阅，
 *      聚焦时可能重复触发。
 *   4. 回调内部必须是**静默**刷新：不许 `setPhase('loading')` / `setLoading(true)`，
 *      否则返回列表会闪骨架，违背本 hook 的目的。
 */

import { useFocusEffect } from 'expo-router';
import { useCallback, useRef } from 'react';

/**
 * 后续每次页面聚焦时调用 `refresh`（首次聚焦跳过）。
 *
 * 副作用：在页面获得焦点时调用 `refresh`；不修改页面状态。
 *
 * @param refresh 刷新回调（必须稳定，且必须是静默刷新）
 * @returns 无
 */
export function useRefreshOnFocus(refresh: () => void): void {
  const skipFirstRef = useRef(true);
  useFocusEffect(
    useCallback(() => {
      if (skipFirstRef.current) {
        skipFirstRef.current = false;
        return;
      }
      refresh();
    }, [refresh]),
  );
}
