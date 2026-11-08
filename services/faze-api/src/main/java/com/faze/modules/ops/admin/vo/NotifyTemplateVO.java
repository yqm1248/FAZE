/*
 * 职责：A-24 通知模板列表的行出参。
 * 调用方：modules/ops 的 AdminNotifyTemplateService / AdminNotifyTemplateController。
 * 数据来源：faze.notify_template。
 * 注意：
 *   1. `variables` 是服务端解析出来的变量名数组（如 `["userName","orderNo"]`），
 *      前端直接用 tag 展示，不在前端重复写一遍正则。
 *   2. `contentPreview` 截断 60 字（稿面「内容预览」列），完整内容在编辑抽屉里拉详情。
 *   3. 不返回 `createdAt` 之外的内部字段。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 通知模板行。
 */
@Data
public class NotifyTemplateVO {

    /** 主键。 */
    private Long id;

    /** 模板编码。 */
    private String templateCode;

    /** 语种。 */
    private String lang;

    /** 标题模板。 */
    private String title;

    /** 正文模板（完整）。 */
    private String content;

    /** 正文预览（截断 60 字）。 */
    private String contentPreview;

    /** 渠道。 */
    private String channel;

    /** 状态：active / disabled。 */
    private String status;

    /** 变量名列表。 */
    private List<String> variables;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
