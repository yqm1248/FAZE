/*
 * 职责：PC A-12「查看聊天记录」抽屉的出参（G9.2；拍板第 7 项：管理端可看聊天原文）。
 * 调用方：modules/conversation/admin/controller/AdminConversationController。
 * 数据来源：faze.conversation_message。
 * 注意：
 *   1. **管理端返回原文，包括已撤回的消息**：撤回对 C 端生效，但审计要能追溯
 *      （段 7 的投诉处理需要原文）。`recalled=true` 只是标记，content 不裁。
 *   2. 每次查看都会写 `operation_log`（拍板第 7 项硬要求）：聊天原文属 L4 敏感信息，
 *      「谁在什么时候看了哪段对话」必须留痕。
 *   3. 这是**只读**视图：管理端不能发消息、不能撤回、不能删除
 *      （IM 的写操作只属于 C 端与律师端）。
 *   4. `senderRole` + `senderId` 足以让审核知道是谁说的；不额外带昵称（避免多查两次用户表，
 *      抽屉里只需要「用户/律师」两个角色标签）。
 */
package com.faze.modules.conversation.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 聊天记录行。
 */
@Data
public class AdminChatMessageVO {

    /** 消息主键。 */
    private Long id;

    /** 会话主键。 */
    private Long conversationId;

    /** 会话内序号。 */
    private Long seq;

    /** 发送者角色：user / lawyer / system。 */
    private String senderRole;

    /** 发送者 id。 */
    private Long senderId;

    /** 消息类型。 */
    private String msgType;

    /** 文本内容（含已撤回消息的原文）。 */
    private String content;

    /** 文件地址。 */
    private String fileUrl;

    /** 文件名。 */
    private String fileName;

    /** 是否已撤回（仅标记，不隐藏内容）。 */
    private boolean recalled;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
