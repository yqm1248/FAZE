/*
 * 职责：conversation 表的数据访问。
 * 调用方：modules/conversation/service/*。
 * 数据来源：faze.conversation。
 * 注意：
 *   1. 继承 BaseMapper，单表条件用 LambdaQueryWrapper；本域没有多表联查（律师信息由 Service 批量补）。
 *   2. **所有查询自带 `deleted_at IS NULL`**（段 2 没有 MP 逻辑删除拦截器）。
 *   3. 列表排序一律 `last_msg_at DESC, id DESC`：同一秒建的两个会话要有稳定顺序，
 *      否则分页会出现「第 2 页又出现第 1 页的条目」。
 *   4. 不在这里写「按 (user_id, lawyer_id) 查」的专用方法：唯一键保证最多一行，
 *      `selectOne` + lambda 就够，多一个方法就多一处条件可能漂移。
 */
package com.faze.modules.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.conversation.entity.Conversation;

/**
 * 会话 Mapper。
 */
public interface ConversationMapper extends BaseMapper<Conversation> {
}
