/*
 * 职责：条文号存在性校验的结果（B3），D3 输出校验据此决定「保留引用 / 删不存在条号 / 无需处理」。
 * 调用方：com.faze.modules.ai.service.ArticleNoValidator#validate、
 *         modules/ai/service/OutputGuard（D3）、modules/ai/service/RefSnapshotBuilder（D4）。
 * 数据来源：law_item（经 ArticleNoValidator 回查）。
 * 注意：
 *   1. 两个列表**永不返回 null**（空时返回 `List.of()`）：D3/D4 会直接遍历，null 会炸链路。
 *   2. `missingArticleNos` 存**回答原文**里的匹配文本（含「第…条」，如「第9999条」），
 *      D3 要按它做精确删除；`validArticleNos` 存**库里的规范条号**（如「第六百八十条」），
 *      两者口径不同，不要混用。
 *   3. 状态三值只有 ALL_EXIST / PARTIAL_MISS / NO_REF（工作流 B3 第 3 条）。
 *      「全部条号都不存在」也归 PARTIAL_MISS——语义是「有需要人工核实的条号」，
 *      不新增第四种状态，避免 D3 多一个分支。
 *   4. 本类是不可变结果对象：构造后列表不可修改，避免调用方顺手改。
 */
package com.faze.modules.ai.dto;

import java.util.List;

/**
 * 条文号校验结果。
 */
public class ValidationResult {

    /** 校验结论。 */
    public enum Status {
        /** 全部条号都在库里存在。 */
        ALL_EXIST,
        /** 部分（或全部）条号不存在。 */
        PARTIAL_MISS,
        /** 回答里没有条号（不是所有问题都该有法条）。 */
        NO_REF
    }

    /** 结论。 */
    private final Status status;

    /** 不存在的条号（回答原文形式）；空列表表示没有缺失。 */
    private final List<String> missingArticleNos;

    /** 存在的条号（库里规范形式）；空列表表示没有有效引用。 */
    private final List<String> validArticleNos;

    /**
     * 私有构造：只允许通过静态工厂创建，保证列表不可变、状态与列表一致。
     *
     * @param status            结论
     * @param validArticleNos   有效条号
     * @param missingArticleNos 缺失条号
     */
    private ValidationResult(Status status, List<String> validArticleNos, List<String> missingArticleNos) {
        this.status = status;
        this.validArticleNos = List.copyOf(validArticleNos);
        this.missingArticleNos = List.copyOf(missingArticleNos);
    }

    /**
     * 构造「无条号」结果。
     *
     * @return NO_REF 结果，两个列表均为空
     */
    public static ValidationResult noRef() {
        return new ValidationResult(Status.NO_REF, List.of(), List.of());
    }

    /**
     * 按有效/缺失条号推导结论。
     *
     * @param validArticleNos   存在的条号（库里规范形式）
     * @param missingArticleNos 不存在的条号（回答原文形式）
     * @return 校验结果
     */
    public static ValidationResult of(List<String> validArticleNos, List<String> missingArticleNos) {
        List<String> valid = validArticleNos == null ? List.of() : validArticleNos;
        List<String> missing = missingArticleNos == null ? List.of() : missingArticleNos;
        if (valid.isEmpty() && missing.isEmpty()) {
            return noRef();
        }
        return new ValidationResult(missing.isEmpty() ? Status.ALL_EXIST : Status.PARTIAL_MISS,
                valid, missing);
    }

    /**
     * 取校验结论。
     *
     * @return ALL_EXIST / PARTIAL_MISS / NO_REF
     */
    public Status getStatus() {
        return status;
    }

    /**
     * 取不存在的条号（回答原文形式，含「第…条」）。
     *
     * @return 不可变列表；无缺失时为空列表，**不返回 null**
     */
    public List<String> getMissingArticleNos() {
        return missingArticleNos;
    }

    /**
     * 取存在的条号（库里规范形式，如 第六百八十条）。
     *
     * @return 不可变列表；无有效引用时为空列表，**不返回 null**
     */
    public List<String> getValidArticleNos() {
        return validArticleNos;
    }
}
