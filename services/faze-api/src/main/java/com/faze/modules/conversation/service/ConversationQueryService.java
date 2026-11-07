/*
 * 职责：conversation 域的只读查询出口，供其它业务域跨域读取会话（段 5 D1 工作台客户咨询）。
 * 调用方：modules/workbench/service/WorkbenchServiceImpl。
 * 数据来源：conversation 表。
 * 注意：
 *   1. **只读**：会话的写入（发消息、已读、摘要）一律走 `ConversationService` / `MessageService`，
 *      不在这里开口子（后端代码规范 §2.3）。
 *   2. 返回实体而不是新造 DTO：与 `LawyerQueryService.findById` 的既有做法一致。
 *   3. 调用方必须自己判 `deleted_at`（本接口的查询已带 `IS NULL`）。
 */
package com.faze.modules.conversation.service;

import com.faze.modules.conversation.entity.Conversation;
import java.util.List;

/**
 * 会话只读查询。
 */
public interface ConversationQueryService {

    /**
     * 取某律师的会话列表（按最近消息时间倒序，未读条数由调用方排序）。
     *
     * @param lawyerId 律师主键
     * @return 会话列表（不含已删除）
     */
    List<Conversation> listByLawyerId(Long lawyerId);

    /**
     * 取「用户 × 律师」的唯一会话（一个用户对一个律师只有一条，段 2 拍板第 6 项）。
     *
     * @param userId   用户主键
     * @param lawyerId 律师主键
     * @return 会话；没有则返回 null
     */
    Conversation findByUserAndLawyer(Long userId, Long lawyerId);
}
