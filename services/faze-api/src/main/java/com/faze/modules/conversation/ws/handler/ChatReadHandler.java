/*
 * 职责：处理 `chat.read`——上报已读并推对端（G6.4）。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler（按 type 分发）。
 * 数据来源：信封 data：`conversationId`、`lastReadSeq`。
 * 注意：
 *   1. **与 REST `POST /app/conversations/{id}/read` 调同一个 Service 方法**
 *      （`MessageService#markRead`）：两条路径各写一份必然漂移（工作流 G6.4 第 4 条）。
 *   2. 推给对端的 `chat.read` 带 `readerRole`：客户端用它判断「我发的消息被谁读了」。
 *   3. 已读不上报给发送端自己（发送端已经知道自己的已读位置，回推只是噪音）。
 *   4. `lastReadSeq` 由服务端 clamp 到会话最大 seq：客户端时钟/竞态导致的超前值不该被接受。
 *   5. **只有 `MarkReadResult#advanced` 为真时才推**：重复上报（重连补报同一位置、
 *      客户端 effect 重跑）不推，否则对端收到回执→重渲染→再上报，形成已读回环风暴。
 */
package com.faze.modules.conversation.ws.handler;

import com.faze.modules.conversation.service.ConversationMember;
import com.faze.modules.conversation.service.ConversationService;
import com.faze.modules.conversation.service.MessageService;
import com.faze.modules.conversation.service.OnlineRegistry;
import com.faze.modules.conversation.ws.ChatContext;
import com.faze.modules.conversation.ws.ChatSocketSender;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * `chat.read` 处理器。
 */
@Component
@RequiredArgsConstructor
public class ChatReadHandler implements ChatTypeHandler {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final OnlineRegistry onlineRegistry;
    private final ChatSocketSender sender;

    @Override
    public String type() {
        return "chat.read";
    }

    @Override
    public void handle(ChatContext context, JsonNode data) {
        Long conversationId = ChatJson.requireLong(data, "conversationId");
        Long lastReadSeq = ChatJson.requireLong(data, "lastReadSeq");

        // 先取成员关系：既做鉴权，也拿到「推给谁」
        ConversationMember member = conversationService.assertMember(conversationId, context.userId());
        MessageService.MarkReadResult result = messageService.markRead(context.userId(), conversationId,
                lastReadSeq);

        // 已读位置没前进（重复上报 / 重连补报同一位置）就不推：
        // 推了只会让对端重渲染并可能再触发它自己的上报，形成回环。
        if (!result.advanced()) {
            return;
        }

        Long peerUserId = member.getPeerUserId();
        if (peerUserId == null) {
            return;
        }
        org.springframework.web.socket.WebSocketSession peerSession = onlineRegistry.sessionOf(peerUserId);
        if (peerSession == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("readerRole", result.readerRole().getCode());
        payload.put("lastReadSeq", result.lastReadSeq());
        sender.send(peerSession, "chat.read", payload);
    }
}
