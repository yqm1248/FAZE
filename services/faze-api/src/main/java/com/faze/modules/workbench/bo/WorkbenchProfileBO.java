/*
 * 职责：律师资料编辑请求体（`PUT /app/workbench/profile`）。
 * 调用方：modules/workbench/controller/AppWorkbenchController → WorkbenchService。
 * 数据来源：App 68-lawyer-profile 页。
 * 注意：
 *   1. 三个字段**均可选**：只传要改的字段，未传的保持原值（partial update）。
 *   2. `specialties` 改它**不触发重新审核**（§六 R5 裁决）；但要写 lawyer_application_log 留痕。
 *   3. 字段上限按《数据表规范》的列宽：bio 是 TEXT 但限 2000 字（防刷），lawFirm ≤ 100。
 */
package com.faze.modules.workbench.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 律师资料编辑入参（全部可选，partial update）。
 */
@Data
public class WorkbenchProfileBO {

    /** 个人简介；不传表示不改（**不能加 @NotBlank**，否则 partial update 变成全量必填）。 */
    @Size(max = 2000, message = "简介不能超过 2000 字")
    private String bio;

    /** 专长领域标签（1~5 个）；不传表示不改。 */
    @Size(max = 5, message = "专长领域最多 5 个")
    private List<@NotBlank @Size(max = 20) String> specialties;

    /** 所属律所；不传表示不改。 */
    @Size(max = 100, message = "律所名称不能超过 100 字")
    private String lawFirm;
}
