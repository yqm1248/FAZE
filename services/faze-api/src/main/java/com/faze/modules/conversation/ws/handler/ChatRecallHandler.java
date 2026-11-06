/*
 * 职责：处理 `chat.recall`——撤回消息并通知双方（G6.4）。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler（按 type 分发）。
 * 数据来源：信封 data：`messageId`。
 * 注意：
 *   1. 四条校验全在 `MessageService#recall`（成员 / 消息归属该会话 / 发送者是自己 / 2 分钟内），
 *      本类**不做任何校验**，否则 WS 与其它入口会出现两套规则。
 *   2. **双方都要收到 `chat.recall`**：撤回者自己也要把气泡改成「已撤回」
 *      （多端登录时另一台设备也要同步）。
 *   3. 重复撤回是幂等成功（`applied=false`），仍然回执——客户端可能重连后重发，
 *      回错误只会让它把消息标成「撤回失败」。
 *   4. 通知里**不带原文**：撤回的意义就是原文不再下发（库里有，接口不回）。
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
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;

/**
 * `chat.recall` 处理器。
 */
@Component
@RequiredArgsConstructor
public class ChatRecallHandler implements ChatTypeHandler {

    private final ConversationService conversationService;
    private final MessageService messageService;
    private final OnlineRegistry onlineRegistry;
    private final ChatSocketSender sender;

    @Override
    public String type() {
        return "chat.recall";
    }

    @Override
    public void handle(ChatContext context, JsonNode data) {
        Long messageId = ChatJson.requireLong(data, "messageId");
        MessageService.RecallResult result = messageService.recall(context.userId(), messageId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("conversationId", result.message().getConversationId());

        // 撤回者自己（当前连接）也收一份：前端要把本机气泡改成「已撤回」
        sender.send(context.session(), "chat.recall", payload);

        ConversationMember member = conversationService.assertMember(
                result.message().getConversationId(), context.userId());
        Long peerUserId = member.getPeerUserId();
        if (peerUserId == null) {
            return;
        }
        WebSocketSession peerSession = onlineRegistry.sessionOf(peerUserId);
        if (peerSession != null) {
            sender.send(peerSession, "chat.recall", payload);
        }
    }
}
