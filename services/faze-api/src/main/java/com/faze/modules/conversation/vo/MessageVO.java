/*
 * 职责：单条消息出参（REST 历史与 WebSocket `chat.message` / `chat.ack` 共用同一结构）。
 * 调用方：modules/conversation/service/MessageService、modules/conversation/ws/handler/*。
 * 数据来源：faze.conversation_message。
 * 注意：
 *   1. **已撤回的消息不回传 `content` / `fileUrl` / `fileName`**（工作流 G6.1 第 4 条）：
 *      只给 `recalled=true` 与 `msgType`（客户端据此显示「消息已撤回」占位）。
 *      原文留在库里（审计与段 7 的聊天记录抽查要用），但接口永不返回。
 *   2. **气泡方向只看 `mine`**（查看者视角，由 `MessageService#toVO` 按 viewerRole 计算）；
 *      `senderRole` + `senderId` 只承载身份语义，且 senderId 是分域 id
 *      （用户侧 app_user.id、律师侧 lawyer.id），**不能**拿去和当前登录用户 id 比较。
 *      **不返回发送者昵称**，一次历史 20 条会变成 20 次用户查询（N+1）；
 *      名称由会话列表与页面上下文提供。
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

    /**
     * 是否**当前查看者自己发的**（气泡方向唯一依据：true=靠右、false=靠左）。
     *
     * <p>为什么不在前端用 senderId 比较：senderId 是分域 id（用户侧 app_user.id、
     * 律师侧 lawyer.id），验收律师 app_user.id=1 而 lawyer.id=2，比较恒 false。
     * 用 `Boolean` 而不是 `boolean`：前端先上线/后端先上线时缺字段应为 `undefined`，
     * 前端按「不是自己」降级（一律 `mine === true` 判断，不写 `!mine`）。</p>
     */
    private Boolean mine;

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
