/*
 * 职责：上游 chat/completions 请求里的单条消息（role + content）。OpenAI/方舟兼容格式的最小载体。
 * 调用方：infra/ai/dto/ChatRequest（组装上游请求）、modules/ai/service/AiQuestionService（把 D1 的
 *         PromptPayload.messages() 转成本类后序列化进 Redis，供 C3 的 SSE 流取用）。
 * 数据来源：D1 PromptAssembler 的 PromptPayload.messages()（系统指令放 role=system，用户问题放 role=user）。
 * 注意：
 *   1. **字段名必须与上游兼容接口一致**（role / content），改名会让上游 400。
 *   2. **本类会进 Redis 暂存**（C2 写、C3 读），因此必须是可 Jackson 3 序列化/反序列化的
 *      POJO（无参构造 + setter），不要改成 record 或加自定义构造器。
 *   3. 系统 Prompt 不落库（D1 第 6 条）：本类只用于「请求上游」与「Redis 短期暂存」，不进 ai_message。
 */
package com.faze.infra.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上游消息（role + content）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /** 角色：system / user / assistant（上游约定的小写值）。 */
    private String role;

    /** 文本内容（系统指令或用户问题原文；禁止写日志）。 */
    private String content;
}
