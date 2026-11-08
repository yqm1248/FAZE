/**
 * social 域（我的收藏 / 关注律师）的类型定义。
 *
 * 职责：把段 7 E3 的 4 个接口（+ 取消关注）出参收敛成一处类型，页面与 api 层共用。
 * 调用方：`features/social/api/social.ts`、`FavoritesPage.tsx`、`FollowingPage.tsx`。
 * 数据来源：`GET/DELETE /app/social/favorites`、`GET /app/social/follows`、
 *           `GET /app/social/follow-count`、`DELETE /app/social/follows`（取关，见注意事项 3）。
 * 注意：
 *   1. **Long 型主键一律是 `string`**（接口规范：后端把 Long 序列化为字符串，
 *      JS 的 2^53 精度装不下，写成 `number` 会在真实数据上静默出错）。
 *   2. 收藏列表是**跨域组装**的（social_action → lawer/article/case 三个域批量取），
 *      所以出参是统一的 VO 形状（title/desc/meta），不是三个域各自的 DTO。
 *      `online` 只有律师类型有意义，其余类型为 null。
 *   3. 工作流 E3 只列了 4 个接口，**没有「取消关注」**，但设计稿 52 有关注页取关按钮。
 *      `unfollowLawyer()` 按 `DELETE /app/social/follows?lawyerId=` 写；该接口未交付时
 *      页面走错误提示（不假装成功）。
 *   4. `counts` 允许缺字段：后端可能只返回有数据的类型；页面一律用 `?? 0` 读。
 */

/** 收藏目标类型（与后端 `social_action.target_type` 对应）。 */
export type SocialTargetType = 'article' | 'lawyer' | 'case';

/** 三个分类的数量（收藏页 Tab 上的数字）。 */
export type FavoriteCounts = Partial<Record<SocialTargetType, number>>;

/** 一条收藏（跨域组装后的统一 VO）。 */
export type FavoriteItem = {
  /** 目标类型。 */
  targetType: string;
  /** 目标主键（字符串形式的 Long）。 */
  targetId: string;
  /** 标题（文章标题 / 律师姓名 / 案例名称）。 */
  title: string;
  /** 摘要（律师为「律所 · 执业 N 年」）；后端可能给 null。 */
  desc: string | null;
  /** 元信息（文章/案例为「文章」「案例」，律师为「律师 · 评分 x」）；后端可能给 null。 */
  meta: string | null;
  /** 律师是否在线；非律师类型为 null。 */
  online: boolean | null;
  /** 收藏时间（UTC，无时区后缀）。 */
  favoritedAt: string | null;
};

/** 收藏列表出参（一次请求带三个计数 + 当前分类列表）。 */
export type FavoritesData = {
  /** 三个分类的数量（**一次请求返回**，避免打三次）。 */
  counts: FavoriteCounts;
  /** 当前分类的列表。 */
  list: FavoriteItem[];
  /** 下一页游标；没有更多时为 null。 */
  nextCursor: string | null;
  /** 是否还有更多。 */
  hasMore: boolean;
};

/** 关注页的律师动态类型。 */
export type FollowFeedType = 'article' | 'reply' | 'none';

/** 一条关注律师。 */
export type FollowItem = {
  /** 律师主键（字符串形式的 Long）。 */
  lawyerId: string;
  /** 姓名。 */
  name: string;
  /** 执业律所；可空。 */
  firm: string | null;
  /** 执业年限；后端可能给 null。 */
  years: number | null;
  /** 擅长领域。 */
  specialties: string[];
  /** 在线状态：online / busy / offline。 */
  onlineStatus: string;
  /** 最近活跃时间（UTC，无时区后缀）；长期未活跃判定用。 */
  lastActiveAt: string | null;
  /** 最新动态类型。 */
  feedType: string;
  /** 最新动态文案（如「发布了文章《…》」「回复了 1 条婚姻家事咨询」）。 */
  feedTitle: string | null;
  /** 最新动态时间（UTC）。 */
  feedAt: string | null;
};

/** 关注列表出参。 */
export type FollowsData = {
  /** 当前页列表。 */
  list: FollowItem[];
  /** 下一页游标。 */
  nextCursor: string | null;
  /** 是否还有更多。 */
  hasMore: boolean;
};

/** 关注数出参（个人页角标）。 */
export type FollowCountData = {
  /** 关注数。 */
  count: number;
};

/**
 * 关注状态的三种展示形态（工作流 E3 第 4 条）。
 *
 * - `active`：在线 + 有新动态；
 * - `replied`：离线 + 回复过咨询；
 * - `inactive`：长期未活跃（**必须明确写出来**，让用户自己决定要不要继续关注）。
 */
export type FollowDisplayState = 'active' | 'replied' | 'inactive';

/**
 * 判定一条关注该显示哪种状态。
 *
 * 优先级：长期未活跃 > 在线（有新动态）> 回复过咨询。
 * 为什么长期未活跃排最前：一个两个月没上线的律师即使「在线」标记是脏数据，
 * 也应该先告诉用户「他很久没来了」，而不是让他以为人就在线。
 *
 * @param item         关注项
 * @param inactiveDays 判定「长期未活跃」的天数阈值（默认 30 天，与设计稿文案一致）
 * @param now          当前时间戳（毫秒；测试可注入）
 * @returns 展示状态
 */
export function followDisplayState(
  item: FollowItem,
  inactiveDays = 30,
  now = Date.now(),
): FollowDisplayState {
  const lastActive = parseTime(item.lastActiveAt);
  if (lastActive !== null) {
    const days = (now - lastActive) / (1000 * 60 * 60 * 24);
    if (days >= inactiveDays) {
      return 'inactive';
    }
  }
  if (item.onlineStatus === 'online') {
    return 'active';
  }
  if (item.feedType === 'reply') {
    return 'replied';
  }
  // 离线且没有「回复咨询」动态：按「有新动态」之外的普通关注展示，
  // 不额外造第四种状态（工作流只要三种）。
  return item.feedType === 'article' ? 'active' : 'replied';
}

/**
 * 解析服务端时间串成时间戳（UTC 无时区时补 `Z`）。
 *
 * @param input 时间串
 * @returns 时间戳；解析失败返回 null
 */
function parseTime(input: string | null): number | null {
  if (input === null || input.trim() === '') {
    return null;
  }
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(input);
  const time = Date.parse(hasZone ? input : `${input}Z`);
  return Number.isNaN(time) ? null : time;
}
