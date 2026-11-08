/*
 * 职责：会话成员关系（一次 `assertMember` 的结果），所有 IM 操作的鉴权载体。
 * 调用方：modules/conversation/service/{ConversationService,MessageService}、
 *        modules/conversation/ws/handler/*。
 * 数据来源：faze.conversation + faze.lawyer（律师行的 user_id 用来判定「当前用户是不是承办律师」）。
 * 注意：
 *   1. **这是 IM 域唯一的越权入口**（技术路线 §7.3.1）：REST 与 WebSocket 都必须先拿它，
 *      任何「先查消息再判归属」的写法都是重复实现，迟早漏掉一条路径。
 *   2. `selfId` 是**本域内的身份 id**：用户侧 = `app_user.id`，律师侧 = `lawyer.id`。
 *      消息表 `sender_id` 用的就是这个口径（不是 app_user.id），别混。
 *   3. `peerUserId` 是**推送目标**：对端登录账号的 `app_user.id`
 *      （C 端对端 = 律师的 user_id；律师端对端 = 会话的 user_id）。
 *      律师被清退（查不到 lawyer 行）时 `peerUserId` 为 null，此时消息仍可落库但推不出去。
 *   4. 不缓存：成员关系是安全判断，缓存失效窗口就是越权窗口。
 */
package com.faze.modules.conversation.service;

import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.enums.SenderRole;
import com.faze.modules.account.entity.Lawyer;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 会话成员关系。
 */
@Data
@RequiredArgsConstructor
public class ConversationMember {

    /** 会话实体。 */
    private final Conversation conversation;

    /** 当前用户在会话中的角色。 */
    private final SenderRole role;

    /** 当前用户在本域内的身份 id（用户 = app_user.id；律师 = lawyer.id）。 */
    private final Long selfId;

    /** 对端登录账号的 app_user.id（推送目标）；查不到对端时为 null。 */
    private final Long peerUserId;

    /** 对端类型：user / lawyer。 */
    private final String peerType;

    /** 承办律师行（律师侧为自己、用户侧为对端）；查不到时为 null。 */
    private final Lawyer lawyer;

    /** @return 会话主键 */
    public Long conversationId() {
        return conversation.getId();
    }

    /** @return 当前用户是否律师侧 */
    public boolean isLawyer() {
        return role == SenderRole.LAWYER;
    }
}
