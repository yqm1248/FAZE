/*
 * 职责：content_review 表的实体映射（内容审核命中记录，第 7 段 A1 建表）。
 * 调用方：modules/ops 的 ContentReviewService（写命中）、AdminContentReviewService（A-20 查询与处置）。
 * 数据来源：V700__seg7_tables.sql（12 字段）。
 * 注意：
 *   1. **本表不存原文全文**：只存命中词（JSON）、命中片段（hit_snippet，≤500 字，已脱敏）、
 *      级别与处置结果。原文永远留在业务表（complaint / feedback / conversation_message 等）
 *      且**一个字节都不改**——这是 §零.4 第 1 条红线，也是 7-A2 的验收对象。
 *   2. `hit_words` 是 JSON 字符串（`[{word,level,category,start,end}]`），
 *      不是独立表——命中词数量小，拆表会让「查一条命中」变成 1+N 次查询。
 *   3. `biz_user_id` 是**内容作者**（不是处置人），用于 A-20 按用户筛选与 B2 的 IDOR 校验。
 *   4. `review_result` 见 {@link com.faze.modules.ops.enums.ReviewResult}；
 *      除 pending 外均为终态（重复处置 410008）。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 内容审核命中记录。
 */
@Data
@TableName("content_review")
public class ContentReview {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型：im_message / review / article_comment / ai_message / complaint / feedback。 */
    private String bizType;

    /** 业务主键（指向原表行 id）。 */
    private Long bizId;

    /** 命中词 JSON：[{word, level, category, start, end}]。 */
    private String hitWords;

    /** 命中片段（前后各 40 字，已脱敏）；不是原文全文。 */
    private String hitSnippet;

    /** 本次命中的最高级别：block / review / warn。 */
    private String level;

    /** 判定引擎：local_trie。 */
    private String engine;

    /** 被审核内容的作者 app_user.id。 */
    private Long bizUserId;

    /** 处置管理员 app_user.id。 */
    private Long reviewerId;

    /** 处置结果：pending / ignored / warned / banned。 */
    private String reviewResult;

    /** 处置时间（UTC）。 */
    private LocalDateTime reviewedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
