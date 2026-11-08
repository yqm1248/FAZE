/*
 * 职责：案例的单个语言版本（保存入参与详情出参共用）。
 * 调用方：AdminCaseLibrarySaveBO#translations、AdminCaseLibraryDetailVO#translations。
 * 数据来源：A-16 录入抽屉顶部的语言 Tab（zh-Hans / bo / ug-Arab-CN / kk-Arab-CN）。
 * 注意：
 *   1. **`zh-Hans` 不走本结构**：源语言正文在主表字段上，这里只传三种译文。
 *   2. 四个正文块（案情简介 / 争议焦点 / 裁判结果 / 案例启示）+ 标题**必须一起切**
 *      （工作流 G1 第 7 条），所以它们同属一个对象，不允许拆成四个接口。
 *   3. `status` 缺省 `pending`：译文允许缺，但必须登记为待译（G4 门禁口径）。
 */
package com.faze.modules.content.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 案例译文入参。
 */
@Data
public class AdminCaseTranslationBO {

    /** 语言：bo / ug-Arab-CN / kk-Arab-CN（不接受 zh-Hans）。 */
    @NotBlank(message = "语言不能为空")
    private String lang;

    /** 译标题。 */
    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;

    /** 译案情简介。 */
    private String summary;

    /** 译争议焦点。 */
    private String disputeFocus;

    /** 译裁判结果。 */
    private String judgment;

    /** 译案例启示。 */
    private String insight;

    /** 状态：draft / reviewed / published / pending；缺省 pending。 */
    private String status;
}
