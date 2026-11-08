/*
 * 职责：review 表的实体映射（订单评价，G5 写入 / G3.2 律师详情展示）。
 * 调用方：modules/trade/mapper/ReviewMapper、modules/account/service/LawyerBrowseService（只读展示）。
 * 数据来源：V200__seg2_tables.sql 建的 review（10 字段）。
 * 注意：
 *   1. **一单只能评一次**（`uk_review_order`）：重复提交靠唯一键拦，不靠应用层先查后插。
 *   2. 段 2 不提供修改 / 删除 / 申诉（工作流 §七），所以业务代码里没有 update / delete 入口。
 *   3. `tags` 是 JSON 数组字符串，实体里按字符串承载；解析集中在 Service/Converter，
 *      不在实体里做（实体只映射表）。
 *   4. `isAnonymous` 是 tinyint(1)，用 Boolean 承载。**匿名时出参不得带 user_id**
 *      （能反查昵称就等于没匿名）。
 *   5. 评分写回 `lawyer.rating` / `review_count` 由 G5.2 负责；本类只读。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 订单评价。
 */
@Data
@TableName("review")
public class Review {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单主键（唯一：一单一次）。 */
    private Long orderId;

    /** 评价人 ID。 */
    private Long userId;

    /** 被评价律师 ID。 */
    private Long lawyerId;

    /** 评分 1-5。 */
    private Integer rating;

    /** 标签数组（JSON 字符串，如 `["专业","响应快"]`）。 */
    private String tags;

    /** 评价正文。 */
    private String content;

    /** 律师回复（段 2 新增接口 `POST /app/reviews/{id}/reply`）。 */
    private String lawyerReply;

    /** 是否匿名。 */
    private Boolean isAnonymous;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
