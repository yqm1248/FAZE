/*
 * 职责：敏感词级别枚举（3 档），数据库存 code 小写值（sensitive_word.level / content_review.level）。
 * 调用方：infra/sensitive 的 Trie 引擎、modules/ops 的内容审核与词库管理、A-20/A-25 页面。
 * 数据来源：《第 7 段工作流》§三 A6 第 2 条（SensitiveLevel）与 A3 第 3 条（三档处置）。
 * 注意：
 *   1. 三档语义**互不包含**：block 拦发送、review 正常投递但进队列、warn 只记录不进队列。
 *      判定结果取「本次命中所有词里的最高级」（maxLevel），顺序是 BLOCK > REVIEW > WARN。
 *   2. 实体字段存 String，枚举只做业务判定（项目既定风格，不实现 MP 的 IEnum）。
 *   3. 本段只做本地词库判定，**不做任何原文改写/替换**（§零.4 第 1 条红线）。
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
 * 敏感词级别。三档处置的唯一依据，不做组合与降级。
 */
@Getter
public enum SensitiveLevel {

    /** 拦截：内容不投递/不提交，但原文仍落业务表；用户可申诉（410006）。 */
    BLOCK("block", "拦截", 3),

    /** 送审：正常投递/展示，同时进 A-20 审核队列。 */
    REVIEW("review", "送审", 2),

    /** 仅记录：写 content_review（review_result=ignored），不进队列，只做统计。 */
    WARN("warn", "仅记录", 1);

    /** 持久化值（sensitive_word.level 里存它）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    /** 严重度权重：用于从多个命中的词里取「最高级」，数值越大越严重。 */
    private final int severity;

    SensitiveLevel(String code, String label, int severity) {
        this.code = code;
        this.label = label;
        this.severity = severity;
    }

    private static final Map<String, SensitiveLevel> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(SensitiveLevel::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code sensitive_word.level 的值
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 401001（参数校验失败）
     */
    public static SensitiveLevel of(String code) {
        SensitiveLevel level = code == null ? null : BY_CODE.get(code);
        if (level == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "未知敏感词级别: " + code);
        }
        return level;
    }

    /**
     * 按持久化值反查，值非法返回 null（列表筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static SensitiveLevel ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否为「需要拦截投递」的级别。
     *
     * @return block 返回 true
     */
    public boolean isBlocking() {
        return this == BLOCK;
    }

    /**
     * 该级别中文名的 i18n key。
     *
     * @return 形如 {@code ops.sensitive.levelBlock}
     */
    public String i18nKey() {
        return switch (this) {
            case BLOCK -> "ops.sensitive.levelBlock";
            case REVIEW -> "ops.sensitive.levelReview";
            case WARN -> "ops.sensitive.levelWarn";
        };
    }
}
