/*
 * 职责：G1「APP 对话历史列表」的一行（含首条 assistant 摘要与分页总数）。
 * 调用方：modules/ai/mapper/AiConversationMapper#selectHistoryPage
 *         ← modules/ai/service/AiConversationService#list ← modules/ai/controller/AiConversationController#list。
 * 数据来源：`resources/mapper/ai/AiConversationMapper.xml` 的单条窗口函数 SQL
 *           （ai_conversation + ai_message）。
 * 注意：
 *   1. `summary` 是 SQL 里 `LEFT(首条 assistant 消息.content, 30)`，**不是消息全文**；
 *      列表只用于展示摘要，避免把 L4 级消息原文整段查进内存（《数据表规范》§9.2）。
 *   2. `total` 来自 `COUNT(*) OVER ()`：列表与总数**同一条 SQL** 返回，
 *      既不用二次 count，也不会出现「分页与总数不一致」。
 *   3. 本行只在本包与服务内部使用，**不对外输出**；出参是 AiConversationRowVO。
 */
package com.faze.modules.ai.mapper;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 对话历史列表行（内部查询结果）。
 */
@Data
public class AiConversationListRow {

    /** 会话主键（序列化交给出参 VO 的 Long 类型统一转字符串）。 */
    private Long id;

    /** 会话标题（首问前 30 字，建会话时由 C2 生成）。 */
    private String title;

    /**
     * 最后活动时间。
     *
     * <p>取 `ai_conversation.updated_at`（与工作流 G1 第 2 条的「按 updated_at DESC」同源）。
     * 该字段由 `AiQuestionService#insertUserMessage` 在每轮提问落库后刷新（lead 2026-11-07 裁决：
     * 「最后活跃时间」的语义放在会话表上，而不是把缺陷藏进查询里），所以本字段表示
     * **最后一次提问的时刻**；回答完成与提问相距秒级，对 G1 的分组/排序无影响。</p>
     */
    private LocalDateTime lastMessageAt;

    /** 首条 assistant 消息的前 30 字（SQL 里截断；可能为 null）。 */
    private String summary;

    /** 满足条件的总条数（窗口函数 `COUNT(*) OVER ()`，每行都带同一个值）。 */
    private Long total;
}
