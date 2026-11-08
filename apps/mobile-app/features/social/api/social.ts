/**
 * social 域接口（我的收藏 / 关注律师）。
 *
 * 职责：段 7 E3 的 4 个接口 + 取消关注的唯一数据入口。
 * 调用方：`features/social/pages/FavoritesPage.tsx`、`FollowingPage.tsx`。
 * 数据来源：
 *   - `GET /api/v1/app/social/favorites?targetType=&cursor=`（收藏列表 + 三分类计数）；
 *   - `DELETE /api/v1/app/social/favorites?targetType=&targetId=`（取消收藏，服务端做归属校验）；
 *   - `GET /api/v1/app/social/follows?cursor=`（关注律师列表）；
 *   - `GET /api/v1/app/social/follow-count`（关注数）；
 *   - `DELETE /api/v1/app/social/follows?lawyerId=`（取消关注，见注意事项 2）。
 * 注意：
 *   1. **收藏页三 Tab 的数字来自一次请求**：`GET favorites` 的出参里带 `counts`，
 *      页面切换分类时才再请求一次列表。**不许对三个 targetType 各打一次计数**（工作流原文）。
 *   2. **取消关注接口不在工作流 E3 的 4 个接口里**，但设计稿 52 有关注页取关按钮；
 *      这里按 `DELETE /app/social/follows?lawyerId=` 写，后端未交付时请求会失败，
 *      页面给错误提示（不假装成功）。这一条已同步给后端（T1）。
 *   3. 所有路径都带 `/api/v1` 前缀（`shared/api/client.ts` 的 BASE_URL 只到域名）。
 */

import { get, request } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

import type {
  FavoriteCounts,
  FavoriteItem,
  FavoritesData,
  FollowCountData,
  FollowsData,
  SocialTargetType,
} from '../types';

/** 收藏列表出参（一次请求带三个计数 + 当前分类列表）。 */
export type RawFavoritesData = Omit<FavoritesData, 'counts'> & {
  /** 后端把 Long 序列化成字符串（含计数），这里先按宽类型接，再归一。 */
  counts: Partial<Record<SocialTargetType, number | string>>;
};

/**
 * 把后端计数（可能是字符串）归一成数字。
 *
 * 为什么需要：后端全局把 Long 序列化为字符串（接口规范），`counts` 是 `Map<String, Long>`，
 * JSON 里是 `"3"` 而不是 `3`。页面要做「减 1」运算，必须先在 api 层归一，
 * 否则 `String`/`number` 混用会在真实数据上悄悄出错。
 *
 * @param raw 后端原始计数
 * @returns 三个分类的非负整数
 */
function normalizeCounts(raw: Partial<Record<SocialTargetType, number | string>>): FavoriteCounts {
  const toInt = (value: number | string | undefined): number => {
    const parsed = Number(value ?? 0);
    return Number.isFinite(parsed) && parsed > 0 ? Math.trunc(parsed) : 0;
  };
  return {
    article: toInt(raw.article),
    lawyer: toInt(raw.lawyer),
    case: toInt(raw.case),
  };
}

/**
 * 取收藏列表（当前分类）+ 三分类计数。
 *
 * @param targetType 当前分类
 * @param cursor     分页游标（首页不传）
 * @returns 成功时 `data` 为计数 + 列表（计数已归一为数字）
 */
export async function getFavorites(
  targetType: SocialTargetType,
  cursor?: string,
): Promise<ApiResult<FavoritesData>> {
  const params = new URLSearchParams({ targetType });
  if (cursor !== undefined && cursor !== '') {
    params.set('cursor', cursor);
  }
  const result = await get<RawFavoritesData>(`/api/v1/app/social/favorites?${params.toString()}`);
  if (!result.ok) {
    return { ok: false, error: result.error };
  }
  return {
    ok: true,
    data: { ...result.data, counts: normalizeCounts(result.data.counts) },
  };
}

/**
 * 取消收藏。
 *
 * 副作用：服务端删除（或置软删）`social_action` 对应行；失败时页面保留原列表项。
 *
 * @param targetType 目标类型
 * @param targetId   目标主键
 * @returns 成功时 `data` 为 null
 */
export function removeFavorite(
  targetType: SocialTargetType,
  targetId: string,
): Promise<ApiResult<null>> {
  const params = new URLSearchParams({ targetType, targetId });
  return request<null>(`/api/v1/app/social/favorites?${params.toString()}`, { method: 'DELETE' });
}

/**
 * 取关注律师列表。
 *
 * @param cursor 分页游标（首页不传）
 * @returns 成功时 `data` 为列表
 */
export function getFollows(cursor?: string): Promise<ApiResult<FollowsData>> {
  const params = new URLSearchParams();
  if (cursor !== undefined && cursor !== '') {
    params.set('cursor', cursor);
  }
  const query = params.toString();
  return get<FollowsData>(`/api/v1/app/social/follows${query === '' ? '' : `?${query}`}`);
}

/**
 * 取关注数（个人页角标用；本段收藏/关注页不展示它，接口先接好）。
 *
 * @returns 成功时 `data` 为 `{ count }`
 */
export function getFollowCount(): Promise<ApiResult<FollowCountData>> {
  return get<FollowCountData>('/api/v1/app/social/follow-count');
}

/**
 * 取消关注一个律师。
 *
 * 副作用：服务端删除关注关系。**该接口不在工作流 E3 的 4 条里**（见文件头注释第 2 条），
 * 后端未交付时会返回业务错误，页面据此提示而不是把卡片假删掉。
 *
 * @param lawyerId 律师主键
 * @returns 成功时 `data` 为 null
 */
export function unfollowLawyer(lawyerId: string): Promise<ApiResult<null>> {
  const params = new URLSearchParams({ lawyerId });
  return request<null>(`/api/v1/app/social/follows?${params.toString()}`, { method: 'DELETE' });
}

/** 收藏条目在页面里的稳定 key（跨分类可能撞 id，拼上类型）。 */
export function favoriteKey(item: FavoriteItem): string {
  return `${item.targetType}-${item.targetId}`;
}
