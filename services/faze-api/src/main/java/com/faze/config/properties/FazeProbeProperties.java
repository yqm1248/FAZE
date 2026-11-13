/*
 * 职责：LLM 探针相关配置的强类型绑定（`faze.probe.ai.*`）。
 * 调用方：DoubaoClient（mock 判定 / 模型 / 超时）、ProbeController（是否 mock 写进 SSE meta）。
 * 数据来源：application.yml 与环境变量（`FAZE_DEEPSEEK_*`）。上游 2026-11-13 起为 DeepSeek。
 * 注意：
 *   1. 规范 §12.3：构造绑定 + @DefaultValue，一个领域一个类。
 *   2. **`model` 现在是 DeepSeek 模型名（`deepseek-flash` / `deepseek-v4-pro`）**。豆包时代的
 *      「`model` 必须是推理接入点 ID `ep-xxxxxxxx`」口径已作废——切到 DeepSeek 后不再需要接入点。
 *      旧别名（`deepseek-chat`、`deepseek-reasoner`）仍能被上游接受，但不是官方在售模型名，
 *      且映射目标不保证稳定，**配置一律写官方名**。
 *   3. `mock` 默认 true：没有 key 时不静默假装成功，而是明确走 mock 并把状态交给 SSE 的
 *      meta 帧（见 ProbeController 的事件协议）。
 */
package com.faze.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM 探针配置（上游 DeepSeek）。
 *
 * @param mock           是否强制走本地 mock 流，默认 true
 * @param apiKey         DeepSeek API key，空则自动 mock
 * @param baseUrl        OpenAI 兼容的 chat/completions 完整地址
 * @param model          模型名（`deepseek-flash` / `deepseek-v4-pro`）
 * @param timeoutSeconds 单次请求超时秒数，默认 120
 */
@ConfigurationProperties(prefix = "faze.probe.ai")
public record FazeProbeProperties(
        @DefaultValue("true") boolean mock,
        String apiKey,
        @DefaultValue("https://api.deepseek.com/chat/completions") String baseUrl,
        String model,
        @DefaultValue("120") int timeoutSeconds) {
}
