/*
 * 职责：案例细分分类枚举（6 值），数据库存 code 小写值。
 * 调用方：content 的 CaseLibraryService、PC A-16 案例库筛选。
 * 数据来源：技术路线 §5.8 的 case_library.category 列注释（marriage/labor/contract/traffic/criminal/policy）。
 * 注意：
 *   1. **大类由细分映射得到**（见 {@link #bigType()}），case_library 表里 case_type 与 category
 *      不许两处都填——只填 category，case_type 由本枚举推导。
 *   2. 注意 `labor`（劳动争议）在案例库是**民事下的细分**，与案件类型的 `labor`（劳动仲裁）不是同一语义；
 *      两套枚举各自独立，不要互相 import 判断。
 */
package com.faze.modules.content.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 案例细分分类。大类由 {@link #bigType()} 映射。
 */
@Getter
public enum CaseCategory {

    /** 婚姻家事 → 民事。 */
    MARRIAGE("marriage", "婚姻家事", CaseLibraryType.CIVIL),

    /** 劳动争议 → 民事。 */
    LABOR("labor", "劳动争议", CaseLibraryType.CIVIL),

    /** 合同纠纷 → 民事。 */
    CONTRACT("contract", "合同纠纷", CaseLibraryType.CIVIL),

    /** 交通事故 → 民事。 */
    TRAFFIC("traffic", "交通事故", CaseLibraryType.CIVIL),

    /** 刑事 → 刑事。 */
    CRIMINAL("criminal", "刑事", CaseLibraryType.CRIMINAL),

    /** 行政诉讼 → 行政。 */
    POLICY("policy", "行政诉讼", CaseLibraryType.ADMINISTRATIVE);

    /** 持久化值（case_library.category 里存它）。 */
    private final String code;

    /** 给开发者/后台看的中文名；前端展示走 i18n。 */
    private final String label;

    /** 对应大类（写 case_type 时由它推导，不许人工再填）。 */
    private final CaseLibraryType bigType;

    CaseCategory(String code, String label, CaseLibraryType bigType) {
        this.code = code;
        this.label = label;
        this.bigType = bigType;
    }

    private static final Map<String, CaseCategory> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CaseCategory::getCode, Function.identity()));

    /**
     * 按持久化值反查，值非法返回 null（筛选参数容错）。
     *
     * @param code case_library.category 的值
     * @return 对应枚举；未知值返回 null
     */
    public static CaseCategory ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 该细分类对应的 i18n key。
     *
     * @return 形如 {@code content.case.category.marriage}
     */
    public String i18nKey() {
        return "content.case.category." + code;
    }
}
