/*
 * 职责：处理 `chat.history`——断线重连后拉增量消息（G6.5 的硬锚点 `2-A9`）。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler（按 type 分发）。
 * 数据来源：信封 data：`conversationId`、`beforeSeq`（可选）、`afterSeq`（可选）、`limit`。
 * 注意：
 *   1. **两种语义**（技术路线 §7.2.1 只定义了 `beforeSeq`，G6.5 需要增量）：
 *      - 传 `afterSeq` → 返回 `seq > afterSeq` 的**升序**增量（重连补漏用的就是这个）；
 *      - 传 `beforeSeq` → 返回 `seq < beforeSeq` 的**升序**历史（向上翻页）；
 *      - 都不传 → 最新一页。
 *      两者同时传时 `afterSeq` 优先（增量比重翻历史更符合「我刚重连」的意图）。
 *   2. 结果按 **seq 升序**返回（技术路线 §7.4 重连要求「服务端按 seq 升序返回」）。
 *   3. `hasMore=true` 时客户端带 `nextCursor`（= 本批最大 seq）作为新的 `afterSeq` 继续拉，
 *      直到 `hasMore=false`。
 *   4. 撤回消息在出参里 `recalled=true` 且**不带 content**（同 REST）。
 */
package com.faze.modules.conversation.ws.handler;

import com.faze.common.pojo.CursorResult;
import com.faze.modules.conversation.service.MessageService;
import com.faze.modules.conversation.vo.MessageVO;
import com.faze.modules.conversation.ws.ChatContext;
import com.faze.modules.conversation.ws.ChatSocketSender;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * `chat.history` 处理器。
 */
@Component
@RequiredArgsConstructor
public class ChatHistoryHandler implements ChatTypeHandler {

    private final MessageService messageService;
    private final ChatSocketSender sender;

    @Override
    public String type() {
        return "chat.history";
    }

    @Override
    public void handle(ChatContext context, JsonNode data) {
        Long conversationId = ChatJson.requireLong(data, "conversationId");
        Long afterSeq = ChatJson.optionalLong(data, "afterSeq");
        Long beforeSeq = ChatJson.optionalLong(data, "beforeSeq");
        Long limit = ChatJson.optionalLong(data, "limit");

        CursorResult<MessageVO> result;
        if (afterSeq != null) {
            result = messageService.incremental(context.userId(), conversationId, afterSeq,
                    limit == null ? null : limit.intValue());
        } else {
            result = messageService.history(context.userId(), conversationId, beforeSeq,
                    limit == null ? null : limit.intValue());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("messages", result.getList());
        payload.put("hasMore", result.isHasMore());
        payload.put("nextCursor", result.getNextCursor());
        sender.send(context.session(), "chat.history", payload);
    }
}
