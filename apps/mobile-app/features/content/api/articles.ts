/**
 * 文章接口（G8.1：列表与详情，四语言 + 降级）。
 *
 * 职责：首页「每日学法」、文章列表页、文章详情页的唯一数据入口。
 * 调用方：`features/content/pages/{HomePage,ArticleListPage,ArticleDetailPage}.tsx`。
 * 数据来源：
 *  - `GET /api/v1/pub/articles?lang=&category=&pageNum=&pageSize=`
 *  - `GET /api/v1/pub/articles/{id}?lang=`
 * 注意：
 *   1. **正文是块数组不是 HTML**：`blocks[].type` 决定渲染方式
 *      （paragraph / heading / law_ref / list / image），法条引用块可点进法条库原文。
 *   2. **`langFallback=true` 表示当前语言没有译文、展示的是简体**：
 *      页面必须给出「暂无该语言版本」提示，不能假装是译文（工作流 G8.1 第 2 条）。
 *   3. 语言不单独传参也行：客户端请求头统一带 `Accept-Language`（`shared/api/client.ts`），
 *      服务端会读；这里显式传 `lang` 是为了「切换语言后立刻重拉」时不依赖头。
 *   4. 文章是公开内容，用 `/pub` 前缀（游客可读）；接口同时挂在 `/app` 下。
 */

import { get } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 文章列表行（`ArticleCardVO`）。 */
export type ArticleCard = {
  /** 文章主键（字符串）。 */
  id: string;
  /** 标题（按请求语言；降级时为简体）。 */
  title: string;
  /** 摘要。 */
  summary: string | null;
  /** 分类。 */
  category: string | null;
  /** 封面可读地址。 */
  coverUrl: string | null;
  /** 阅读数。 */
  viewCount: number;
  /** 上架时间（UTC）。 */
  publishedAt: string | null;
  /** 是否语言降级。 */
  langFallback: boolean;
};

/** 文章内容块（`ArticleBlockVO`）。 */
export type ArticleBlock = {
  /** 块类型：paragraph / heading / law_ref / list / image。 */
  type: string;
  /** 文本（paragraph / heading）。 */
  text: string | null;
  /** 标题层级（heading）。 */
  level: number | null;
  /** 列表项（list）。 */
  items: string[] | null;
  /** 是否有序（list）。 */
  ordered: boolean | null;
  /** 法律名称（law_ref）。 */
  lawName: string | null;
  /** 条文号（law_ref）。 */
  articleNo: string | null;
  /** 法条库主键（law_ref；为空表示不可跳转）。 */
  lawItemId: string | null;
  /** 法条原文（law_ref；服务端已补）。 */
  lawContent: string | null;
  /** 图片地址（image）。 */
  url: string | null;
  /** 图片替代文字（image）。 */
  alt: string | null;
  /** 图片说明（image）。 */
  caption: string | null;
};

/** 文章详情（`ArticleDetailVO`）。 */
export type ArticleDetail = {
  /** 文章主键。 */
  id: string;
  /** 标题。 */
  title: string;
  /** 摘要。 */
  summary: string | null;
  /** 分类。 */
  category: string | null;
  /** 封面地址。 */
  coverUrl: string | null;
  /** 阅读数。 */
  viewCount: number;
  /** 上架时间（UTC）。 */
  publishedAt: string | null;
  /** 正文块。 */
  blocks: ArticleBlock[];
  /** 是否语言降级。 */
  langFallback: boolean;
};

/** 文章分页结果。 */
export type ArticlePage = {
  list: ArticleCard[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/** 列表查询条件。 */
export type ArticleQuery = {
  /** 页码（从 1 开始）。 */
  pageNum: number;
  /** 每页条数（服务端 clamp 1~100）。 */
  pageSize: number;
  /** 分类筛选；不传 = 全部。 */
  category?: string;
  /** 语言；不传则用请求头 Accept-Language。 */
  lang?: string;
};

/**
 * 分页查文章列表。
 *
 * @param query 页码 / 条数 / 分类 / 语言
 * @returns 成功时 `data` 为分页结果
 */
export function getArticles(query: ArticleQuery): Promise<ApiResult<ArticlePage>> {
  const params = new URLSearchParams({
    pageNum: String(query.pageNum),
    pageSize: String(query.pageSize),
  });
  if (query.category !== undefined && query.category !== '') {
    params.set('category', query.category);
  }
  if (query.lang !== undefined && query.lang !== '') {
    params.set('lang', query.lang);
  }
  return get<ArticlePage>(`/api/v1/pub/articles?${params.toString()}`);
}

/**
 * 查文章详情。
 *
 * @param articleId 文章主键
 * @param lang      语言（可空）
 * @returns 成功时 `data` 为文章详情
 */
export function getArticle(articleId: string, lang?: string): Promise<ApiResult<ArticleDetail>> {
  const suffix = lang === undefined || lang === '' ? '' : `?lang=${encodeURIComponent(lang)}`;
  return get<ArticleDetail>(`/api/v1/pub/articles/${encodeURIComponent(articleId)}${suffix}`);
}
