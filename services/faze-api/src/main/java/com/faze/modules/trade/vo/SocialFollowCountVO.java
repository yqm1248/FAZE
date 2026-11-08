/*
 * 职责：`GET /api/v1/app/social/follow-count` 的出参（个人页「关注」角标）。
 * 调用方：modules/trade/controller/AppSocialController → APP 我的页角标。
 * 数据来源：social_action（action_type='follow' 且未软删）。
 * 注意：
 *   `count` 用原生 `long` 而不是 `Long`：它不是 ID，JacksonConfig 只把包装类 Long
 *   序列化成字符串（见 JacksonConfig 注释），这里必须是 JSON 数字，
 *   与分页 total 的口径一致。
 */
package com.faze.modules.trade.vo;

import lombok.Data;

/**
 * 关注数。
 */
@Data
public class SocialFollowCountVO {

    /** 关注数。 */
    private long count;
}
