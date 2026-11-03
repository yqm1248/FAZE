/**
 * 字体族常量与资源映射。
 *
 * 依据：i18n 规范 §7（少数民族文字必须自备字体）、技术路线 §13.3.1（按 Unicode 区段切 run）。
 * 调用方：`shared/components/LocalizedText.tsx`（字体族名）、`app/_layout.tsx`（`useFonts` 加载）。
 * 注意：
 *   1. **`FONT_FAMILY` 的值必须与 `useFonts` 的 key 完全一致**，否则加载了却引不到（回退系统字体）。
 *   2. 字体授权是 **SIL Open Font License 1.1**（可商用、可再分发，须保留版权与许可文本）。
 *      许可原文在 `assets/fonts/OFL-*.txt`，**不许删**。
 *   3. 藏文用 **Noto Serif Tibetan**：Google Fonts 仓库里**只收录了 Serif 版**，
 *      `Noto Sans Tibetan` 在 `google/fonts` 的 `ofl/` 下不存在（2026-11-04 实测 404）。
 *      若将来拿到 Sans 版，只需换 `FONT_ASSETS` 与 `FONT_FAMILY.tibetan` 两处。
 */

/** 字体族名（与 `useFonts` 的 key 必须一致）。 */
export const FONT_FAMILY = {
  /** 藏文。 */
  tibetan: 'NotoSerifTibetan',
  /** 阿拉伯系（维吾尔文 / 哈萨克文共用）。 */
  arabic: 'NotoNaskhArabic',
} as const;

/**
 * 字体资源映射（交给 `expo-font` 的 `useFonts`）。
 *
 * 可变字体（`[wght]`）在 RN 里按默认实例加载；段 0 只用 Regular 字形，不涉及字重切换。
 */
export const FONT_ASSETS = {
  [FONT_FAMILY.tibetan]: require('../../assets/fonts/NotoSerifTibetan-Variable.ttf'),
  [FONT_FAMILY.arabic]: require('../../assets/fonts/NotoNaskhArabic-Variable.ttf'),
} as const;
