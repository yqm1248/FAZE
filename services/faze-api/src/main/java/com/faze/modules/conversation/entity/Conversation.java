/*
 * 职责：conversation 表的实体映射（IM 会话，一个用户 × 一个律师 = 一条，跨订单复用）。
 * 调用方：modules/conversation/mapper/ConversationMapper、
 *        modules/conversation/service/{ConversationService,MessageService}。
 * 数据来源：V200__seg2_tables.sql 建的 conversation（10 字段）。
 * 注意：
 *   1. **唯一键 `uk_conversation_user_lawyer (user_id, lawyer_id)` 是「一个律师一个会话」的唯一保证**
 *      （段 2 工作流 §零之二 拍板第 6 项）：会话与订单解耦，同一对用户/律师跨订单复用一条会话。
 *      `POST /app/conversations` 是「创建或获取」，冲突时 SELECT 返回已存在的那条。
 *   2. `unread_user` / `unread_lawyer` **是冗余计数**，不是真相：真相是
 *      `conversation_message.read_by_user_at / read_by_lawyer_at`。计数对不上时以消息表重算
 *      （工作流 G6.4「卡住怎么办」），不要反过来拿计数推已读。
 *   3. `last_message` 只存摘要（VARCHAR(200)），前端列表展示用；**不许存完整正文**。
 *   4. 没有 MP 逻辑删除拦截器：所有查询必须自带 `deleted_at IS NULL`。
 */
package com.faze.modules.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * IM 会话。
 */
@Data
@TableName("conversation")
public class Conversation {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** C 端用户 ID（app_user.id）。 */
    private Long userId;

    /** 律师 ID（lawyer.id）。 */
    private Long lawyerId;

    /** 最后一条消息摘要（列表展示用）。 */
    private String lastMessage;

    /** 最后一条消息时间（UTC）。 */
    private LocalDateTime lastMsgAt;

    /** 用户未读数（冗余计数，真相见类注释第 2 条）。 */
    private Integer unreadUser;

    /** 律师未读数（冗余计数）。 */
    private Integer unreadLawyer;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删除时间（UTC）；NULL 表示未删除。 */
    private LocalDateTime deletedAt;
}
