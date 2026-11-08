/**
 * 在线状态 Hook（F3 的「网络已断开」条）。
 *
 * 职责：尽力探测在线/离线——Web 用 `navigator.onLine` + `online`/`offline` 事件；
 * 原生（无 NetInfo 依赖）由调用方在流式失败时把 `markOffline()` 置 false。
 * 调用方：`features/ai/pages/AiChatPage.tsx`。
 * 数据来源：浏览器 `navigator` / `online` 事件（Web）；流式失败（原生）。
 * 注意：
 *   1. **没有装 `@react-native-community/netinfo`**（未登记依赖，AGENTS §12 不允许顺手加）：
 *      原生端的「飞行模式 → 自动发现」不完整，靠 `useAiStream` 的网络失败信号兜底。
 *      这是本段的已知限制，写进 F3 验收文档；Web/CDP 验收不受影响。
 *   2. 只在 Web 注册监听；原生直接返回初始值 true，避免 `window` 不存在时的崩溃。
 */

import { useEffect, useState } from 'react';

/** `useOnlineStatus` 的返回值。 */
export type UseOnlineStatusResult = {
  /** 是否在线（未知时按在线处理）。 */
  online: boolean;
  /** 手动置为离线（流式请求失败时调用）。 */
  markOffline: () => void;
  /** 手动置为在线（下一次请求成功时调用）。 */
  markOnline: () => void;
};

/**
 * 订阅在线状态。
 *
 * 副作用：Web 下注册 `online` / `offline` 事件监听，卸载时移除。
 *
 * @returns 在线状态与两个手动标记函数
 */
export function useOnlineStatus(): UseOnlineStatusResult {
  const [online, setOnline] = useState(true);

  useEffect(() => {
    const browserNavigator = typeof navigator === 'undefined' ? null : navigator;
    if (browserNavigator !== null && typeof browserNavigator.onLine === 'boolean') {
      setOnline(browserNavigator.onLine);
    }
    if (typeof window === 'undefined' || typeof window.addEventListener !== 'function') {
      return undefined;
    }
    const handleOnline = () => setOnline(true);
    const handleOffline = () => setOnline(false);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  return {
    online,
    markOffline: () => setOnline(false),
    markOnline: () => setOnline(true),
  };
}
