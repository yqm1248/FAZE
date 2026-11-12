/**
 * 深色模式的取用入口（第 8 段 E-4）。
 *
 * 职责：把「设置里的三选（system / on / off）+ 系统外观」解析成一个确定的光/暗方案，
 *       并返回对应的那一套颜色令牌（`color` 或 `darkColor`）。
 * 调用方：**目前没有页面调用**——本文件是令牌层的落点，页面接入见下方"未接入"说明。
 * 数据来源：`./tokens`（两套同构的颜色令牌）；系统外观由调用方传 `useColorScheme()` 的结果。
 * 注意：
 *   1. **本文件不做 React 状态**：不读存储、不订阅系统外观。理由是本段只做令牌层，
 *      读存储要 `features/settings/storage.ts`（features → shared 是反向依赖）。
 *      将来接入时，在 App 根布局用一个 `ThemeProvider` 调本函数，页面从 context 取颜色。
 *   2. `system` 的语义是「跟随系统」，`null`（系统值还没读到）按浅色处理——
 *      宁可先亮一下，也不要闪一个深色再变亮。
 *   3. 两套令牌**同构**（33 个叶子键相同），所以返回类型是联合类型时不丢字段；
 *      `check-tokens-sync.py` 会拦「只在一边加了键」的情况。
 *   4. 主色在深色下是提亮过的（#7C93F7），不是简单反色——**不要**在页面里自己用
 *      `darken()` 之类算深色值，那会绕开对比度校验。
 */

import { color, darkColor } from './tokens';

/** 光/暗方案。 */
export type ColorScheme = 'light' | 'dark';

/**
 * 深色设置的三选值。
 *
 * <p>与 `features/settings/storage.ts` 的 `ThemeMode` **字面量相同**，
 * 但这里不 import 它：shared 层不能反向依赖 features 层。两处若将来分叉，
 * 由 TypeScript 在调用处（App 根布局）报错，而不是在 shared 里留下一个悬空引用。</p>
 */
export type ThemeModeValue = 'system' | 'on' | 'off';

/**
 * 解析出最终的显示方案。
 *
 * @param mode         设置值：system 跟随系统 / on 强制深色 / off 强制浅色
 * @param systemScheme 系统外观；null 表示还没读到（按浅色处理）
 * @returns light 或 dark
 */
export function resolveColorScheme(
  mode: ThemeModeValue,
  systemScheme: ColorScheme | null,
): ColorScheme {
  if (mode === 'on') {
    return 'dark';
  }
  if (mode === 'off') {
    return 'light';
  }
  return systemScheme === 'dark' ? 'dark' : 'light';
}

/**
 * 取当前方案对应的颜色令牌。
 *
 * @param mode         设置值
 * @param systemScheme 系统外观（null = 未读到，按浅色）
 * @returns 浅色返回 {@link color}，深色返回 {@link darkColor}（两者同构）
 */
export function getColorTokens(
  mode: ThemeModeValue,
  systemScheme: ColorScheme | null,
): typeof color | typeof darkColor {
  return resolveColorScheme(mode, systemScheme) === 'dark' ? darkColor : color;
}

/**
 * 直接按方案取令牌（已经知道方案时用，省一次解析）。
 *
 * @param scheme light 或 dark
 * @returns 对应的一套颜色令牌
 */
export function getColorTokensByScheme(scheme: ColorScheme): typeof color | typeof darkColor {
  return scheme === 'dark' ? darkColor : color;
}
