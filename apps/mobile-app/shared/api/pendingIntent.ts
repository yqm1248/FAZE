/**
 * 登录后的「去向」暂存（G7.3：点击推送 → 未登录 → 登录后继续去该会话，不丢目标）。
 *
 * 职责：在「未登录」分支记下一个待跳转路由，等登录成功后由登录页取走。
 * 调用方：`features/conversation/push.ts`（推送意图分发）、`features/auth/pages/LoginPage.tsx`（消费）。
 * 数据来源：无（进程内内存）。
 * 注意：
 *   1. **只存一条**：用户连点两条推送，以最后一条为准——同时跳两个页面做不到。
 *   2. **本轮用内存而不是 SecureStore**：真正的推送 SDK 尚未接入（G7.1 挂账），
 *      进程被杀后「点推送→登录→回会话」本来就不可达；做成持久化只会给一个假象。
 *      段 8 接 SDK 时改成 SecureStore，并补「冷启动读取」路径（本文件接口不变）。
 *   3. 只接受应用内路由字符串（`/chat?...`），**不许存外部 URL**：见 `isInternalHref`。
 */

/** 待跳转的应用内路由。 */
let pendingHref: string | null = null;

/** 应用内路由前缀白名单（防把任意字符串当路由塞进来）。 */
const INTERNAL_PREFIX = '/';

/**
 * 记下登录后要去的地方。
 *
 * @param href 应用内路由（以 `/` 开头）
 */
export function savePendingHref(href: string): void {
  pendingHref = isInternalHref(href) ? href : null;
}

/**
 * 取走待跳转路由（取走即清空，避免登录后每次启动都跳一次）。
 *
 * @returns 路由；没有则 null
 */
export function takePendingHref(): string | null {
  const value = pendingHref;
  pendingHref = null;
  return value;
}

/**
 * 判断是否是应用内路由。
 *
 * @param href 待判断字符串
 * @returns 以 `/` 开头且不含 `://` 时返回 true
 */
export function isInternalHref(href: string): boolean {
  return href.startsWith(INTERNAL_PREFIX) && !href.includes('://');
}
