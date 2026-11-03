/*
 * 职责：豆包探针相关配置的强类型绑定（`faze.probe.ai.*`）。
 * 调用方：DoubaoClient（mock 判定 / 接入点 / 超时）、ProbeController（是否 mock 写进 SSE meta）。
 * 数据来源：application.yml 与环境变量（`FAZE_DOUBAO_*`）。
 * 注意：
 *   1. 规范 §12.3：构造绑定 + @DefaultValue，一个领域一个类。
 *   2. **`model` 是推理接入点 ID（形如 `ep-xxxxxxxx`），不是模型名字**。用错会 404，
 *      所以字段名与注释都写死这个口径。
 *   3. `mock` 默认 true：没有 key 时不静默假装成功，而是明确走 mock 并把状态交给 SSE 的
 *      meta 帧（见 ProbeController 的事件协议）。
 */
package com.faze.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 豆包探针配置。
 *
 * @param mock           是否强制走本地 mock 流，默认 true
 * @param apiKey         豆包 API key，空则自动 mock
 * @param baseUrl        OpenAI 兼容的 chat/completions 地址
 * @param model          推理接入点 ID（`ep-` 开头），不是模型名
 * @param timeoutSeconds 单次请求超时秒数，默认 120
 */
@ConfigurationProperties(prefix = "faze.probe.ai")
public record FazeProbeProperties(
        @DefaultValue("true") boolean mock,
        String apiKey,
        @DefaultValue("https://ark.cn-beijing.volces.com/api/v3/chat/completions") String baseUrl,
        String model,
        @DefaultValue("120") int timeoutSeconds) {
}
