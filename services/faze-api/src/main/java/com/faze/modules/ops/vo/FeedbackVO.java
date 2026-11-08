/*
 * 职责：我的反馈列表的行出参。
 * 调用方：modules/ops 的 FeedbackController（`GET /api/v1/app/feedback`）。
 * 数据来源：faze.feedback + 按工单回查的最近一条回复。
 * 注意：
 *   1. `contact` **原样返回**：这是用户自己填的联系方式，本人可见明文；
 *      列表只给本人看（`user_id` 归属校验），不存在泄露给第三人。
 *   2. `replyContent` 同投诉：来自 notification。
 */
package com.faze.modules.ops.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 我的反馈行。
 */
@Data
public class FeedbackVO {

    /** 反馈 id。 */
    private Long id;

    /** 反馈类型。 */
    private String type;

    /** 具体情况。 */
    private String content;

    /** 截图 oss_key 数组。 */
    private String images;

    /** 联系方式（本人可见）。 */
    private String contact;

    /**
     * 反馈状态。
     *
     * <p><b>口径（2026-11-09 修正）</b>：有工单时返回**工单状态**
     * （`waiting` / `processing` / `replied` / `closed`），无工单时退回 `feedback.status`。
     * 原因同 ComplaintVO：反馈落库即生成工单，用户看到的处理进度应以工单流转为准。</p>
     */
    private String status;

    /**
     * 工单状态（`waiting` / `processing` / `replied` / `closed`）；无工单为 null。
     */
    private String ticketStatus;

    /** 关联工单号。 */
    private String ticketNo;

    /** 最新回复内容。 */
    private String replyContent;

    /** 最新回复时间（UTC）。 */
    private LocalDateTime replyAt;

    /** 提交时间（UTC）。 */
    private LocalDateTime createdAt;
}
