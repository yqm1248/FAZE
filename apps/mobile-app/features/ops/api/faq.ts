/**
 * 常见问题接口（APP 55 客服中心「大家常问」/ 56 常见问题）。
 *
 * 职责：从 `faq` 表拉问题与答案，页面不写死任何一条问答。
 * 调用方：`features/ops/pages/FaqPage.tsx`、`features/ops/pages/SupportPage.tsx`。
 * 数据来源：`GET /api/v1/pub/faq?category=`（免登录，只返回当前语种的启用项）。
 * 注意：
 *   1. **`category` 传空表示全部**；后端不做分页（种子只有 7 条，界面是折叠列表不是无限滚动）。
 *   2. 答案里的 `\n` 必须按行渲染（后端 `FaqVO.answer` 注释明写可含换行），
 *      直接塞进一个 `<Text>` 会把换行吃掉。
 *   3. 55 页的「大家常问」取前 5 条：稿面的 5 条是固定文案，而 `faq` 表没有点击量字段，
 *      所以按 `sortOrder` 取前 5（降级，已在段 7 工作流 §十一 登记）。
 */

import { get } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

import type { FaqCategory, FaqItem } from '../types';

/**
 * 拉取常见问题。
 *
 * @param category 分类；`undefined` 或空串表示全部
 * @returns 成功时 `data` 为按 `sortOrder` 升序的列表
 */
export function getFaqs(category?: FaqCategory): Promise<ApiResult<FaqItem[]>> {
  const query = category === undefined ? '' : `?category=${encodeURIComponent(category)}`;
  return get<FaqItem[]>(`/api/v1/pub/faq${query}`);
}
