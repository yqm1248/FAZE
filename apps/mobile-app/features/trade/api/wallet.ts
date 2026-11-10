/**
 * 钱包接口（余额 / 资金流水）。
 *
 * 职责：「账户余额与明细」页的唯一数据入口。
 * 调用方：`features/trade/pages/BalancePage.tsx`。
 * 数据来源：`GET /api/v1/app/wallet/balance`、`GET /api/v1/app/wallet/flows`。
 * 注意：
 *   1. **金额一律整数分**（字符串形式的 Long，客户端转数字只为展示，不做累加）。
 *   2. 余额接口返回的是**流水汇总值**（后端 `1-A6` 的保证），不是缓存字段。
 *   3. 流水分页响应是三段式 `list / total / pageNum / pageSize / hasMore`；
 *      `hasMore` 由服务端算，前端不要拿 total 自己推。
 *   4. `beginTime` / `endTime` 是 ISO-8601 本地时间（`2026-11-01T00:00:00`），
 *      后端用 `@DateTimeFormat(iso = DATE_TIME)` 解析。
 */

import { get } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 余额出参（`BalanceVO`，单位：分）。 */
export type BalanceData = {
  availableCents: string;
  frozenCents: string;
  totalCents: string;
};

/**
 * 流水业务类型。
 *
 * 与后端 `balance_flow.biz_type` 一一对应（不区分 `owner_type`）：`income_unfreeze` 只由律师侧
 * 收入解冻产生，用户余额页不会出现，但类型清单仍要补齐——漏一个值会让前端映射表出现空洞。
 */
export type FlowBizType =
  | 'recharge'
  | 'consume'
  | 'refund'
  | 'income'
  | 'withdraw'
  | 'freeze'
  | 'unfreeze'
  | 'income_unfreeze'
  | 'adjust';

/** 资金流水项（`FlowVO`）。 */
export type FlowItem = {
  /** 流水主键（字符串，禁止 `Number()`）。 */
  id: string;
  /** 业务类型。 */
  bizType: string;
  /** 变动金额（分）：正入账、负出账。 */
  deltaCents: string;
  /** 变动后总余额（分）。 */
  balanceAfterCents: string;
  /** 备注。 */
  remark: string | null;
  /** 发生时间（UTC，无时区后缀）。 */
  createdAt: string;
};

/** C 端分页响应（`PageResult`）。 */
export type FlowPage = {
  list: FlowItem[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/** 流水查询条件。 */
export type FlowQuery = {
  pageNum: number;
  pageSize: number;
  /** 业务类型筛选，不传则全部。 */
  bizType?: FlowBizType;
  /** 起始时间（含），ISO-8601 本地时间。 */
  beginTime?: string;
  /** 结束时间（含），ISO-8601 本地时间。 */
  endTime?: string;
};

/**
 * 查询余额。
 *
 * @returns 成功时 `data` 为可用/冻结/总额（分）
 */
export function getBalance(): Promise<ApiResult<BalanceData>> {
  return get<BalanceData>('/api/v1/app/wallet/balance');
}

/**
 * 分页查询资金流水。
 *
 * @param query 页码、条数与筛选条件
 * @returns 成功时 `data` 为分页结果
 */
export function getFlows(query: FlowQuery): Promise<ApiResult<FlowPage>> {
  const params = new URLSearchParams({
    pageNum: String(query.pageNum),
    pageSize: String(query.pageSize),
  });
  if (query.bizType !== undefined) {
    params.set('bizType', query.bizType);
  }
  if (query.beginTime !== undefined) {
    params.set('beginTime', query.beginTime);
  }
  if (query.endTime !== undefined) {
    params.set('endTime', query.endTime);
  }
  return get<FlowPage>(`/api/v1/app/wallet/flows?${params.toString()}`);
}
