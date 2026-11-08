/*
 * 职责：单条消息出参（REST 历史与 WebSocket `chat.message` / `chat.ack` 共用同一结构）。
 * 调用方：modules/conversation/service/MessageService、modules/conversation/ws/handler/*。
 * 数据来源：faze.conversation_message。
 * 注意：
 *   1. **已撤回的消息不回传 `content` / `fileUrl` / `fileName`**（工作流 G6.1 第 4 条）：
 *      只给 `recalled=true` 与 `msgType`（客户端据此显示「消息已撤回」占位）。
 *      原文留在库里（审计与段 7 的聊天记录抽查要用），但接口永不返回。
 *   2. `senderRole` + `senderId` 是判断气泡方向与头像的依据；**不返回发送者昵称**，
 *      一次历史 20 条会变成 20 次用户查询（N+1）；名称由会话列表与页面上下文提供。
 *   3. `readByPeer` 只对「自己发的消息」有意义：对端已读为 true（用于「已读」小字标）。
 *   4. `clientMsgId` 会回传：客户端靠它把自己「发送中」的气泡替换成服务端确认的那条。
 */
package com.faze.modules.conversation.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 消息出参。
 */
@Data
public class MessageVO {

    /** 消息主键。 */
    private Long id;

    /** 所属会话。 */
    private Long conversationId;

    /** 会话内序号（游标）。 */
    private Long seq;

    /** 客户端幂等键（回传给发送方做本地替换）。 */
    private String clientMsgId;

    /** 发送者角色：user / lawyer / system / customer_service。 */
    private String senderRole;

    /** 发送者 ID（user 是 app_user.id，lawyer 是 lawyer.id）。 */
    private Long senderId;

    /** 消息类型：text / image / file / system。 */
    private String msgType;

    /** 文本内容；已撤回时为 null。 */
    private String content;

    /** 文件/图片 URL；已撤回时为 null。 */
    private String fileUrl;

    /** 文件名；已撤回时为 null。 */
    private String fileName;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** 是否已撤回。 */
    private boolean recalled;

    /** 对端是否已读（只对自己发的消息有意义）。 */
    private boolean readByPeer;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
