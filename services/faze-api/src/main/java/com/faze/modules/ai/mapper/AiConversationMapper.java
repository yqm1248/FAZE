/*
 * 职责：ai_conversation 的数据访问。
 * 调用方：modules/ai/service（C2 提交、G 组列表/删除）、modules/ai/controller。
 * 数据来源：faze.ai_conversation。
 * 注意：
 *   1. 用户删除 = `UPDATE ... SET deleted_at`，**不物理删除**；C 端列表查询必须带
 *      `deleted_at IS NULL`，后台 A-28 查询**不带**这个条件（要能查已删会话）。
 *   2. 归属校验一律写进 SQL（`WHERE id=? AND user_id=?`），不许先查到再在 Java 里比对——
 *      后者会把「别人的会话」读进内存，属于越权读。
 *   3. 本 Mapper 只放确实需要的自定义 SQL，其余走 MyBatis-Plus 的 BaseMapper。
 */
package com.faze.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ai.entity.AiConversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话 Mapper。
 */
@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {
}
