/*
 * 职责：OpenAI 兼容 `/chat/completions` 的请求体（上游 2026-11-13 起为 DeepSeek）。
 * 调用方：infra/ai/impl/DoubaoClientImpl、DoubaoClientStub；C3 的 AiStreamService 从 Redis 恢复
 *         messages 与 model 后组装本类。
 * 数据来源：D1 PromptAssembler 的 PromptPayload（model + messages），以及 sys_config 的 ai_model。
 * 注意：
 *   1. **只带上游需要的字段**：model / messages / stream / thinking。温度、max_tokens 等本段不设，
 *      避免「运营以为改的是配置、其实被代码写死」的漂移。
 *   2. 流式必须由调用方显式 `setStream(true)`；`DoubaoClient.stream()` 内部会再兜底改 true，
 *      因为「流式收到 0 个 chunk 但 HTTP 200」最常见的根因就是忘了传 stream=true（C1 卡住项）。
 *   3. messages 里系统指令与用户问题**物理分离**（D1 第 7 条），本类不做字符串拼接。
 *   4. **`thinking` 是成本开关，不是可选装饰**：`deepseek-flash` 直连时上游默认 `enabled`，
 *      实测同一问题 completion_tokens 168（reasoning 138）对关闭后的 38——模板开启状态下输出
 *      token 多 4.4 倍，比直接换 `deepseek-v4-pro` 还贵。本项目是「法条检索 + 短答」，
 *      不需要思维链，所以 DoubaoClientImpl 在组包时固定传 `disabled`；要开思考请改这里而不是改库。
 */
package com.faze.infra.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上游对话请求体。
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    /** 模型 id（DeepSeek 官方名，如 deepseek-flash；不带上下文后缀）。为空时由 AiProperties 的兜底值填。 */
    private String model;

    /** 消息列表：system → 历史 → 当前 user（顺序由 D1 排好，本类不改）。 */
    private List<ChatMessage> messages;

    /** 是否流式。true 时上游返回 text/event-stream。 */
    private boolean stream;

    /**
     * 思考模式开关（DeepSeek V4 起由请求体控制，不再是「换模型名」）。
     *
     * <p>本项目固定 {@link Thinking#disabled()}；字段为 null 时不序列化，由 client 组包兜底。</p>
     */
    private Thinking thinking;

    /**
     * 思考模式参数体，上游形如 `{"type":"disabled"}`。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Thinking {

        /** `enabled` / `disabled`。 */
        private String type;

        /** 关闭思考模式：不产生 reasoning token，首字节更快、成本最低。 */
        public static Thinking disabled() {
            return new Thinking("disabled");
        }

        /** 打开思考模式：产生思维链，输出 token 约 4 倍，仅排障/对比用。 */
        public static Thinking enabled() {
            return new Thinking("enabled");
        }
    }
}
