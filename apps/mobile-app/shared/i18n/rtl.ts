/**
 * RTL 方向的应用与纠正（软重启链路）。
 *
 * 依据：技术路线 §13.4、第 0 段工作流 B3 第 2 条。
 * 调用方：`shared/i18n/index.ts` 的 `changeLang()`、`app/_layout.tsx` 的启动纠正。
 * 注意：
 *   1. **同向不重启**：zh ↔ bo 都是 LTR，只重渲染；LTR ↔ RTL 才走 forceRTL + reload。
 *   2. `forceRTL` 写的是**原生持久化标记**，所以「杀进程重启后仍是 RTL」成立；
 *      `reloadAsync` 只影响「用户是否需要手动杀进程」这一体验层。
 *      **`0-A3` 的硬判据是杀进程后仍 RTL，不依赖 `reloadAsync` 可用**。
 *   3. `reloadAsync` 在 Expo Go 下不可用（会抛），此时返回 false 由调用方降级为
 *      「提示用户手动重启」，**不许因此判 `0-A3` 失败**。
 */

import * as Updates from 'expo-updates';
import { I18nManager, Platform } from 'react-native';

import { dirOf } from './lang';
import type { Lang } from './lang';

/**
 * web 端的排版方向兜底。
 *
 * `react-native-web` 的 `I18nManager.forceRTL` 是空实现，浏览器里 `dir` 永远是 ltr；
 * 而负责人明确「真机验收全部改为浏览器验收」，所以 web 上必须自己写 `document.dir`。
 * **只在 web 生效**，native 仍走 `forceRTL` + reload 那条链路，不影响 `0-A3`。
 *
 * @param lang 当前语言
 */
export function applyWebDirection(lang: Lang): void {
  if (Platform.OS !== 'web') {
    return;
  }
  const doc = globalThis.document;
  if (doc === undefined) {
    return;
  }
  doc.documentElement.dir = dirOf(lang) === 'rtl' ? 'rtl' : 'ltr';
}

/**
 * 应用目标语言的方向。
 *
 * @param next    目标语言
 * @param current 当前语言
 * @returns 是否触发了重启（true = 已请求 reload；false = 同向，只需重渲染）
 */
export async function applyDirection(next: Lang, current: Lang): Promise<boolean> {
  const nextIsRtl = dirOf(next) === 'rtl';
  const currentIsRtl = dirOf(current) === 'rtl';

  // 同向（zh ↔ bo，或 ug ↔ kk）只换文案，不重启。
  if (nextIsRtl === currentIsRtl) {
    return false;
  }

  // 顺序不能反：allowRTL 必须在 forceRTL 之前，且两者都要在 reload 之前。
  I18nManager.allowRTL(true);
  I18nManager.forceRTL(nextIsRtl);

  try {
    await Updates.reloadAsync();
    return true;
  } catch {
    // Expo Go / 未配置 update 通道时 reloadAsync 不可用。
    // 返回 false，由调用方提示「请手动重启」——**不能把这里当失败**。
    if (__DEV__) {
      console.warn('[rtl] reloadAsync 不可用，需要手动重启才能生效（0-A3 允许手动重启验）');
    }
    return false;
  }
}

/**
 * 启动时校验「原生方向」与「当前语言要求的方向」是否一致，不一致则纠正。
 *
 * 场景：用户切到维吾尔文后手动杀了进程，但 forceRTL 标记与存储的语言恰好不一致
 * （例如上一次切换中途崩溃）。
 *
 * @param lang 当前语言
 * @returns 是否需要再重启一次（true = 已纠正，建议调用 reloadAsync）
 */
export function ensureDirection(lang: Lang): boolean {
  const shouldBeRtl = dirOf(lang) === 'rtl';
  if (I18nManager.isRTL === shouldBeRtl) {
    return false;
  }
  I18nManager.allowRTL(true);
  I18nManager.forceRTL(shouldBeRtl);
  return true;
}

/**
 * 当前是否 RTL（探针页要显示给验收人看）。
 *
 * @returns `I18nManager.isRTL`
 */
export function isRtlNow(): boolean {
  return I18nManager.isRTL;
}
