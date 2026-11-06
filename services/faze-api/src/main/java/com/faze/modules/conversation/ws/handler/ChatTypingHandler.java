/*
 * 职责：处理 `chat.typing`——输入状态转推对端（G6.4）。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler（按 type 分发）。
 * 数据来源：信封 data：`conversationId`、`isTyping`。
 * 注意：
 *   1. **服务端不落库**（工作流 G6.4 第 3 条）：输入状态是瞬时的，落库只会在库里堆垃圾，
 *      而且「对方正在输入」本来就允许丢（3 秒无输入自动消失是客户端行为）。
 *   2. **仍然要做成员校验**：不做的话，任何人可以给任意会话的对方推送假「正在输入」
 *      （技术路线 §7.3「V2.1 缺失」里点名的场景）。
 *   3. 对端离线时**直接丢弃**，不写 message_offline：补推一条 3 秒前的「正在输入」毫无意义。
 *   4. 高频消息：客户端应节流（3 秒一次），服务端不做限流（段 2 无 IM 频控配置）。
 */
package com.faze.modules.conversation.ws.handler;

import com.faze.modules.conversation.service.ConversationMember;
import com.faze.modules.conversation.service.ConversationService;
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
 * `chat.typing` 处理器。
 */
@Component
@RequiredArgsConstructor
public class ChatTypingHandler implements ChatTypeHandler {

    private final ConversationService conversationService;
    private final OnlineRegistry onlineRegistry;
    private final ChatSocketSender sender;

    @Override
    public String type() {
        return "chat.typing";
    }

    @Override
    public void handle(ChatContext context, JsonNode data) {
        Long conversationId = ChatJson.requireLong(data, "conversationId");
        boolean typing = ChatJson.optionalBoolean(data, "isTyping", true);

        ConversationMember member = conversationService.assertMember(conversationId, context.userId());
        Long peerUserId = member.getPeerUserId();
        if (peerUserId == null) {
            return;
        }
        WebSocketSession peerSession = onlineRegistry.sessionOf(peerUserId);
        if (peerSession == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("isTyping", typing);
        sender.send(peerSession, "chat.typing", payload);
    }
}
