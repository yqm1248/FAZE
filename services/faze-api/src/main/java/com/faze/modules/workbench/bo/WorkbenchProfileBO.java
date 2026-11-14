/*
 * 职责：律师资料编辑请求体（`PUT /app/workbench/profile`）。
 * 调用方：modules/workbench/controller/AppWorkbenchController → WorkbenchService。
 * 数据来源：App 68-lawyer-profile 页。
 * 注意：
 *   1. 四个字段**均可选**：只传要改的字段，未传的保持原值（partial update）。
 *   2. `specialties` 改它**不触发重新审核**（§六 R5 裁决）；但要写 lawyer_application_log 留痕。
 *   3. 字段上限按《数据表规范》的列宽：bio 是 TEXT 但限 2000 字（防刷），lawFirm ≤ 100，
 *      avatarUrl 对应 `lawyer.avatar_url VARCHAR(500)`。
 *   4. `avatarUrl` 传的是 **OSS 对象 key 不是 URL**（`private/avatar/{yyyyMM}/{userId}/{uuid}.{ext}`），
 *      归属校验在 `WorkbenchServiceImpl.updateProfile`（此处只做长度校验）。
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

    /**
     * 头像对象 key（`private/avatar/...`，不是 URL）；不传表示不改。
     *
     * <p>列宽 500；归属（key 里的 userId 必须是本人的 `app_user.id`）由服务层校验。</p>
     */
    @Size(max = 500, message = "头像地址不能超过 500 字")
    private String avatarUrl;
}
