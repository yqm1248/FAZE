/*
 * 职责：A-18 模板列表的查询入参（段 4 B1）。
 * 调用方：AdminDocTemplateController#list。
 * 数据来源：前端 query string。
 * 注意：
 *   1. **`pageSize` 服务端必须 clamp 到 100**（《后端代码规范》§8.3）：前端传 100000 会把整表拉出来。
 *      本类的 `@Max` 是第一道，service 里还有第二道（`@Max` 校验失败会返回 401001，体验差；
 *      clamp 是静默收敛，更符合列表页）。
 *   2. `type` 对应表里的 `category`（设计稿列名是"类型"，库里字段是 category），
 *      **查询参数沿用设计稿叫 type**，避免前端改两处。
 *   3. `kw` 只搜 name 与 code，不搜 template_html（搜 HTML 全文没有索引，且会把大字段拉出来）。
 */
package com.faze.modules.document.admin.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模板列表查询。
 */
@Data
public class DocTemplateQueryBO {

    /** 页码，从 1 起。 */
    @Min(value = 1, message = "页码从 1 起")
    private Integer pageNum = 1;

    /** 每页条数。 */
    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer pageSize = 20;

    /** 分类（设计稿的"类型"）：诉讼文书 / 合同文书 / 劳动文书 / 跨境电商。 */
    @Size(max = 50, message = "分类过长")
    private String type;

    /** 状态：draft / active / inactive；不传则不筛。 */
    @Size(max = 20, message = "状态过长")
    private String status;

    /** 语言；段 4 固定 zh-Hans。 */
    @Size(max = 20, message = "语言过长")
    private String lang;

    /** 关键字（匹配 name 或 code）。 */
    @Size(max = 50, message = "关键字过长")
    private String kw;
}
