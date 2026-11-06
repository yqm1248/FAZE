/*
 * 职责：conversation_message 表的数据访问。
 * 调用方：modules/conversation/service/{MessageService,OfflineMessageService}。
 * 数据来源：faze.conversation_message。
 * 注意：
 *   1. 继承 BaseMapper，单表条件用 LambdaQueryWrapper；**本域不写 XML**。
 *   2. 分页一律 `seq` 游标：`WHERE conversation_id=? AND seq < ? ORDER BY seq DESC LIMIT n`。
 *      用 OFFSET 时「新消息插入」会让翻页错位（第 2 页重复第 1 页的最后一条）。
 *   3. 批量 UPDATE（已读）用 LambdaUpdateWrapper，一次 SQL 更新整段 seq 范围，
 *      不要逐条 updateById——50 条历史消息会变成 50 次 SQL。
 *   4. 撤回只写 `recalled_at`，**不改 `msg_type`**（技术路线 §7.4）。
 */
package com.faze.modules.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.conversation.entity.ConversationMessage;

/**
 * 会话消息 Mapper。
 */
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {
}
