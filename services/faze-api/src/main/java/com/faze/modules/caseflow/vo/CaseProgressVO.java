/*
 * 职责：案件进度节点响应（`GET /app/cases/{id}/progress` 的列表项，也是推进接口的返回）。
 * 调用方：modules/caseflow/controller/AppCaseController、App 54-case-progress 页。
 * 数据来源：case_progress。
 * 注意：
 *   1. 时间一律 UTC，前端按 +8 展示（接口规范 §5.5）。
 *   2. `operatorRole` 用于前端决定图标（user/lawyer/admin/system），不展示原始英文。
 */
package com.faze.modules.caseflow.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 案件进度节点。
 */
@Data
public class CaseProgressVO {

    /** case_progress.id。 */
    private Long id;

    /** 顺序号（从 1 起）。 */
    private Integer step;

    /** 节点编码（submitted/review/...）。 */
    private String nodeCode;

    /** 节点标题。 */
    private String title;

    /** 节点说明。 */
    private String description;

    /** 操作人 app_user.id；系统节点为 null。 */
    private Long operatorId;

    /** 操作人角色：user / lawyer / admin / system。 */
    private String operatorRole;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;
}
