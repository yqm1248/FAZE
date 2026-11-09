/*
 * 职责：conversation_message 表的实体映射（IM 消息，只追加）。
 * 调用方：modules/conversation/mapper/ConversationMessageMapper、
 *        modules/conversation/service/MessageService。
 * 数据来源：V200__seg2_tables.sql 建的 conversation_message（16 字段）。
 * 注意：
 *   1. **只追加**：业务代码里没有 UPDATE content 的入口，只有两类 UPDATE——
 *      标记已读（`read_by_user_at` / `read_by_lawyer_at`）与撤回（`recalled_at`）。
 *      `recalled_at` 置值时 **`msg_type` 不改**（技术路线 §7.4 修正了 V2.1 的语义矛盾）：
 *      类型只是消息形态，撤回是状态；改类型会让「撤回的图片」变成「撤回的文本」。
 *   2. `seq` 是会话内序号，由 Redis `INCR conv:{id}:seq` 生成，配合
 *      `uk_msg_conversation_seq` 保证不重复；**分页一律用 seq 游标，不用 OFFSET**。
 *   3. `client_msg_id` 是客户端幂等键，配合 `uk_msg_client_id` 去重（重发/重连补发靠它）。
 *   4. 撤回后的出参**不回传 content / file_url**（工作流 G6.1 第 4 条），由 Service 层裁剪。
 *   5. `content` 是 TEXT，不截断；但 `conversation.last_message` 只放前 200 字摘要。
 */
package com.faze.modules.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * IM 消息。
 */
@Data
@TableName("conversation_message")
public class ConversationMessage {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话。 */
    private Long conversationId;

    /** 会话内序号（游标分页依据）。 */
    private Long seq;

    /** 客户端幂等键（64 字符内）。 */
    private String clientMsgId;

    /** 发送者角色：user / lawyer / system / customer_service。 */
    private String senderRole;

    /** 发送者 ID（user 是 app_user.id，lawyer 是 lawyer.id；system 无真实发送者，约定存 0）。 */
    private Long senderId;

    /** 消息类型：text / image / file / system。 */
    private String msgType;

    /** 文本内容（text 类型）。 */
    private String content;

    /** 文件 URL（私有桶签名地址，拼装后返回）。 */
    private String fileUrl;

    /** 文件名。 */
    private String fileName;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** 用户已读时间（UTC）。 */
    private LocalDateTime readByUserAt;

    /** 律师已读时间（UTC）。 */
    private LocalDateTime readByLawyerAt;

    /** 撤回时间（UTC）；NULL 表示未撤回。 */
    private LocalDateTime recalledAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
