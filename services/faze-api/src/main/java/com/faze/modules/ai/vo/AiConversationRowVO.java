/*
 * 职责：G1「APP 对话历史列表」的出参行。
 * 调用方：modules/ai/controller/AiConversationController#list（C 端，需 JWT）。
 * 数据来源：AiConversationService#list → AiConversationMapper#selectHistoryPage（1 条 SQL）。
 * 注意：
 *   1. 字段严格按工作流 G1 第 2 条：`{ id, title, lastMessageAt, summary }`，**不多给**；
 *      分组（今天/昨天/更早）由前端按 `lastMessageAt` 算，服务端不按后端字段分组（避免时区问题）。
 *   2. `id` 声明为 `Long`：JacksonConfig 会把包装类 Long 全局序列化成字符串，
 *      前端拿到的是 `"id": "123"`，与接口规范 §5.3 的「Long 型 ID 一律字符串」一致。
 *   3. `summary` 已是 SQL 截断的前 30 字，前端直接展示，不要再切。
 */
package com.faze.modules.ai.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * APP 对话历史列表行。
 */
@Data
public class AiConversationRowVO {

    /** 会话主键（序列化为字符串）。 */
    private Long id;

    /** 会话标题（首问前 30 字）。 */
    private String title;

    /**
     * 最后活动时间（UTC）。
     *
     * <p>来源 `ai_conversation.updated_at`，每轮提问落库后由 AiQuestionService 刷新
     * （见 AiConversationListRow 注释），所以是「最后一次提问的时刻」。前端按它分组「今天/昨天/更早」。</p>
     */
    private LocalDateTime lastMessageAt;

    /** 首条 AI 回答前 30 字；该会话还没有 assistant 消息时为 null。 */
    private String summary;
}
