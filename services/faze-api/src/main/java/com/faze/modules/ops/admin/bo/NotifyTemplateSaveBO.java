/*
 * 职责：A-24 通知模板的新建/修改入参。
 * 调用方：modules/ops 的 AdminNotifyTemplateController。
 * 数据来源：HTTP 请求体。
 * 注意：
 *   1. `title` 与 `content` 的变量集合**必须一致**：标题有 `{{a}}`、正文没有（或反之）
 *      说明模板写错了，保存时应拒绝（工作流 D2 算做完第 2 条）。
 *   2. `lang` 默认 `zh-Hans`（本段只做中文）。
 *   3. `channel` 默认 `inapp`；短信渠道本段只存配置不接服务商。
 */
package com.faze.modules.ops.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模板保存入参。
 */
@Data
public class NotifyTemplateSaveBO {

    /** 模板编码。 */
    @NotBlank(message = "模板编码不能为空")
    @Size(max = 50, message = "模板编码不能超过 50 字")
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "模板编码只能是小写字母、数字与下划线")
    private String templateCode;

    /** 语种，默认 zh-Hans。 */
    @Size(max = 20, message = "语种不能超过 20 字")
    private String lang;

    /** 标题模板。 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;

    /** 正文模板。 */
    @NotBlank(message = "正文不能为空")
    @Size(max = 1000, message = "正文不能超过 1000 字")
    private String content;

    /** 渠道：inapp / push / sms。 */
    @Pattern(regexp = "inapp|push|sms", message = "渠道取值非法")
    private String channel;
}
