/*
 * 职责：律师回复评价的入参（G5.1，`POST /app/reviews/{id}/reply`，段 2 新增接口）。
 * 调用方：modules/trade/controller/ReviewController#reply。
 * 数据来源：请求体。
 * 注意：
 *   1. `content` **必填**：空回复等于没回复，还会在律师主页上留下一条空白回应。
 *   2. 宽度与 `review.lawyer_reply VARCHAR(500)` 对齐（JSON 之外没有第二处约束）。
 *   3. 回复内容会被 C 端评价列表原样展示（`LawyerReviewVO.lawyerReply`），
 *      所以**不做富文本、不解析 HTML**：纯文本最安全，也符合段 2 不做内容审核编辑器的前提。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 律师回复评价入参。
 */
@Data
public class ReviewReplyBO {

    /** 回复正文（必填，≤500 字）。 */
    @Size(max = 500, message = "回复最长 500 字")
    private String content;
}
