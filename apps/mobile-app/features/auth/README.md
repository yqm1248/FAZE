/**
 * `features/auth` —— 登录与鉴权域（段 0 只含登录页）。
 *
 * 边界：管手机号密码登录；不管注册、忘记密码、短信验证码（段 1）。
 * 数据：`shared/api/client.ts` → `POST /api/v1/pub/auth/login/password`。
 * 注意：本目录在 B1 阶段先建空目录占位，C1 才写 `pages/LoginPage.tsx`；
 *      页面**不许**自己读 SecureStore 或自己拼请求头，一律走 `features/auth/api/auth.ts`。
 */
