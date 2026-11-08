/*
 * 职责：`GET /api/v1/app/social/favorites` 的出参（E3 我的收藏页）。
 * 调用方：modules/trade/controller/AppSocialController → APP 51 收藏页。
 * 数据来源：social_action（收藏行）+ article / case_library / lawyer（跨域只取简要信息）。
 * 注意：
 *   1. **一次返回三个 targetType 的 counts**（稿面 Tab「文章 3 / 律师 2 / 案例 2」）：
 *      前端不许为三个数字打三次接口，否则切 Tab 会有三次抖动。
 *   2. `list` 只是**当前 targetType** 的分页数据：三个类型混在一个列表里会让游标语义
 *      变复杂（每种类型的分页进度不同），稿面本身也是 Tab 切换。
 *   3. 目标已删除 / 已下架的行**不出现在 list 里**，但 counts 仍按 social_action 计数：
 *      这是有意的——计数回答「我收藏过几条」，列表回答「现在还能看的有几条」。
 *      如果两者必须一致，运营下架内容会让用户的收藏数莫名减少，更像 bug。
 *   4. `targetId` 是 Long（JacksonConfig 会序列化成字符串），与项目「ID 一律字符串」口径一致。
 *   5. 时间字段是 `yyyy-MM-dd HH:mm:ss`（+08:00）字符串：收藏页直接展示，前端不换算。
 */
package com.faze.modules.trade.vo;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 收藏列表（含三类型计数）。
 */
@Data
public class SocialFavoritesVO {

    /**
     * 三个 targetType 的收藏数：key 为 article / lawyer / case。
     *
     * <p><b>value 必须是 Integer 而不是 Long</b>：JacksonConfig 把包装类 Long 序列化成字符串
     * （ID 防精度丢失），用它当计数会让前端拿到 `{"article":"3"}`——Tab 上做数字运算/比较
     * 会出类型错误。计数不是 ID，用 Integer（JSON 数字）。2026-11-09 实测踩过。</p>
     */
    private Map<String, Integer> counts;

    /** 当前 targetType 的收藏行。 */
    private List<Item> list;

    /** 下一页游标（social_action.id）；没有下一页为 null。 */
    private String nextCursor;

    /** 是否还有更多。 */
    private boolean hasMore;

    /**
     * 收藏行。
     */
    @Data
    public static class Item {

        /** 对外目标类型：article / lawyer / case。 */
        private String targetType;

        /** 目标主键。 */
        private Long targetId;

        /** 标题（文章标题 / 律师姓名 / 案例标题）。 */
        private String title;

        /** 描述（文章摘要 / 律所·执业年限 / 案情简介）。 */
        private String desc;

        /** 附加信息（文章分类与发布时间 / 律师评分 / 案例类型）。 */
        private String meta;

        /** 是否在线；只有 lawyer 目标有值，其它为 null。 */
        private Boolean online;

        /** 收藏时间（+08:00）。 */
        private String favoritedAt;
    }
}
