/*
 * 职责：案件类型枚举（5 类），数据库存 code 小写值。
 * 调用方：caseflow 的立案表单、列表筛选、PC 案件管理页。
 * 数据来源：《第 5 段工作流》§零之二 第 13 项与 §三 A4 第 1 条。
 * 注意：
 *   1. **案件类型 5 类与案例分类 3 类是两套独立枚举**，不许强行统一（§零之二 第 13 项）：
 *      案件有 lab（劳动仲裁）与 family（家事），案例库不收这两类。
 *   2. 设计稿立案页第 1 步写「民事 / 行政 / 刑事」三选一 + 7 项细分纠纷类型，
 *      但工作流裁决为 **5 类**（补劳动仲裁与家事）——以工作流为准，设计稿的三选一由前端映射。
 */
package com.faze.modules.caseflow.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 案件类型。5 类，与案件状态机、案例库分类都不是一回事。
 */
@Getter
public enum CaseType {

    /** 民事。 */
    CIVIL("civil", "民事"),

    /** 刑事。 */
    CRIMINAL("criminal", "刑事"),

    /** 行政。 */
    ADMINISTRATIVE("administrative", "行政"),

    /** 劳动仲裁。案例库不收录。 */
    LABOR("labor", "劳动仲裁"),

    /** 家事。案例库不收录。 */
    FAMILY("family", "家事");

    /** 持久化值（legal_case.case_type 里存它）。 */
    private final String code;

    /** 给开发者/后台看的中文名；前端展示走 i18n。 */
    private final String label;

    CaseType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, CaseType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CaseType::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code legal_case.case_type 的值
     * @return 对应枚举；未知值返回 null（筛选参数场景不抛异常）
     */
    public static CaseType ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 该类型对应的 i18n key。
     *
     * @return 形如 {@code case.type.labor}
     */
    public String i18nKey() {
        return "case.type." + code;
    }
}
