/**
 * 充值接口（下单 / 单笔查询 / 记录列表 / Mock 支付确认）。
 *
 * 职责：充值页与充值记录的唯一数据入口。
 * 调用方：`features/trade/pages/RechargePage.tsx`。
 * 数据来源：`POST /api/v1/app/wallet/recharges`、`GET /api/v1/app/wallet/recharges/{orderNo}`、
 *           `GET /api/v1/app/wallet/recharges`、`GET /api/v1/app/wallet/balance`、
 *           `POST /api/v1/pub/pay/callback/mock`（仅 Mock 通道联调用）。
 * 注意：
 *   1. **幂等键必带**：下单请求的 `Idempotency-Key` 由页面用一次性的随机串生成，
 *      同一串在 24 小时内只会落一张单（服务端 Redis 去重）。失败重试要复用同一串，
 *      否则「点两次出两单」。
 *   2. **金额单位是分**：页面里输入的是元，转分只在这里做一次，不在渲染层反复换算。
 *   3. `payParams` 是渠道给的公开参数，**不含密钥**；Mock 通道里带 `callbackBody`，
 *      页面拿它去打 mock 回调接口模拟「渠道通知平台」。
 *   4. 列表是游标分页：`nextCursor` 原样回传，不要自己算 offset。
 */

import { get, post, request } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

import type {
  RechargeCreateResult,
  RechargeDetail,
  RechargeListPage,
  WalletBalanceWithPanel,
} from '../types';

/**
 * 查询余额与充值页参数。
 *
 * @returns 成功时 `data` 含可用余额（字符串分）与 `recharge` 面板参数
 */
export function getWalletWithPanel(): Promise<ApiResult<WalletBalanceWithPanel>> {
  return get<WalletBalanceWithPanel>('/api/v1/app/wallet/balance');
}

/**
 * 发起充值下单。
 *
 * @param amountCents    充值金额（分，整数）
 * @param channel        渠道码（wechat / alipay / mock）
 * @param idempotencyKey 幂等键（同一次点击的重试必须复用同一个值）
 * @returns 成功时 `data` 为单号与拉起渠道的参数
 */
export function createRecharge(
  amountCents: number,
  channel: string,
  idempotencyKey: string,
): Promise<ApiResult<RechargeCreateResult>> {
  return post<RechargeCreateResult>(
    '/api/v1/app/wallet/recharges',
    { amountCents, channel },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  );
}

/**
 * 查询单笔充值单。
 *
 * @param orderNo 平台充值单号
 * @returns 成功时 `data` 为状态、金额、渠道、余额
 */
export function getRecharge(orderNo: string): Promise<ApiResult<RechargeDetail>> {
  return get<RechargeDetail>(`/api/v1/app/wallet/recharges/${encodeURIComponent(orderNo)}`);
}

/**
 * 查询充值记录（游标分页）。
 *
 * @param cursor 上一页最后一条的 id，首页传 undefined
 * @param limit  每页条数
 * @returns 成功时 `data` 为本页条目与下一页游标
 */
export function listRecharges(cursor?: string, limit = 20): Promise<ApiResult<RechargeListPage>> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor !== undefined && cursor.length > 0) {
    params.set('cursor', cursor);
  }
  return get<RechargeListPage>(`/api/v1/app/wallet/recharges?${params.toString()}`);
}

/**
 * 模拟渠道通知（仅 Mock 通道可用）。
 *
 * 为什么要这个函数：Expo 端没有微信/支付宝的 RN 插件，Mock 通道跑的是
 * 「页面内模拟确认 → 把预生成的回调报文打给平台」这条链路，用来验回调与入账
 * （锚点 6-A2 / 6-A3）。真实渠道接入后这个函数只在开发环境用。
 *
 * 报文与应答都不是 JSON（P0-1 的两处断点）：
 *   1. `callbackBody` 是 `MockPaymentGateway#buildCallbackBody` 生成的
 *      `application/x-www-form-urlencoded` 表单串（`orderNo=..&amountCents=..&sign=..`）。
 *      对表单串做 `JSON.parse` 必抛 SyntaxError；用 JSON.stringify 发出去服务端也解析不出来。
 *      → 走 client 的 `bodyMode: 'text'` 原样发送，显式声明表单 Content-Type。
 *   2. 成功应答是 `{"code":"SUCCESS"}`（不是 `R<T>`，见 RechargeCallbackService），
 *      套 envelope 解析会永远 `ok:false` → 用 `successMode: 'http'` 按 HTTP 200 判成功。
 *
 * @param callbackBody Mock 网关预生成的回调报文（表单串，不是 JSON）
 * @returns 成功（HTTP 200）时 `data` 为 null
 */
export function mockNotify(callbackBody: string): Promise<ApiResult<null>> {
  return request<null>('/api/v1/pub/pay/callback/mock', {
    method: 'POST',
    body: callbackBody,
    bodyMode: 'text',
    contentType: 'application/x-www-form-urlencoded;charset=UTF-8',
    successMode: 'http',
    auth: false,
  });
}
