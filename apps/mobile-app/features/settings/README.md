/**
 * `features/settings` —— 设置域（段 0 只含语言切换页）。
 *
 * 边界：管语言切换；不管账号设置、通知设置（段 1 起）。
 * 数据：`shared/i18n` 的 `setLang()` 与 `applyDirection()`。
 * 注意：语言页归属本域的 `pages/LanguagePage.tsx`，但用 **`profile` namespace**
 *      （i18n 规范 §4.3 把 `profile` 定义为 profile + settings 两域共用，不新增 namespace）。
 */
