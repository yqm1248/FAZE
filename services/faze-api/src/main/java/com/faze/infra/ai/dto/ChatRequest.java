/*
 * 职责：豆包（火山方舟）OpenAI 兼容 `/api/v3/chat/completions` 的请求体。
 * 调用方：infra/ai/impl/DoubaoClientImpl、DoubaoClientStub；C3 的 AiStreamService 从 Redis 恢复
 *         messages 与 model 后组装本类。
 * 数据来源：D1 PromptAssembler 的 PromptPayload（model + messages），以及 sys_config 的 ai_model。
 * 注意：
 *   1. **只带上游需要的字段**：model / messages / stream。温度、max_tokens 等本段不设，
 *      避免「运营以为改的是配置、其实被代码写死」的漂移。
 *   2. 流式必须由调用方显式 `setStream(true)`；`DoubaoClient.stream()` 内部会再兜底改 true，
 *      因为「流式收到 0 个 chunk 但 HTTP 200」最常见的根因就是忘了传 stream=true（C1 卡住项）。
 *   3. messages 里系统指令与用户问题**物理分离**（D1 第 7 条），本类不做字符串拼接。
 */
package com.faze.infra.ai.dto;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上游对话请求体。
 */
@Data
@NoArgsConstructor
public class ChatRequest {

    /** 模型 id（含上下文后缀，如 doubao-seed-1.6-32k）。为空时由 AiProperties 的兜底值填。 */
    private String model;

    /** 消息列表：system → 历史 → 当前 user（顺序由 D1 排好，本类不改）。 */
    private List<ChatMessage> messages;

    /** 是否流式。true 时上游返回 text/event-stream。 */
    private boolean stream;
}
