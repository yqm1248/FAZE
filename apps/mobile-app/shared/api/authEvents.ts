/**
 * 鉴权失效事件的发布/订阅。
 *
 * 职责：让 `client.ts` 能通知「该回登录页了」，而不 import `expo-router`。
 * 调用方：`app/_layout.tsx`（挂载时注册）、`shared/api/client.ts`（401 时触发）。
 * 注意：
 *   1. **`client.ts` 不许 import `expo-router`**：它被 `features/<域>/api` 引用，
 *      再引用 router 会形成 `router → 页面 → api → router` 的循环，且单测环境没有导航容器。
 *   2. handler 只有一个（后注册覆盖先注册）。多页面同时注册没有意义——
 *      登出动作应统一由根布局执行。
 */

/** 鉴权失效时的回调。 */
export type UnauthorizedHandler = () => void;

let handler: UnauthorizedHandler | null = null;

/**
 * 注册/注销鉴权失效回调。
 *
 * @param fn 回调；传 null 表示注销
 */
export function setUnauthorizedHandler(fn: UnauthorizedHandler | null): void {
  handler = fn;
}

/**
 * 触发鉴权失效（清 token 之后由 `client.ts` 调用）。
 *
 * 没有注册 handler 时静默返回——启动早期（根布局尚未挂载）发生 401 不应崩。
 */
export function emitUnauthorized(): void {
  handler?.();
}
