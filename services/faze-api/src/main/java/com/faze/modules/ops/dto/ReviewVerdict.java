/*
 * 职责：内容审核的返回值——告诉调用方「能不能投递」以及「到底写没写记录」。
 * 调用方：投诉/反馈提交链路：blockDelivery=true 时返回 410006 且**不落业务表**。
 * 数据来源：modules/ops 的 ContentReviewService.review(...)。
 * 注意：
 *   1. **`blockDelivery=false` 不代表没命中**：review / warn 级命中也会写记录，
 *      只是不拦。调用方不要用 !blockDelivery 去推断「内容干净」。
 *   2. `reviewId` 为 null 表示「没有写 content_review」（未命中，或开关关闭）。
 *      非 null 时调用方可以把它带给前端，供「误判申诉」使用（B2 的 contentReviewId）。
 *   3. 记录是不可变值对象：调用方只读，不要 set。
 */
package com.faze.modules.ops.dto;

/**
 * 内容审核结论。
 *
 * @param blockDelivery 是否拦截投递（命中 block 级且拦截开关打开）
 * @param reviewId      写入的 content_review.id；未写记录时为 null
 */
public record ReviewVerdict(boolean blockDelivery, Long reviewId) {

    /**
     * 放行且未写记录（未命中 / 开关关闭）。
     *
     * @return 结论对象
     */
    public static ReviewVerdict pass() {
        return new ReviewVerdict(false, null);
    }

    /**
     * 拦截。
     *
     * @param reviewId 写入的命中记录 id（可为 null，若拦截但记录未写）
     * @return 结论对象
     */
    public static ReviewVerdict blocked(Long reviewId) {
        return new ReviewVerdict(true, reviewId);
    }
}
