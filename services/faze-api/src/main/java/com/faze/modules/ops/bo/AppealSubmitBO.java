/*
 * 职责：用户端提交敏感词误判申诉的入参。
 * 调用方：modules/ops 的 ContentAppealController（`POST /api/v1/app/content-appeals`）。
 * 数据来源：APP 提交（内容被拦截时的「点此申诉」入口）。
 * 注意：
 *   1. `contentReviewId` 由**拦截时的响应**带给前端（ReviewVerdict.reviewId），
 *      前端不应自己猜 id；服务端会校验它归属当前用户（IDOR 防护）。
 *   2. 只允许对 `level='block'` 的命中申诉——review/warn 用户根本看不见，
 *      放开会产生「用户猜 id 试探内容是否命中」的探测面。
 *   3. `reason` 必填且 ≤500：申诉理由是复核员唯一的判断依据，不能留空。
 */
package com.faze.modules.ops.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 申诉提交入参。
 */
@Data
public class AppealSubmitBO {

    /** 被误判的命中记录 id。 */
    @NotNull(message = "命中记录 id 不能为空")
    private Long contentReviewId;

    /** 申诉理由。 */
    @NotBlank(message = "申诉理由不能为空")
    @Size(max = 500, message = "申诉理由不能超过 500 字")
    private String reason;
}
