/**
 * 启动分流器：冷启动后的第一站。
 *
 * 职责：读持久化的登录态，决定进登录页还是原路由（段 0 只有登录页一个目标）。
 * 数据：`shared/api/tokenStore` 的 `readSession()`。
 * 注意：
 *   1. **不许停在空白**：读存储失败也必须落到某个路由（`0-A8` 断网不崩的第三条约束）。
 *   2. 本页不渲染任何可见文字（避免白屏闪烁）；`View` 只是占位容器。
 */

import { Redirect } from 'expo-router';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { readSession } from '@/shared/api/tokenStore';

/** 分流决策状态。 */
type Decision = { readonly ready: false } | { readonly ready: true; readonly target: '/login' | '/' };

/**
 * 启动分流器。
 *
 * 副作用：读一次 SecureStore。
 *
 * @returns 决策完成时重定向；未完成时空白容器
 */
export default function Index() {
  const [decision, setDecision] = useState<Decision>({ ready: false });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const session = await readSession();
        if (!cancelled) {
          // 有 token 先回首页占位（段 0 没有真实首页，B 组之后由 C 组接真页面）；
          // 没 token 去登录页。
          setDecision({ ready: true, target: session === null ? '/login' : '/' });
        }
      } catch {
        // 读存储异常也必须有去路：直接去登录页，绝不空白。
        if (!cancelled) {
          setDecision({ ready: true, target: '/login' });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (!decision.ready) {
    return <View style={styles.root} />;
  }
  // 有 token 时目标仍是 `/`，会再次进入本页——段 0 没有真实首页，
  // 这里直接落到登录页避免自我循环（C 组接入首页后再改）。
  return <Redirect href="/login" />;
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
});
