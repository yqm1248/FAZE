/**
 * 图标镜像白名单。
 *
 * 依据：i18n 规范 §8.2——**只有方向性图标**要随 RTL 镜像；品牌 logo、头像、
 * 播放/暂停、对勾/叉、时钟日历一律**不镜像**（镜像了就是错）。
 * 调用方：`shared/components/DirectionalIcon.tsx`（唯一消费者）。
 * 注意：
 *   1. **白名单是「类型」不是「文档」**：不在名单里的图标名传不进 `DirectionalIcon`，
 *      「把公司 logo 镜像了」这种错误在编译期就被拦住——这比写文档提醒强，因为文档没人读。
 *   2. 图标名以 `@expo/vector-icons` 的 `Ionicons` 名为准（段 0 只用这一套）。
 */

/** 必须随 RTL 镜像的图标名（方向性语义）。 */
export const DIRECTIONAL_ICONS = [
  'arrow-back',
  'arrow-forward',
  'chevron-back',
  'chevron-forward',
  'arrow-undo',
  'arrow-redo',
] as const;

/** 方向性图标名的类型（`DirectionalIcon` 只接受它）。 */
export type DirectionalIconName = (typeof DIRECTIONAL_ICONS)[number];
