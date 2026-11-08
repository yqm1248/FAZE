/**
 * 案例收藏接口（段 5 G2）。
 *
 * 职责：案例详情页底部「收藏」按钮的唯一数据入口。
 * 调用方：`features/content/pages/CaseDetailPage.tsx`。
 * 数据来源：`POST /api/v1/app/case-library/{id}/favorite`（收藏）/
 *          `DELETE /api/v1/app/case-library/{id}/favorite`（取消）。
 * 注意：
 *   1. **复用段 2 的 `social_action` 表**（`action_type=favorite`、`target_type=case_library`），
 *      不新建收藏表（工作流 G2 第 2 条）。
 *   2. 服务端两个方向都幂等：重复收藏 / 重复取消都返回成功。
 *   3. 收藏状态以服务端为准：详情接口回 `favorited`，本文件只负责写。
 */

import { request } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/**
 * 收藏或取消收藏。
 *
 * @param caseId 案例主键
 * @param favorite true = 收藏，false = 取消收藏
 * @returns 成功时空数据
 */
export function toggleCaseFavorite(
  caseId: string,
  favorite: boolean,
): Promise<ApiResult<null>> {
  return request<null>(
    `/api/v1/app/case-library/${encodeURIComponent(caseId)}/favorite`,
    { method: favorite ? 'POST' : 'DELETE' },
  );
}
