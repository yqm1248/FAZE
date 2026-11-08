/*
 * 职责：敏感词分类枚举（5 类），数据库存大写 code（sensitive_word.category）。
 * 调用方：infra/sensitive 的 Trie 引擎、A-25 词库页的筛选项与表单。
 * 数据来源：《第 7 段工作流》§三 A6 第 2 条（SensitiveCategory）与 A1 第 4 步的五类种子。
 * 注意：
 *   1. **分类只用于统计与筛选，不参与拦截判定**（拦截只看 level）。新增分类只需加一个枚举值
 *      与 DDL 注释，不改引擎。
 *   2. 种子数据里政治/暴恐/色情三类是占位词（正式词库由运营提供，裁决 17 挂账），
 *      不得据此认为「五类词库已可用」。
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
 * 敏感词分类。只做统计与筛选，不参与拦截判定。
 */
@Getter
public enum SensitiveCategory {

    /** 政治类。 */
    POLITICS("POLITICS", "政治"),

    /** 暴恐类。 */
    VIOLENCE("VIOLENCE", "暴恐"),

    /** 色情低俗类。 */
    PORN("PORN", "色情低俗"),

    /** 欺诈类（含法律行业承诺性禁语：包打赢、百分百胜诉等）。 */
    FRAUD("FRAUD", "欺诈"),

    /** 违法类（代开发票、办假证、虚假诉讼等）。 */
    ILLEGAL("ILLEGAL", "违法");

    /** 持久化值（sensitive_word.category 里存它，大写）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    SensitiveCategory(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, SensitiveCategory> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(SensitiveCategory::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code sensitive_word.category 的值（大写）
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 401001（参数校验失败）
     */
    public static SensitiveCategory of(String code) {
        SensitiveCategory category = code == null ? null : BY_CODE.get(code);
        if (category == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "未知敏感词分类: " + code);
        }
        return category;
    }

    /**
     * 按持久化值反查，值非法返回 null（列表筛选参数容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static SensitiveCategory ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 该分类中文名的 i18n key。
     *
     * @return 形如 {@code ops.sensitive.categoryPolitics}
     */
    public String i18nKey() {
        return switch (this) {
            case POLITICS -> "ops.sensitive.categoryPolitics";
            case VIOLENCE -> "ops.sensitive.categoryViolence";
            case PORN -> "ops.sensitive.categoryPorn";
            case FRAUD -> "ops.sensitive.categoryFraud";
            case ILLEGAL -> "ops.sensitive.categoryIllegal";
        };
    }
}
