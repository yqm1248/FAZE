/**
 * 全局搜索接口（段 5 G3：40-search 四域分域）。
 *
 * 职责：搜索页的唯一数据入口，以及「法条原文」弹层的查询。
 * 调用方：`features/content/pages/SearchPage.tsx`、`CaseDetailPage.tsx`（点法条看原文）。
 * 数据来源：`GET /api/v1/app/search`、`GET /api/v1/pub/law-items`。
 * 注意：
 *   1. **响应必须分域**：`lawyers` / `articles` / `cases` / `documents` 四个对象，
 *      单域搜索时其余为 undefined（后端 `@JsonInclude(NON_NULL)` 不输出）。
 *   2. 单域页调用时传 `type`；综合页不传。
 *   3. 搜索历史存本地（`AsyncStorage`，最多 10 条），**不上服务端**（工作流 G3 第 7 条）。
 *   4. 「文书」域跳段 4 的模板表单页，**不带价格**（V1 不作文书付费）。
 */

import { get } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 分域结果容器。 */
export type SearchGroup<T> = {
  list: T[];
  total: number;
};

/** 律师命中。 */
export type LawyerHit = {
  id: string;
  name: string | null;
  lawFirm: string | null;
  specialtiesText: string | null;
  avatarUrl: string | null;
};

/** 文章命中。 */
export type ArticleHit = {
  id: string;
  title: string | null;
  category: string | null;
  publishedAt: string | null;
};

/** 案例命中。 */
export type CaseHit = {
  id: string;
  title: string | null;
  categoryLabel: string | null;
  source: string | null;
};

/** 文书模板命中。 */
export type DocTemplateHit = {
  id: string;
  name: string | null;
  category: string | null;
};

/** 搜索响应（分域）。 */
export type SearchResult = {
  lawyers?: SearchGroup<LawyerHit>;
  articles?: SearchGroup<ArticleHit>;
  cases?: SearchGroup<CaseHit>;
  documents?: SearchGroup<DocTemplateHit>;
};

/** 单域类型。 */
export type SearchType = 'lawyer' | 'article' | 'case' | 'document';

/**
 * 全局搜索。
 *
 * @param kw   关键词
 * @param type 单域类型；null 表示综合
 * @returns 分域结果
 */
export function searchAll(
  kw: string,
  type: SearchType | null,
): Promise<ApiResult<SearchResult>> {
  const params = new URLSearchParams({ kw });
  if (type !== null) {
    params.set('type', type);
  }
  return get<SearchResult>(`/api/v1/app/search?${params.toString()}`);
}

/** 法条原文（后端 LawItemBrief 形状）。 */
export type LawItemBrief = {
  id: string;
  lawCode: string | null;
  lawName: string | null;
  articleNo: string | null;
  content: string | null;
  status: string | null;
};

/**
 * 按关键词检索法条（案例详情页「相关法条」点击时查原文）。
 *
 * @param kw   关键词（法律名 + 条号）
 * @param size 每页条数
 * @returns 法条列表
 */
export function searchLawItems(kw: string, size = 5): Promise<ApiResult<{ list: LawItemBrief[] }>> {
  return get<{ list: LawItemBrief[] }>(
    `/api/v1/pub/law-items?kw=${encodeURIComponent(kw)}&pageSize=${size}`,
  );
}
