/**
 * 意见反馈接口（APP 58 反馈页）。
 *
 * 职责：提交反馈、拉我的反馈列表。
 * 调用方：`features/ops/pages/FeedbackPage.tsx`。
 * 数据来源：`POST /api/v1/app/feedback`、`GET /api/v1/app/feedback`。
 * 注意：
 *   1. **`content` 选填**（稿面「选填，填写有助于更快处理」）：空串也要能提交，
 *      服务端会用类型标签兜底填 `content` 列。前端不要因为内容为空就禁用按钮。
 *   2. `contact` 选填但**填了必须是合法手机号**：后端正则是 `^$|^1[3-9]\d{9}$`，
 *      前端先拦一道给出即时提示，避免用户填完才发现提交失败。
 *   3. 提交同样会生成工单（`service_ticket`），但反馈页**不强调单号**——
 *      稿面只承诺「每条都会有人看，不承诺逐条回复」。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

import type { FeedbackItem, FeedbackSubmitInput, OpsPage } from '../types';

/**
 * 提交意见反馈。
 *
 * 副作用：写 `feedback` 一行，并在同事务里生成 `service_ticket` 一条。
 *
 * @param input 反馈入参
 * @returns 成功时 `data` 为新建的反馈行
 */
export function submitFeedback(input: FeedbackSubmitInput): Promise<ApiResult<FeedbackItem>> {
  return post<FeedbackItem>('/api/v1/app/feedback', {
    type: input.type,
    content: input.content,
    images: input.images,
    contact: input.contact,
  });
}

/**
 * 拉我的反馈列表。
 *
 * @param pageNum  页码（从 1 开始）
 * @param pageSize 每页条数
 * @returns 成功时 `data` 为分页结果
 */
export function listMyFeedback(
  pageNum = 1,
  pageSize = 20,
): Promise<ApiResult<OpsPage<FeedbackItem>>> {
  return get<OpsPage<FeedbackItem>>(
    `/api/v1/app/feedback?pageNum=${String(pageNum)}&pageSize=${String(pageSize)}`,
  );
}
