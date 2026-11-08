/*
 * 职责：评价出参（G5.1：提交后的回执 / 订单详情判断是否已评价 / 律师侧看自己的回复）。
 * 调用方：modules/trade/service/ReviewService → modules/trade/controller/{OrderController,ReviewController}。
 * 数据来源：faze.review 单行。
 * 注意：
 *   1. `tags` 出参是**数组**：库里是 JSON 字符串，让客户端解析 JSON 是设计错误
 *      （解析失败整页崩，服务端可以降级成空数组）。解析口径与 LawyerBrowseConverter 一致。
 *   2. **不回 `userId`**：这是用户自己的评价回执，页面不需要用户 id；而匿名评价一旦带上
 *      可反查的标识就等于没匿名（见 Review 类注释第 4 条）。律师侧展示走 LawyerReviewVO，
 *      那边的匿名处理已单独实现。
 *   3. `anonymous` 与 `isAnonymous` 同名不同名：出参用 `anonymous`，与 LawyerReviewVO 保持一致，
 *      前端不用为两处写两种判断。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 评价出参。
 */
@Data
public class ReviewVO {

    /** 评价主键。 */
    private Long id;

    /** 订单主键。 */
    private Long orderId;

    /** 被评价律师 ID。 */
    private Long lawyerId;

    /** 评分 1-5。 */
    private Integer rating;

    /** 标签数组（空数组表示未选）。 */
    private List<String> tags;

    /** 评价正文；未填为 null。 */
    private String content;

    /** 律师回复；未回复为 null。 */
    private String lawyerReply;

    /** 是否匿名。 */
    private Boolean anonymous;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
