/**
 * 语言切换的持久化与方向应用（页面唯一可调入口）。
 *
 * 职责：把「切换语言」的三件事收敛成一个函数——写持久化、应用方向、交给调用方决定是否重启。
 * 调用方：`features/settings/pages/LanguagePage.tsx`。
 * 数据来源：`shared/api/tokenStore`（`faze.lang`）与 `shared/i18n/rtl`。
 * 注意：
 *   1. **顺序不能改**：先写持久化（重启后靠它恢复），再 `applyDirection`（写 forceRTL 标记），
 *      最后才 `reloadAsync`。任何一步提前都会出现「切换时镜像、重启后回弹」。
 *   2. 页面**不许**自己拼 `faze.lang` 键名、不许自己调 `I18nManager`——都经本文件。
 *   3. 同向切换（zh ↔ bo）不需要重启；函数返回 false 时页面直接重渲染即可。
 */

import { writeStoredLang } from '@/shared/api/tokenStore';
import type { Lang } from '@/shared/i18n';
import { applyDirection } from '@/shared/i18n/rtl';

/**
 * 切换语言。
 *
 * 副作用：写 SecureStore 的语言键；异向时写 `forceRTL` 标记并请求软重启。
 *
 * @param next    目标语言
 * @param current 当前语言（用于判断是否异向）
 * @returns 是否已请求重启（true = 调用了 `reloadAsync`；false = 同向，直接重渲染）
 */
export async function applyLocale(next: Lang, current: Lang): Promise<boolean> {
  // 任务 1：先落盘——重启后 `app/_layout.tsx` 靠这个值恢复语言。
  await writeStoredLang(next);

  // 任务 2：写方向标记；异向时内部会尝试 reloadAsync。
  return applyDirection(next, current);
}
