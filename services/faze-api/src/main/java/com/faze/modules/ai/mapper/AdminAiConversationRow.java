/*
 * 职责：A-28「AI 对话检索」会话列表的一行（含提问人昵称、问题摘要、引用条数、总数）。
 * 调用方：modules/ai/mapper/AiConversationMapper#selectAdminPage
 *         ← modules/ai/admin/service/AiConversationAdminService#list。
 * 数据来源：`resources/mapper/ai/AiConversationMapper.xml` 的单条聚合 SQL
 *           （ai_conversation + ai_message + app_user）。
 * 注意：
 *   1. **包含已软删会话**：后台要能查到「用户已删除」的会话（《AI对话留存策略》§三），
 *      所以 SQL 里没有 `deleted_at IS NULL` 条件。
 *   2. `userName` = `COALESCE(NULLIF(u.nickname, ''), u.phone)`：昵称没填时回退手机号，
 *      保证设计稿左栏「用户」列永远有可读内容（后台可见原手机号，脱敏口径只管日志与导出）。
 *   3. `refCount` = 该会话 assistant 消息 `refs` 数组的总条数（JSON_TABLE 聚合，一次查完）；
 *      refs 为 NULL（没查过）与 `[]`（查过没引用）都不产生计数。
 *   4. `total` 来自 `COUNT(*) OVER ()`，列表与总数一条 SQL。
 */
package com.faze.modules.ai.mapper;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * A-28 会话列表行（内部查询结果）。
 */
@Data
public class AdminAiConversationRow {

    /** 会话主键。 */
    private Long id;

    /** 归属用户主键。 */
    private Long userId;

    /** 用户展示名：昵称优先，昵称空则用手机号（后台可见原手机号）。 */
    private String userName;

    /** 会话标题（首问前 30 字）。 */
    private String title;

    /** 会话语言（段 3 固定 zh-Hans）。 */
    private String lang;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）；非空表示用户已删除，后台仍可查。 */
    private LocalDateTime deletedAt;

    /** 首条 user 消息的前 30 字（设计稿左栏的「问题摘要」）。 */
    private String questionSummary;

    /** 该会话 assistant 消息 refs 快照的总条数。 */
    private Long refCount;

    /** 满足条件的总条数（窗口函数，每行同一个值）。 */
    private Long total;
}
