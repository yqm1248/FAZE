/*
 * 职责：内容审核处置结果枚举（4 态），数据库存 code 小写值（content_review.review_result）。
 * 调用方：modules/ops 的 ContentReviewService / 审核队列接口 / A-20 与 A-25 页面。
 * 数据来源：《第 7 段工作流》§三 A3 第 3 条（三档处置落库值）与 B3 第 1 条（处置三选）。
 * 注意：
 *   1. **pending 是「待处置」，不是「未命中」**：只有 review 级命中会进队列并在 pending；
 *      warn 级落库即 ignored（不进队列），block 级落库后由用户申诉或管理员处置。
 *   2. ignored / warned / banned 都是终态，**不可再改**（重复处置返回 410008）。
 *   3. 申诉通过（accepted）把对应记录改为 ignored——这是唯一一条「售后改判」路径，
 *      但词条本身不动（A-25 稿明写「词条本身不变」）。
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
 * 内容审核处置结果。除 pending 外均为终态。
 */
@Getter
public enum ReviewResult {

    /** 待处置：进 A-20 队列，等待管理员处理。 */
    PENDING("pending", "待处置"),

    /** 已忽略（误判）：不处置用户，记录留档。 */
    IGNORED("ignored", "已忽略"),

    /** 已警告：写 user_violation(level=warning)，发站内信。 */
    WARNED("warned", "已警告"),

    /** 已封禁：写 user_violation(level=ban) 并把 app_user.status 置 banned。 */
    BANNED("banned", "已封禁");

    /** 持久化值（content_review.review_result 里存它）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    ReviewResult(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, ReviewResult> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ReviewResult::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code content_review.review_result 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 401001（参数校验失败）
     */
    public static ReviewResult of(String code) {
        ReviewResult result = code == null ? null : BY_CODE.get(code);
        if (result == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "未知审核处置结果: " + code);
        }
        return result;
    }

    /**
     * 按持久化值反查，值非法返回 null（列表筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static ReviewResult ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否已处置（终态判定）。
     *
     * @return 非 pending 返回 true；重复处置应返回 410008
     */
    public boolean isHandled() {
        return this != PENDING;
    }

    /**
     * 该结果中文名的 i18n key。
     *
     * @return 形如 {@code ops.review.resultPending}
     */
    public String i18nKey() {
        return switch (this) {
            case PENDING -> "ops.review.resultPending";
            case IGNORED -> "ops.review.resultIgnored";
            case WARNED -> "ops.review.resultWarned";
            case BANNED -> "ops.review.resultBanned";
        };
    }
}
