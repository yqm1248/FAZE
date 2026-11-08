/*
 * 职责：律师评价列表行（`GET /app/lawyers/{id}/reviews`，G3.2）。
 * 调用方：modules/account/controller/LawyerBrowseController → APP「律师详情」页评价区。
 * 数据来源：faze.review + faze.app_user（昵称，仅非匿名时取）。
 * 注意：
 *   1. **匿名评价绝不带 user_id / 昵称 / 头像**：给了其中任何一个都能反查到人，等于没匿名。
 *      匿名的 `userNickname` 用前端 i18n 的「匿名用户」文案，服务端传 `null`。
 *   2. 昵称脱敏口径：只回传昵称，不回传手机号；昵称本身是用户自己起的，不算敏感。
 *   3. `tags` 是数组（服务端解析 JSON），不是字符串。
 *   4. 不返回 `orderId`：评价与订单的关联只在后台可见，C 端给出去会泄露别人的消费记录。
 */
package com.faze.modules.account.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 律师评价行。
 */
@Data
public class LawyerReviewVO {

    /** 评价主键。 */
    private Long id;

    /** 评分 1~5。 */
    private Integer rating;

    /** 标签数组。 */
    private List<String> tags;

    /** 评价正文。 */
    private String content;

    /** 律师回复，可空。 */
    private String lawyerReply;

    /** 是否匿名。 */
    private Boolean anonymous;

    /** 评价人昵称；匿名为 null。 */
    private String userNickname;

    /** 评价时间（UTC）。 */
    private LocalDateTime createdAt;
}
