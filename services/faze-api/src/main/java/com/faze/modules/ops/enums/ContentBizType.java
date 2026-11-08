/*
 * 职责：内容审核的业务类型枚举（6 个），用于 content_review.biz_type 与「从哪里来的内容」判定。
 * 调用方：modules/ops 的 ContentReviewService、AdminContentReviewService（A-20 类型筛选）。
 * 数据来源：《第 7 段工作流》§三 A3 第 2 条（biz_type 六个取值）。
 * 注意：
 *   1. **本段只接入 complaint / feedback 两条链路**（A3 第 7 条）：其余四种类型（im_message /
 *      review / article_comment / ai_message）的表与代码在段 2/3/内容域，本段只提供
 *      `ContentReviewService.review()` 供它们调用，并在 §九 登记「各段需在落库前调用」。
 *   2. 新增类型要同步改 DDL 注释与 A-20 的筛选项，**不是只加一个枚举值就完事**。
 */
package com.faze.modules.ops.enums;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * 内容审核业务类型。
 */
@Getter
public enum ContentBizType {

    /** 私信消息（段 2 已交付，本段未接入）。 */
    IM_MESSAGE("im_message", "私信消息"),

    /** 律师评价（段 2 已交付，本段未接入）。 */
    REVIEW("review", "评价"),

    /** 文章评论（内容域，本段未接入）。 */
    ARTICLE_COMMENT("article_comment", "文章评论"),

    /** AI 回答（段 3，本段未接入）。 */
    AI_MESSAGE("ai_message", "AI 回答"),

    /** 投诉正文（**本段接入**）。 */
    COMPLAINT("complaint", "投诉"),

    /** 意见反馈（**本段接入**）。 */
    FEEDBACK("feedback", "反馈");

    /** 持久化值（content_review.biz_type 里存它）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    ContentBizType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, ContentBizType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ContentBizType::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code content_review.biz_type 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 401001（参数校验失败）
     */
    public static ContentBizType of(String code) {
        ContentBizType type = code == null ? null : BY_CODE.get(code);
        if (type == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "未知内容业务类型: " + code);
        }
        return type;
    }

    /**
     * 按持久化值反查，值非法返回 null（列表筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static ContentBizType ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 该类型中文名的 i18n key。
     *
     * @return 形如 {@code ops.review.bizTypeComplaint}
     */
    public String i18nKey() {
        return switch (this) {
            case IM_MESSAGE -> "ops.review.bizTypeImMessage";
            case REVIEW -> "ops.review.bizTypeReview";
            case ARTICLE_COMMENT -> "ops.review.bizTypeArticleComment";
            case AI_MESSAGE -> "ops.review.bizTypeAiMessage";
            case COMPLAINT -> "ops.review.bizTypeComplaint";
            case FEEDBACK -> "ops.review.bizTypeFeedback";
        };
    }
}
