/*
 * 职责：一类 WebSocket 消息的处理器（每种 type 一个实现，见技术路线 §7.2.1）。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler（按 type 查表分发）。
 * 数据来源：inbound data（JsonNode）。
 * 注意：
 *   1. 实现类**只做三件事**：取字段 → 调业务 Service → 发回执/推送。
 *      业务校验、鉴权、落库都在 Service 里（否则 WS 与 REST 会写出两套规则）。
 *   2. 抛 `BusinessException` 是契约的一部分：分发器统一转成 `chat.error`，
 *      handler 里不要自己 try/catch 再拼错误帧。
 *   3. `type()` 返回的字符串必须与技术路线 §7.2.1 的表格逐字一致；不要自创 type。
 */
package com.faze.modules.conversation.ws.handler;

import com.faze.modules.conversation.ws.ChatContext;
import tools.jackson.databind.JsonNode;

/**
 * WS 消息处理器。
 */
public interface ChatTypeHandler {

    /**
     * 处理的 type。
     *
     * @return 客户端 → 服务端信封的 type 字符串
     */
    String type();

    /**
     * 处理一条消息。
     *
     * @param context 连接与身份上下文
     * @param data    信封里的 data 节点（可能为 null）
     */
    void handle(ChatContext context, JsonNode data);
}
