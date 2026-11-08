/**
 * 充值页路由壳。
 *
 * 职责：只做一行导出（路由壳不许有逻辑，AGENTS §3.1）。
 * 调用方：expo-router 自动加载；入口在余额页与支付页「余额不足」。
 * 数据来源：无。
 * 注意：实现在 `features/trade/pages/RechargePage.tsx`；余额+档位+渠道来自
 *       `GET /app/wallet/balance` 的 `recharge` 子对象，下单走 `POST /app/wallet/recharges`。
 */

export { RechargePage as default } from '@/features/trade/pages/RechargePage';
