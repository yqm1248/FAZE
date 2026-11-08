/*
 * 职责：`GET /api/v1/app/social/follows` 的出参（E3 关注律师页）。
 * 调用方：modules/trade/controller/AppSocialController → APP 52 关注页。
 * 数据来源：social_action（关注行）+ lawyer（姓名/律所/在线状态）+ app_user.last_login_at（最近活跃）
 *           + review（最近回复过咨询的近似依据）。
 * 注意：
 *   1. **三种状态由前端判定**（与 T2 前端的约定）：后端只给 `onlineStatus`、
 *      `lastActiveAt`、`feedType` 三个原始事实，不在服务端拼「在线+有新动态」这类文案——
 *      文案要跟设计稿走，四语言各一份，放服务端反而会漂移。
 *   2. `feedType` 只能是 `article` / `reply` / `none`：
 *      - `reply`：最近 30 天该律师回复过用户评价（review.lawyer_reply 非空）——有真实数据源；
 *      - `article`：需要「文章作者」字段，而 `article` 表没有 author 列（段 1 未建），
 *        **当前不会产出**（这是数据缺口，不是分支没写），已在 §十一 挂账；
 *      - `none`：既非回复也非文章。
 *   3. `lastActiveAt` 取 `app_user.last_login_at`（该律师关联账号最后登录时间），不是
 *      `lawyer.updated_at`（后者是资料更新时间，运营改一次价格就会刷新，不能代表活跃）。
 *   4. 时间字段是 `yyyy-MM-dd HH:mm:ss`（+08:00）字符串。
 */
package com.faze.modules.trade.vo;

import java.util.List;
import lombok.Data;

/**
 * 关注律师列表。
 */
@Data
public class SocialFollowsVO {

    /** 关注行。 */
    private List<Item> list;

    /** 下一页游标（social_action.id）。 */
    private String nextCursor;

    /** 是否还有更多。 */
    private boolean hasMore;

    /**
     * 关注行。
     */
    @Data
    public static class Item {

        /** 律师主键。 */
        private Long lawyerId;

        /** 执业姓名。 */
        private String name;

        /** 执业律所。 */
        private String firm;

        /** 执业年限。 */
        private Integer years;

        /** 擅长领域（已解析成数组）。 */
        private List<String> specialties;

        /** 在线状态：online / busy / offline。 */
        private String onlineStatus;

        /** 最近活跃时间（+08:00）；从未登录为 null。 */
        private String lastActiveAt;

        /** 动态类型：article / reply / none。 */
        private String feedType;

        /** 动态标题（回复类为「回复了用户评价」，可空）。 */
        private String feedTitle;

        /** 动态时间（+08:00），可空。 */
        private String feedAt;
    }
}
