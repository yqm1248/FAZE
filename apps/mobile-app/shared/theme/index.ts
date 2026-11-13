/**
 * 主题层统一出口。
 *
 * 页面与组件通过 `@/shared/theme` 取用设计令牌与文本样式，
 * 不直接引用 `tokens.ts` 的内部结构，便于将来调整分组而不改动调用方。
 */

export {
  color,
  darkColor,
  gradient,
  fontSize,
  fontWeight,
  lineHeight,
  spacing,
  radius,
  size,
  shadow,
  tokens,
} from './tokens';

export type { Tokens } from './tokens';

// 深色模式取用入口（第 8 段 E-4）：按 settings.theme + 系统外观选一套颜色令牌。
// 页面**不要**直接 import darkColor，走 getColorTokens——它会按设置与系统外观选。
export { getColorTokens, getColorTokensByScheme, resolveColorScheme } from './darkMode';
export type { ColorScheme, ThemeModeValue } from './darkMode';

export { typography, lineHeightFor } from './typography';

export { FONT_ASSETS, FONT_FAMILY } from './fonts';

// Web 输入框焦点环复位：自绘输入框统一从这里取，别各写一份（原生端无可见效果）。
export { WEB_FOCUS_RESET } from './focusRing';

// 图像区域（画布 / 预览纸张）的 LTR 布局隔离：Web 端必须为空，否则控制台报警。
export { IMAGE_BOX_LTR } from './imageBoxLtr';
