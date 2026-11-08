/**
 * 律师浏览接口（列表 / 详情 / 评价 / 关注）。
 *
 * 职责：`features/lawyer` 下浏览类页面的唯一数据入口。
 * 调用方：`pages/LawyerListPage.tsx`（G3.1）、`pages/LawyerDetailPage.tsx`（G3.2）。
 * 数据来源：`GET /api/v1/app/lawyers`、`GET /api/v1/app/lawyers/{id}`、
 *          `GET /api/v1/app/lawyers/{id}/reviews`、`POST/DELETE /api/v1/app/lawyers/{id}/follow`。
 * 注意：
 *   1. **列表与详情是公开接口**（未登录也能看）：找律师对游客开放，后端 SecurityConfig 已放行
 *      这三个 GET；关注接口需要登录。
 *   2. 金额一律整数分；`specialties` 出参已经是数组（服务端解析 JSON），前端不要再解析。
 *   3. 分页是 C 端 `list` 三段式（list/total/pageNum/pageSize/hasMore），不是游标。
 */

import { get, post, request } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 律师列表卡片（`LawyerCardVO`）。 */
export type LawyerCard = {
  /** 律师主键（字符串，禁止 `Number()`）。 */
  id: string;
  /** 执业姓名。 */
  realName: string;
  /** 头像地址。 */
  avatarUrl: string | null;
  /** 执业律所。 */
  lawFirm: string | null;
  /** 执业地区。 */
  region: string | null;
  /** 执业年限。 */
  practiceYears: number | null;
  /** 擅长领域数组。 */
  specialties: string[];
  /** 评分 0.0~5.0。 */
  rating: string | null;
  /** 评价数。 */
  reviewCount: number | null;
  /** 累计接单数。 */
  orderCount: number | null;
  /** 在线状态：online / busy / offline。 */
  onlineStatus: string | null;
  /** 图文咨询价格（分）。 */
  consultPriceCents: string | null;
  /** 电话咨询价格（分）。 */
  phonePriceCents: string | null;
  /** 文书代写价格（分）。 */
  documentPriceCents: string | null;
};

/** 律师列表分页响应。 */
export type LawyerPage = {
  list: LawyerCard[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/** 律师列表查询条件。 */
export type LawyerListQuery = {
  /** 页码，从 1 开始。 */
  pageNum: number;
  /** 每页条数（服务端 clamp 到 1~100）。 */
  pageSize: number;
  /** 分类：空=全部；否则为「民事纠纷 / 刑事辩护 / 婚姻家事 / 合同纠纷」之一。 */
  category?: string;
  /** 关键词（姓名 / 地区 / 律所）。 */
  keyword?: string;
};

/**
 * 分页查询律师列表。
 *
 * @param query 页码、条数、分类与关键词
 * @returns 成功时 `data` 为分页结果
 */
export function getLawyers(query: LawyerListQuery): Promise<ApiResult<LawyerPage>> {
  const params = new URLSearchParams({
    pageNum: String(query.pageNum),
    pageSize: String(query.pageSize),
  });
  if (query.category !== undefined && query.category !== '') {
    params.set('category', query.category);
  }
  if (query.keyword !== undefined && query.keyword.trim() !== '') {
    params.set('keyword', query.keyword.trim());
  }
  return get<LawyerPage>(`/api/v1/app/lawyers?${params.toString()}`);
}

/** 律师详情（`LawyerDetailVO`，G3.2 使用，字段在那一提交补齐）。 */
export type LawyerDetail = LawyerCard & {
  /** 个人简介。 */
  bio: string | null;
  /** 图文咨询是否上架。 */
  serviceTextOn: boolean;
  /** 电话咨询是否上架。 */
  servicePhoneOn: boolean;
  /** 文书代写是否上架。 */
  serviceDocOn: boolean;
  /** 当前用户是否已关注；未登录时为 false。 */
  followed: boolean;
};

/**
 * 查询律师详情。
 *
 * @param lawyerId 律师主键
 * @returns 成功时 `data` 为律师详情
 */
export function getLawyerDetail(lawyerId: string): Promise<ApiResult<LawyerDetail>> {
  return get<LawyerDetail>(`/api/v1/app/lawyers/${encodeURIComponent(lawyerId)}`);
}

/** 律师评价行（`LawyerReviewVO`）。 */
export type LawyerReview = {
  /** 评价主键。 */
  id: string;
  /** 评分 1~5。 */
  rating: number;
  /** 标签数组。 */
  tags: string[];
  /** 评价正文。 */
  content: string | null;
  /** 律师回复。 */
  lawyerReply: string | null;
  /** 是否匿名。 */
  anonymous: boolean;
  /** 评价人昵称；匿名为 null。 */
  userNickname: string | null;
  /** 评价时间（UTC）。 */
  createdAt: string;
};

/** 评价分页响应。 */
export type LawyerReviewPage = {
  list: LawyerReview[];
  total: number;
  pageNum: number;
  pageSize: number;
  hasMore: boolean;
};

/**
 * 查询某律师的评价（分页）。
 *
 * @param lawyerId 律师主键
 * @param pageNum  页码（从 1 开始）
 * @param pageSize 每页条数
 * @returns 成功时 `data` 为评价分页结果
 */
export function getLawyerReviews(
  lawyerId: string,
  pageNum: number,
  pageSize: number,
): Promise<ApiResult<LawyerReviewPage>> {
  const params = new URLSearchParams({
    pageNum: String(pageNum),
    pageSize: String(pageSize),
  });
  return get<LawyerReviewPage>(
    `/api/v1/app/lawyers/${encodeURIComponent(lawyerId)}/reviews?${params.toString()}`,
  );
}

/** 关注律师（需登录）。 */
export function followLawyer(lawyerId: string): Promise<ApiResult<null>> {
  return post<null>(`/api/v1/app/lawyers/${encodeURIComponent(lawyerId)}/follow`);
}

/** 取消关注（需登录）。 */
export function unfollowLawyer(lawyerId: string): Promise<ApiResult<null>> {
  return request<null>(`/api/v1/app/lawyers/${encodeURIComponent(lawyerId)}/follow`, { method: 'DELETE' });
}
