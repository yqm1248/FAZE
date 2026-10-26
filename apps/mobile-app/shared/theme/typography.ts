import type { TextStyle } from 'react-native';

import { fontSize, fontWeight, lineHeight } from './tokens';

/**
 * 文本样式组合。
 *
 * 说明为什么单独建一层而不是在页面里手写 `fontSize + lineHeight`：
 *
 * 1. RN 的 `lineHeight` 是绝对像素值而不是倍数，原项目的 CSS 行高（1.5、1.6、1.8）
 *    必须换算成"字号 × 倍数"。若在各页面手算，很容易出现 14×1.5=21 与 14×1.6=22.4
 *    这类不一致，破坏垂直节奏。
 * 2. 标题统一使用 `tight` 行高、列表正文使用 `relaxed`、长段落使用 `loose`，
 *    这套对应关系只应该在这里维护一次。
 */
export const typography = {
  /** 24dp / 1.3 —— 首页标语、认证页主标题。 */
  h1: {
    fontSize: fontSize.h1,
    fontWeight: fontWeight.bold,
    lineHeight: Math.round(fontSize.h1 * lineHeight.tight),
  } satisfies TextStyle,

  /** 17dp / 1.3 —— 页面标题。 */
  h2: {
    fontSize: fontSize.h2,
    fontWeight: fontWeight.bold,
    lineHeight: Math.round(fontSize.h2 * lineHeight.tight),
  } satisfies TextStyle,

  /** 16dp / 1.3 —— 区块标题。 */
  h3: {
    fontSize: fontSize.h3,
    fontWeight: fontWeight.bold,
    lineHeight: Math.round(fontSize.h3 * lineHeight.tight),
  } satisfies TextStyle,

  /** 15dp / 1.5 —— 正文、律师姓名。 */
  bodyLarge: {
    fontSize: fontSize.bodyLarge,
    fontWeight: fontWeight.regular,
    lineHeight: Math.round(fontSize.bodyLarge * lineHeight.normal),
  } satisfies TextStyle,

  /** 14dp / 1.6 —— 列表正文。原 `.law-title { line-height: 1.6 }`。 */
  body: {
    fontSize: fontSize.body,
    fontWeight: fontWeight.regular,
    lineHeight: Math.round(fontSize.body * lineHeight.relaxed),
  } satisfies TextStyle,

  /** 13dp / 1.5 —— 次要说明。 */
  bodySmall: {
    fontSize: fontSize.bodySmall,
    fontWeight: fontWeight.regular,
    lineHeight: Math.round(fontSize.bodySmall * lineHeight.normal),
  } satisfies TextStyle,

  /** 12dp / 1.5 —— 功能卡文字、标签。 */
  caption: {
    fontSize: fontSize.caption,
    fontWeight: fontWeight.regular,
    lineHeight: Math.round(fontSize.caption * lineHeight.normal),
  } satisfies TextStyle,

  /** 11dp / 1.5 —— meta 时间、状态标签。 */
  micro: {
    fontSize: fontSize.micro,
    fontWeight: fontWeight.regular,
    lineHeight: Math.round(fontSize.micro * lineHeight.normal),
  } satisfies TextStyle,

  /**
   * 长段落。原律师详情个人简介 `line-height: 1.8`。
   * 与 `bodyLarge` 的区别只在行高，用于连续多行的说明性文本。
   */
  paragraph: {
    fontSize: fontSize.bodyLarge,
    fontWeight: fontWeight.regular,
    lineHeight: Math.round(fontSize.bodyLarge * lineHeight.loose),
  } satisfies TextStyle,
} as const;
