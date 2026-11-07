/*
 * 职责：律师工作台「文书管理」列表行（`GET /app/workbench/documents`）。
 * 调用方：modules/workbench/controller/AppWorkbenchController、App 65-lawyer-docs 页。
 * 数据来源：document 表（按 lawyer_id 归属）+ doc_template.name。
 * 注意：
 *   1. **双视角归属**：document.user_id 是委托人、document.lawyer_id 是律师；
 *      本接口按 `lawyer_id` 过滤（律师看自己代写的），用户侧走 `features/document` 的既有接口。
 *   2. 不返回 ossKey 原文（L4 级敏感），只给 `pdfAvailable` 布尔让前端决定是否显示下载。
 */
package com.faze.modules.workbench.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 律师文书列表行。
 */
@Data
public class WorkbenchDocRowVO {

    /** document.id（Long 序列化为字符串，见接口规范 §5.3）。 */
    private Long id;

    /** 文书标题（模板名 + 委托人简称）。 */
    private String title;

    /** 委托人昵称。 */
    private String clientName;

    /** 文书状态：draft / generated / signed / failed（段 4 的 4 态）。 */
    private String status;

    /** 是否有可用 PDF（决定「发送达」按钮是否可点）。 */
    private Boolean pdfAvailable;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 最后更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /**
     * 首次发送给委托人的时间（UTC，段 5 D2）；null 表示**待发送**。
     *
     * <p>「待发送 / 已发送」由这一列判定，不塞进 `status`（status 是文书生成生命周期）。</p>
     */
    private LocalDateTime sentAt;
}
