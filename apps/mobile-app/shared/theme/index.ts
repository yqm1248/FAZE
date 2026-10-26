/**
 * 主题层统一出口。
 *
 * 页面与组件通过 `@/shared/theme` 取用设计令牌与文本样式，
 * 不直接引用 `tokens.ts` 的内部结构，便于将来调整分组而不改动调用方。
 */

export {
  color,
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

export { typography } from './typography';
