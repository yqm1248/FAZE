/**
 * 乐观切换 Hook —— 收藏 / 关注 / 点赞这类「一个按钮翻转一个布尔」的操作统一走这里。
 *
 * 职责：把「点一下 → 立刻翻转 → 请求 → 失败回滚」这套逻辑收敛成一份，
 *       避免每个页面各写一遍（各写一遍的结果就是有的回滚有的不回滚、有的防重入有的不防）。
 * 调用方：`features/content/pages/CaseDetailPage.tsx`（案例收藏）、
 *         `features/lawyer/pages/LawyerDetailPage.tsx`（关注律师）。
 * 数据来源：执行函数由调用方注入（`apply(next)`），本 hook 不直接依赖任何业务 api。
 * 注意：
 *   1. **为什么参数是注入的 `apply`，而不是计划里写的 `useFavorite(targetType, targetId)`**：
 *      `shared/` 不能反向依赖 `features/`（AGENTS §3.1 分层）。如果 hook 内部去 import
 *      `features/content/api/favorite` 和 `features/lawyer/api/follow`，就是 shared → features
 *      倒挂，将来拆包直接编译不过。注入执行函数既保住分层，又不减少统一性——
 *      「翻转 + 回滚 + 防重入」三件事都在这里。
 *   2. **受控模式**（`value` + `onChange`），不是自己持有状态。原因：真值来自详情接口，
 *      是异步到达的；hook 内部持有时「接口回来把用户刚点的结果覆盖掉」这件事很难写对，
 *      而受控模式让「谁是真值」只有一个答案。
 *   3. **乐观更新**：点击立刻翻转，用户不用等网络。失败必须回滚，否则界面状态与服务端不一致
 *      （下次进来又变回去，用户会以为是 bug）。
 *   4. **防重入**：`busy` 为真时直接返回，连点不会发两个请求。
 *   5. 失败时把 `AppError` 交回调用方，由 `useErrorMessage` 按 `msgKey` 翻文案——
 *      **不要在这里硬编码「操作失败」或挪用某个特定错误码的文案**。
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import type { ApiResult } from '@/shared/api/types';

/** `useOptimisticToggle` 的参数。 */
export type OptimisticToggleOptions = {
  /** 当前真值（来自详情接口或上层 state）。 */
  value: boolean;
  /** 写回真值。乐观翻转与失败回滚都通过它。 */
  onChange: (next: boolean) => void;
  /**
   * 执行写入：`next` 是翻转后的目标状态。
   *
   * `next` 是「目标值」而不是「取反」：调用方据此决定发 POST 还是 DELETE。
   */
  apply: (next: boolean) => Promise<ApiResult<unknown>>;
  /** 成功后的附加动作（轻提示、埋点等）。 */
  onSuccess?: (next: boolean) => void;
};

/** `useOptimisticToggle` 的返回值。 */
export type OptimisticToggle = {
  /** 是否正在提交（防重入用）。 */
  busy: boolean;
  /** 翻转并提交。失败时自动回滚，并把错误返回给调用方翻文案。 */
  toggle: () => Promise<ApiResult<unknown> | null>;
};

/**
 * 维护一个「可乐观翻转」的布尔状态（受控）。
 *
 * @param options 当前值、写回函数、执行函数、成功回调
 * @returns 忙碌标志与切换函数
 */
export function useOptimisticToggle(options: OptimisticToggleOptions): OptimisticToggle {
  const [busy, setBusy] = useState(false);
  /** 同步防重入：`busy` 的 state 更新要等下一次渲染，连点两次会在同一帧里都读到 false。 */
  const inFlightRef = useRef(false);

  // 用 ref 读最新值：toggle 被内联传给 onPress 时写进 deps 会每次渲染都重建；
  // 而用闭包捕获又会在异步期间读到过期值（乐观更新后 value 已经变了）。
  const valueRef = useRef(options.value);
  const onChangeRef = useRef(options.onChange);
  const applyRef = useRef(options.apply);
  const onSuccessRef = useRef(options.onSuccess);
  useEffect(() => {
    valueRef.current = options.value;
    onChangeRef.current = options.onChange;
    applyRef.current = options.apply;
    onSuccessRef.current = options.onSuccess;
  }, [options.value, options.onChange, options.apply, options.onSuccess]);

  const toggle = useCallback(async (): Promise<ApiResult<unknown> | null> => {
    if (inFlightRef.current) {
      return null;
    }
    inFlightRef.current = true;
    setBusy(true);

    const prev = valueRef.current;
    const next = !prev;
    // 乐观更新：先翻，再请求
    onChangeRef.current(next);

    const result = await applyRef.current(next);

    inFlightRef.current = false;
    setBusy(false);

    if (!result.ok) {
      // 回滚：界面必须与「服务端没变」这个事实一致
      onChangeRef.current(prev);
      return result;
    }
    onSuccessRef.current?.(next);
    return result;
  }, []);

  return { busy, toggle };
}
