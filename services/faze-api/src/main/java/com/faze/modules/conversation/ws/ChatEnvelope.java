/*
 * 职责：WebSocket 入站消息信封（统一 JSON：`{type,id,timestamp,data}`）。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler#handleTextMessage。
 * 数据来源：客户端 WS 文本帧。
 * 注意：
 *   1. **`data` 用 JsonNode 承载**：不同 type 的 data 结构完全不同（技术路线 §7.2.1），
 *      用泛型/多态反序列化只会得到一个「什么字段都可能没有」的万能类；
 *      由各 handler 自己按类型取字段，取不到就报 401001。
 *   2. `type` 未知时回 `chat.error`，**不要静默丢弃**——客户端会以为消息发出去了。
 *   3. `id` / `timestamp` 是客户端生成的追踪号与时间戳，服务端只回显 `id`（用作 log 关联），
 *      **不信任 timestamp**（消息时间以服务端落库时间为准）。
 */
package com.faze.modules.conversation.ws;

import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * 入站消息信封。
 */
@Data
public class ChatEnvelope {

    /** 消息类型（chat.send / chat.read / chat.recall / chat.typing / chat.history / ping）。 */
    private String type;

    /** 客户端消息追踪号（服务端只用于日志关联）。 */
    private String id;

    /** 客户端时间戳（不可信，仅透传）。 */
    private String timestamp;

    /** 业务数据（按 type 解释）。 */
    private JsonNode data;
}
