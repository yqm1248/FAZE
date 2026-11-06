/*
 * 职责：message_offline 表的实体映射（对端离线期间的消息补推队列）。
 * 调用方：modules/conversation/mapper/MessageOfflineMapper、
 *        modules/conversation/service/{MessageService,OfflineMessageService}。
 * 数据来源：V200__seg2_tables.sql 建的 message_offline（6 字段）。
 * 注意：
 *   1. **这是补推队列，不是消息副本**：消息的真相永远是 `conversation_message`，
 *      本表只记「这条消息还没推到某人」，推送成功后删除（工作流 G6.6 第 3 条）。
 *   2. `user_id` 是**接收方**，不是发送方——表名容易被读成「离线的用户」，实际是「发给离线用户」。
 *   3. 清理走 `task_queue`？不：清理是定时批量任务（保留 7 天），不需要逐条可靠投递；
 *      补推成功后按 `(user_id, message_id)` 直接删即可（幂等，多删无害）。
 *   4. 索引 `idx_offline_user_seq` 支撑「重连后按 seq 升序分页补推」。
 */
package com.faze.modules.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 离线消息补推队列行。
 */
@Data
@TableName("message_offline")
public class MessageOffline {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收方用户 ID（app_user.id）。 */
    private Long userId;

    /** 所属会话。 */
    private Long conversationId;

    /** 消息主键（conversation_message.id）。 */
    private Long messageId;

    /** 消息 seq（补推排序依据）。 */
    private Long seq;

    /** 入队时间（UTC），超期清理依据。 */
    private LocalDateTime createdAt;
}
