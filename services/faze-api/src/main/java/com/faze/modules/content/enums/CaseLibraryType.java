/*
 * 职责：案例库大类枚举（3 类），数据库存 code 小写值。
 * 调用方：content 的 CaseLibraryService、App 案例列表（04-case 按它分三组）、PC A-16 案例库。
 * 数据来源：《第 5 段工作流》§零之二 第 13 项与 §三 A4 第 2 条。
 * 注意：
 *   1. **案例 3 类与案件 5 类是两套独立枚举**（§零之二 第 13 项）：
 *      案例库不收录劳动仲裁与家事，所以这里没有 labor / family。
 *   2. 大类由细分 `category` 映射得到，**不许两处都填**（case_library 表注释已写死）。
 */
package com.faze.modules.content.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 案例库大类。App 列表按它分三组。
 */
@Getter
public enum CaseLibraryType {

    /** 民事。 */
    CIVIL("civil", "民事"),

    /** 行政。 */
    ADMINISTRATIVE("administrative", "行政"),

    /** 刑事。 */
    CRIMINAL("criminal", "刑事");

    /** 持久化值（case_library.case_type 里存它）。 */
    private final String code;

    /** 给开发者/后台看的中文名；前端展示走 i18n。 */
    private final String label;

    CaseLibraryType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, CaseLibraryType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CaseLibraryType::getCode, Function.identity()));

    /**
     * 按持久化值反查，值非法返回 null（筛选参数容错）。
     *
     * @param code case_library.case_type 的值
     * @return 对应枚举；未知值返回 null
     */
    public static CaseLibraryType ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 该大类对应的 i18n key。
     *
     * @return 形如 {@code content.case.type.civil}
     */
    public String i18nKey() {
        return "content.case.type." + code;
    }
}
