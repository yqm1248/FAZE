/**
 * AI 配额 Hook（F5）。
 *
 * 职责：读 `GET /app/ai/quota`，给出「今日还剩 N 次」与「是否用完」，并在每轮回答完成后刷新。
 * 调用方：`features/ai/pages/AiChatPage.tsx`。
 * 数据来源：`features/ai/api/ai.ts#getAiQuota`。
 * 注意：
 *   1. **配额是服务端权威值**：本 Hook 只读不扣；每轮 `done` 后重新拉一次，不用本地 -1 猜测
 *      （重连不重复扣费是服务端 C5 的语义）。
 *   2. 查询失败**不阻断对话**：`remaining` 返回 null，页面隐藏配额行；AI 仍可发（服务端会兜底）。
 */

import { useCallback, useEffect, useRef, useState } from 'react';

import { getAiQuota } from '@/features/ai/api/ai';
import type { AiQuota } from '@/features/ai/api/ai';

/** `useAiQuota` 的返回值。 */
export type UseAiQuotaResult = {
  /** 配额数据；未取到为 null。 */
  quota: AiQuota | null;
  /** 剩余次数（limt - used，clamp 到 ≥0）；未取到为 null。 */
  remaining: number | null;
  /** 是否已用完（true 时输入框禁用）。 */
  exhausted: boolean;
  /** 重新拉取配额（每次回答完成后调）。 */
  refresh: () => void;
};

/**
 * 订阅配额。
 *
 * 副作用：挂载时发一次 `GET /app/ai/quota`；`refresh()` 时再发。
 *
 * @returns 配额、剩余次数与刷新函数
 */
export function useAiQuota(): UseAiQuotaResult {
  const [quota, setQuota] = useState<AiQuota | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const load = useCallback(async (): Promise<void> => {
    const result = await getAiQuota();
    if (!mountedRef.current) {
      return;
    }
    if (result.ok) {
      setQuota(result.data);
    }
    // 失败保持旧值/空值：配额显示不是对话的前置条件（见文件头注释第 2 条）。
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const refresh = useCallback((): void => {
    void load();
  }, [load]);

  const remaining = quota === null ? null : Math.max(0, quota.limit - quota.used);
  return { quota, remaining, exhausted: remaining === 0, refresh };
}
