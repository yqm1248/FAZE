/*
 * 职责：案件进度表 case_progress 的实体（9 字段，与 V501 迁移一一对应）。
 * 调用方：caseflow 的 Mapper / Service。
 * 数据来源：case_progress 表（只追加，不修改不删除）。
 * 注意：
 *   1. 没有 updated_at / deleted_at（B 类追加型流水表，见《数据表规范》§四）。
 *   2. `node_code` 是枚举编码，不是自由文本（§三 B4：不许自由输入节点）。
 */
package com.faze.modules.caseflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 案件进度实体。
 */
@Data
@TableName("case_progress")
public class CaseProgress {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** legal_case.id。 */
    private Long caseId;

    /** 顺序号（从 1 起，同案唯一）。 */
    private Integer step;

    /** 节点编码（submitted/review/accepted/supplement/hearing/judgment/effective/closed）。 */
    private String nodeCode;

    /** 节点标题。 */
    private String title;

    /** 节点说明。 */
    private String description;

    /** 操作人 app_user.id；系统节点为 null。 */
    private Long operatorId;

    /** 操作人角色（user/lawyer/admin/system）。 */
    private String operatorRole;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;
}
