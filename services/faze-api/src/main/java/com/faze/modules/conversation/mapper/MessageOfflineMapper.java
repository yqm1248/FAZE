/*
 * 职责：message_offline 表的数据访问（离线补推队列）。
 * 调用方：modules/conversation/service/OfflineMessageService。
 * 数据来源：faze.message_offline。
 * 注意：
 *   1. 只做「入队 / 按接收方取 / 按消息删除 / 按时间清理」四类操作，
 *      **不在这里判断在线状态**——在线判断在 OnlineRegistry（Redis），Mapper 只管表。
 *   2. 按接收方取消息必须 `ORDER BY seq ASC`：补推顺序错了，客户端按 seq 去重后
 *      会出现「先看到后面的问题、再看到前面的回答」。
 *   3. 删除按 `(user_id, message_id)` 而不是主键：补推可能重复执行（重连两次），
 *      按业务键删是幂等的。
 */
package com.faze.modules.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.conversation.entity.MessageOffline;

/**
 * 离线消息 Mapper。
 */
public interface MessageOfflineMapper extends BaseMapper<MessageOffline> {
}
