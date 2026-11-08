/*
 * 职责：faq 表的实体映射（常见问题，第 7 段 A1 建表）。
 * 调用方：modules/ops 的 FaqService（C5 的 APP 55/56 两页数据源）。
 * 数据来源：V700__seg7_tables.sql（9 字段）。
 * 注意：
 *   1. **答案必须从库里读**（不能写死在前端）：运营改库，APP 页面跟着变——
 *      这是 C5 算做完第 2 条的验收方式。
 *   2. `answer` 是 TEXT，支持 `\n` 换行（前端按行渲染）。
 *   3. 停用语义用 `status`（active / disabled），不用 `enabled` 列——
 *      照技术路线原文，A-24/A-25 页面的筛选也按 status。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 常见问题。
 */
@Data
@TableName("faq")
public class Faq {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类：account / pay / lawyer / case。 */
    private String category;

    /** 问题。 */
    private String question;

    /** 答案。 */
    private String answer;

    /** 语种。 */
    private String lang;

    /** 排序（升序）。 */
    private Integer sortOrder;

    /** 状态：active / disabled。 */
    private String status;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
