/*
 * 职责：处理 `chat.send`——发消息（落库 + ack 给发送端 + 推给对端/入离线队列）。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler（按 type 分发）。
 * 数据来源：信封 data：`conversationId`、`msgType`、`content`、`clientMsgId`
 *          以及本段补的可选字段 `fileUrl` / `fileName` / `fileSize`（图片/文件消息）。
 * 注意：
 *   1. **`senderId` 一律取自握手身份**（`context.userId()`），data 里的任何 senderId 都忽略。
 *   2. ack 必须在推送对端**之前**发：客户端先把自己的「发送中」气泡变成「已发送」，
 *      再处理对端消息；反过来会出现「对方的回复先到、自己的消息还是转圈」。
 *   3. 幂等命中（`duplicated=true`）时**只回 ack、不再推对端**：重发不是新消息，
 *      再推一次会让对端看到两条一样的。
 *   4. 协议表（技术路线 §7.2.1）的 `chat.send.data` 只列了 4 个字段，没有文件字段；
 *      段 2 补 `fileUrl`/`fileName`/`fileSize` 三个可选字段（G6.3 第 6 条要求支持图片/文件），
 *      与 `chat.message` 的出参字段对齐。这是扩展，不是改动既有字段语义。
 */
package com.faze.modules.conversation.ws.handler;

import com.faze.modules.conversation.service.MessagePushService;
import com.faze.modules.conversation.service.MessageService;
import com.faze.modules.conversation.ws.ChatContext;
import com.faze.modules.conversation.ws.ChatSocketSender;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * `chat.send` 处理器。
 */
@Component
@RequiredArgsConstructor
public class ChatSendHandler implements ChatTypeHandler {

    private final MessageService messageService;
    private final MessagePushService messagePushService;
    private final ChatSocketSender sender;

    @Override
    public String type() {
        return "chat.send";
    }

    @Override
    public void handle(ChatContext context, JsonNode data) {
        Long conversationId = ChatJson.requireLong(data, "conversationId");
        String msgType = ChatJson.requireText(data, "msgType");
        String content = ChatJson.optionalText(data, "content");
        String clientMsgId = ChatJson.requireText(data, "clientMsgId");
        String fileUrl = ChatJson.optionalText(data, "fileUrl");
        String fileName = ChatJson.optionalText(data, "fileName");
        Long fileSize = ChatJson.optionalLong(data, "fileSize");

        MessageService.SendOutcome outcome = messageService.send(context.userId(), conversationId,
                msgType, content, clientMsgId, fileUrl, fileName, fileSize);

        // 1) 先给发送端 ack（客户端据此把「发送中」气泡改成已发送）
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("clientMsgId", outcome.message().getClientMsgId());
        ack.put("messageId", outcome.message().getId());
        ack.put("seq", outcome.message().getSeq());
        ack.put("createdAt", outcome.message().getCreatedAt() == null
                ? OffsetDateTime.now(ZoneOffset.UTC).toString()
                : outcome.message().getCreatedAt().toString());
        sender.send(context.session(), "chat.ack", ack);

        // 2) 再推对端（幂等命中不推，避免对端看到重复消息）
        if (!outcome.duplicated()) {
            messagePushService.pushToPeer(outcome.member(), outcome.message());
        }
    }
}
