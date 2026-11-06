/*
 * 职责：从上游 SSE 的单个 `data: {...}` 帧里解析出的一块增量（C1 的流式解析结果）。
 * 调用方：infra/ai/impl/DoubaoClientImpl#stream（产出）、modules/ai/service/AiStreamService（消费并三写）。
 * 数据来源：豆包 `/api/v3/chat/completions` 的流式响应，字段位置：
 *           choices[0].delta.content / choices[0].finish_reason / usage.prompt_tokens / usage.completion_tokens。
 * 注意：
 *   1. `content` **可能为 null 或空串**（第一帧常是 role、最后一帧常只有 finish_reason），
 *      调用方必须跳过空 content，不能把它当成「0 字节的上游故障」。
 *   2. `finishReason` / token 数通常只在最后一帧出现；流到一半时它们都是 null，这是正常现象。
 *   3. **本类禁止进日志**（content 是回答原文，L4 级）：日志里只允许出现 chunk 序号、字符数、耗时。
 *   4. 不要在这里做缓冲聚合——每帧独立出来是「真流式」的唯一证据（C1 卡住项第 3 条）。
 */
package com.faze.infra.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上游流式响应的单块增量。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatChunk {

    /** 增量文本（delta.content）；可能为 null/空串，调用方需跳过。 */
    private String content;

    /** 结束原因（choices[0].finish_reason）：stop / length / content_filter 等；中途为 null。 */
    private String finishReason;

    /** 输入 token 数（usage.prompt_tokens）；通常最后一帧才有。 */
    private Integer promptTokens;

    /** 输出 token 数（usage.completion_tokens）；通常最后一帧才有。 */
    private Integer completeTokens;
}
