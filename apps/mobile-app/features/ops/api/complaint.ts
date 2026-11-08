/**
 * 投诉接口（APP 57 投诉页与「我的投诉」回看）。
 *
 * 职责：提交投诉、拉我的投诉列表。
 * 调用方：`features/ops/pages/ComplaintPage.tsx`。
 * 数据来源：`POST /api/v1/app/complaints`、`GET /api/v1/app/complaints`。
 * 注意：
 *   1. **提交成功要拿 `ticketNo` 展示**（工作流 C2：用户联系客服时报单号最快定位）。
 *      后端 `ComplaintVO.ticketNo` 可能为 null（工单生成失败时降级），页面要两种文案都备。
 *   2. 律师问题（`targetType='lawyer'`）**必须带已完成订单 id**：服务端会校验归属与状态，
 *      前端先拦一道只是为了少一次往返，**不能替代服务端校验**。
 *   3. 列表只返回本人数据（归属条件在 SQL 里），前端不需要传 userId。
 *   4. 截图走三段式上传（`features/account/api/upload.ts` 的 `uploadFile`），
 *      拿到 oss_key 后原样放进 `images`，**不许自己拼路径**。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

import type { ComplaintItem, ComplaintSubmitInput, OpsPage } from '../types';

/**
 * 提交投诉。
 *
 * 副作用：写 `complaint` 一行，并在同事务里生成 `service_ticket` 一条。
 *
 * @param input 投诉入参
 * @returns 成功时 `data` 为新建的投诉行（含 `ticketNo`）
 */
export function submitComplaint(input: ComplaintSubmitInput): Promise<ApiResult<ComplaintItem>> {
  return post<ComplaintItem>('/api/v1/app/complaints', {
    targetType: input.targetType,
    targetId: input.targetId,
    reason: input.reason,
    images: input.images,
  });
}

/**
 * 拉我的投诉列表。
 *
 * @param pageNum  页码（从 1 开始）
 * @param pageSize 每页条数
 * @returns 成功时 `data` 为分页结果
 */
export function listMyComplaints(
  pageNum = 1,
  pageSize = 20,
): Promise<ApiResult<OpsPage<ComplaintItem>>> {
  return get<OpsPage<ComplaintItem>>(
    `/api/v1/app/complaints?pageNum=${String(pageNum)}&pageSize=${String(pageSize)}`,
  );
}
