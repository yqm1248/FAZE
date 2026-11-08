/*
 * 职责：案例的法条引用条目（保存入参与出参共用）。
 * 调用方：AdminCaseLibrarySaveBO / AdminCaseLibraryDetailVO。
 * 数据来源：A-16 录入抽屉「法条引用」Tab 的搜索选择结果。
 * 注意：
 *   1. `lawItemId` 是段 3 法条库的主键；保存前服务端会用它回查并校验条号存在。
 *   2. `articleNo` 存**条文原文的条号写法**（如「第七百三十三条」），不是数字序号：
 *      App 详情页要直接拼成「《民法典》第七百三十三条」渲染，存数字还要再转换一次。
 *   3. `lawName` 存法律全称（中华人民共和国民法典），与 `law_item.law_name` 一致。
 */
package com.faze.modules.content.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 案例法条引用项。
 */
@Data
public class AdminLawRefBO {

    /** 法律全称。 */
    @NotBlank(message = "法律名称不能为空")
    @Size(max = 100, message = "法律名称不能超过 100 字")
    private String lawName;

    /** 条号原文（如「第七百三十三条」或「733」）。 */
    @NotBlank(message = "条号不能为空")
    @Size(max = 50, message = "条号不能超过 50 字")
    private String articleNo;

    /** 法条库主键（可空；给了就必须存在）。 */
    private Long lawItemId;
}
