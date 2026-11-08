/**
 * 案例库接口（段 5 G2：04-case 列表 / 19-case-detail 详情）。
 *
 * 职责：案例列表与详情页的唯一数据入口。
 * 调用方：`features/content/pages/CaseListPage.tsx`、`CaseDetailPage.tsx`。
 * 数据来源：`/api/v1/app/case-library/**`。
 * 注意：
 *   1. **只返回已上线案例**（服务端保证）；已下架详情返回 409002，页面走「已下架」空态。
 *   2. 列表条目只有 `id` + `title` + `caseType`：设计稿明确列表不放来源/日期/缩略图
 *      （工作流 G2 第 1 条），不要在前端补字段。
 *   3. `lang` 显式传：App 的 i18n 语言 = 请求语言，缺译时服务端回退中文并给 `translated=false`。
 *   4. 游标分页：`cursor` 传上一页最后一条 id。
 */

import { get } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 案例列表条目（后端 CaseCardVO）。 */
export type CaseCard = {
  id: string;
  title: string;
  caseType: string;
};

/** 案例列表分页（后端 CaseListPageVO）。 */
export type CaseListPage = {
  list: CaseCard[];
  nextCursor: string | null;
  hasMore: boolean;
};

/** 案例法条引用（后端 CaseDetailVO.LawRefVO）。 */
export type CaseLawRef = {
  lawName: string;
  articleNo: string;
  lawItemId: string | null;
};

/** 案例详情（后端 CaseDetailVO）。 */
export type CaseDetail = {
  id: string;
  title: string;
  keywords: string | null;
  source: string | null;
  judgmentYear: number | null;
  viewCount: number | null;
  summary: string | null;
  disputeFocus: string | null;
  judgment: string | null;
  insight: string | null;
  lawRefs: CaseLawRef[];
  translated: boolean;
  /** 当前用户是否已收藏（未登录恒 false）。 */
  favorited: boolean;
  disclaimer: string;
};

/**
 * 按大类拉案例（游标分页）。
 *
 * @param type   大类 civil / administrative / criminal；不传表示全部
 * @param cursor 游标（上一页最后一条 id）
 * @param lang   语言（zh-Hans / bo / ug-Arab-CN / kk-Arab-CN）
 * @param limit  每页条数
 * @returns 分页结果
 */
export function fetchCases(
  type: string | null,
  cursor: string | null,
  lang: string,
  limit = 10,
): Promise<ApiResult<CaseListPage>> {
  const params = new URLSearchParams({ limit: String(limit), lang });
  if (type !== null) {
    params.set('type', type);
  }
  if (cursor !== null) {
    params.set('cursor', cursor);
  }
  return get<CaseListPage>(`/api/v1/app/case-library?${params.toString()}`);
}

/**
 * 拉案例详情（按语言，缺译回退中文）。
 *
 * @param id   案例主键
 * @param lang 语言
 * @returns 详情
 */
export function fetchCaseDetail(id: string, lang: string): Promise<ApiResult<CaseDetail>> {
  return get<CaseDetail>(
    `/api/v1/app/case-library/${encodeURIComponent(id)}?lang=${encodeURIComponent(lang)}`,
  );
}
