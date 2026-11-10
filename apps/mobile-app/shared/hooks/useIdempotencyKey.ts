/**
 * 幂等键 Hook —— **全 App 唯一允许生成幂等键的地方**。
 *
 * 职责：给「提交类」请求（下单、充值、提现、立案、补正）提供一次操作的固定幂等键，
 *       并规定什么时候该换新键。
 * 调用方：`features/lawyer/pages/LawyerDetailPage.tsx`（下单）、
 *         `features/trade/pages/RechargePage.tsx`（充值）、
 *         `features/workbench/components/WithdrawPanel.tsx`（提现）、
 *         `features/case/pages/FilingPage.tsx`（立案 / 补正）。
 * 数据来源：无（本地生成的随机串）。
 * 注意：
 *   1. **语义只有一种，写反了就失去意义**：
 *      · **页面挂载时生成一次** → 同一次操作的多次点击、多次重试，用的是同一个键；
 *      · **成功后调 `reset()`** → 下一次操作是新操作，不能复用旧键（否则服务端返回上次结果，
 *        用户会看到「提交成功」但其实是上一次的那单）；
 *      · **失败后不要 reset** → 重试必须复用同一个键，服务端才认得出这是重试而不是新提交。
 *   2. **不许在 `features` 的 api 层生成幂等键**。api 层每次调用都新生成一个，
 *      等于「每次都当新提交」，幂等形同虚设。ESLint 规则 `no-idem-key-in-api` 会拦。
 *   3. 键格式 `[A-Za-z0-9_-]{1,64}`（接口规范）；前缀用业务名区分链路，
 *      避免两条链路在服务端 Redis 里互串（如 `case-filing` / `case-resubmit` / `trade-recharge`）。
 */

import { useCallback, useMemo, useState } from 'react';

/**
 * 生成一个幂等键。**只应由本文件与 `useIdempotencyKey` 调用**。
 *
 * @param prefix 业务前缀（见注意事项 3）
 * @returns 形如 `trade-recharge-1731234567890-a1b2c3d4` 的一次性键
 */
function newIdempotencyKey(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/** `useIdempotencyKey` 的返回值。 */
export type IdempotencyKey = {
  /** 当前操作的幂等键。提交时把它塞进 `Idempotency-Key` 请求头。 */
  key: string;
  /** 成功后调用：换成新键，表示「下一次是新操作」。**失败时不要调。** */
  reset: () => void;
};

/**
 * 为一次提交操作维护幂等键。
 *
 * @param prefix 业务前缀，如 `trade-recharge`
 * @returns 当前键 + 重置函数
 */
export function useIdempotencyKey(prefix: string): IdempotencyKey {
  const [key, setKey] = useState<string>(() => newIdempotencyKey(prefix));
  const reset = useCallback(() => {
    setKey(newIdempotencyKey(prefix));
  }, [prefix]);
  // 返回稳定引用：调用方常把它写进 useCallback 的依赖数组，
  // 每次渲染都新建对象会让那些 useCallback 全部失效。
  return useMemo(() => ({ key, reset }), [key, reset]);
}
