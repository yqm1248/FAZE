/*
 * 职责：非流式 `chat/completions` 的解析结果（C1 的 chat()）。
 * 调用方：infra/ai/DoubaoClient#chat 的调用方；本段 C3 走流式，非流式主要用于 C1 验收与排障。
 * 数据来源：豆包非流式响应 choices[0].message.content / finish_reason / usage。
 * 注意：
 *   1. `content` 是回答原文（L4 级），**禁止进日志**。
 *   2. 非流式不参与 C3 的续传与落库；若将来有内部工具用它，也必须自己负责落库。
 */
package com.faze.infra.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 非流式回答结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResult {

    /** 完整回答文本（L4 敏感，禁止进日志）。 */
    private String content;

    /** 结束原因（stop / length 等）。 */
    private String finishReason;

    /** 输入 token 数。 */
    private Integer promptTokens;

    /** 输出 token 数。 */
    private Integer completeTokens;
}
