/**
 * 字体大小倍率（段 7 E1 设置页的「字体大小」四档）。
 *
 * 职责：把「小 / 标准 / 大 / 特大」映射成字号倍率，并提供把基准字号、文本样式
 *       按倍率缩放成真实字号的纯函数，供设置页（以及需要跟随字号的其他页面）使用。
 * 所属路由：`/settings`（设置页「字体大小」底部浮层）。其他页面可按需复用。
 * 数据来源：倍率是本地常量（工作流 §三 E1 第 1 条明文：`1.0 / 0.875 / 1.125 / 1.25`）；
 *           用户选中的档位持久化在 `features/settings/storage.ts`（键 `settings.fontScale`）。
 * 注意：
 *   1. **本文件不读写存储**：`shared` 层不依赖 `features`，读写交给设置页的 storage 封装，
 *      这样本文件保持纯计算、可单测。四语言缺译登记不受影响（这里没有用户可见文案）。
 *   2. **不修改 `tokens.ts` 的字号令牌**：倍率是运行时缩放，令牌仍是设计基准值。
 *      改令牌要同步 `tokens.json` 与 `docs/design/设计令牌.md` 三处，工作流本段不允许。
 *   3. 只缩放 `fontSize` 与 `lineHeight`，**不缩放图标尺寸与间距**：放大字号后图标不变，
 *      与 iOS/Android 系统字体设置的通行做法一致，也避免四档切换把整页布局挤变形。
 *   4. 缩放结果取整（dp）：RN 支持小数，但整数能避免不同平台上的半像素抖动。
 */

import type { TextStyle } from 'react-native';

/**
 * 四档倍率表。
 *
 * 顺序即浮层里的展示顺序（小 → 标准 → 大 → 特大）；键名同时是持久化值，
 * 改名会让老用户的设置失效，**不要改键名**。
 */
export const FONT_SCALE_MULTIPLIERS = {
  /** 小：0.875 倍。 */
  sm: 0.875,
  /** 标准：1.0 倍（默认）。 */
  md: 1.0,
  /** 大：1.125 倍。 */
  lg: 1.125,
  /** 特大：1.25 倍。 */
  xl: 1.25,
} as const;

/** 字号档位。 */
export type FontScaleLevel = keyof typeof FONT_SCALE_MULTIPLIERS;

/** 四档档位（渲染顺序）。 */
export const FONT_SCALE_LEVELS: readonly FontScaleLevel[] = ['sm', 'md', 'lg', 'xl'];

/** 默认档位：标准。 */
export const DEFAULT_FONT_SCALE: FontScaleLevel = 'md';

/**
 * 判断一个未知值是否为合法档位。
 *
 * 从存储读回的值可能是历史脏数据（旧键名、被手改），非档位一律回默认值，
 * 不让页面因为一个字符串进到「四档都不选中」的状态。
 *
 * @param value 待判断的值
 * @returns 是合法档位时返回 true
 */
export function isFontScaleLevel(value: unknown): value is FontScaleLevel {
  return typeof value === 'string' && value in FONT_SCALE_MULTIPLIERS;
}

/**
 * 取某一档的倍率。
 *
 * @param level 档位
 * @returns 倍率（0.875 / 1.0 / 1.125 / 1.25）
 */
export function fontScaleOf(level: FontScaleLevel): number {
  return FONT_SCALE_MULTIPLIERS[level];
}

/**
 * 按档位缩放一个基准字号。
 *
 * @param baseSize 基准字号（dp，一般取自 `tokens.fontSize`）
 * @param level    档位
 * @returns 缩放并取整后的字号（dp）
 */
export function scaleFontSize(baseSize: number, level: FontScaleLevel): number {
  return Math.round(baseSize * FONT_SCALE_MULTIPLIERS[level]);
}

/**
 * 按档位缩放一个文本样式（`fontSize` + 行高一起缩放，保持行高比例）。
 *
 * 为什么行高要一起缩放：只放大字号不放大行高，大字档的文字会挤在一起；
 * 只放大行高不放大字号则间距突兀。两者同倍率缩放后，各档的视觉密度一致。
 *
 * @param style 基准文本样式（一般来自 `shared/theme` 的 `typography`）
 * @param level 档位
 * @returns 新的样式对象；原样式没有 `lineHeight` 时不补
 */
export function scaleTextStyle(style: TextStyle, level: FontScaleLevel): TextStyle {
  const multiplier = FONT_SCALE_MULTIPLIERS[level];
  const scaled: TextStyle = { ...style };
  if (typeof style.fontSize === 'number') {
    scaled.fontSize = Math.round(style.fontSize * multiplier);
  }
  if (typeof style.lineHeight === 'number') {
    scaled.lineHeight = Math.round(style.lineHeight * multiplier);
  }
  return scaled;
}
