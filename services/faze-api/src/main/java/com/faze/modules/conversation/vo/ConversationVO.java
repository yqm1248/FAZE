/*
 * 职责：会话列表行出参（C 端「消息」列表与律师端会话列表共用）。
 * 调用方：modules/conversation/service/ConversationQueryService。
 * 数据来源：faze.conversation + 批量补的律师行 / 用户行。
 * 注意：
 *   1. **`peerType` 明确对端是谁**：C 端列表对端是律师、律师端列表对端是用户。
 *      不给中文 label（文案走 i18n），只给类型 code。
 *   2. `unread` 是「当前查看者视角的未读数」：C 端取 `unread_user`，律师端取 `unread_lawyer`。
 *      两个字段都暴露给客户端会诱导前端自己算，反而算错。
 *   3. `lastMessage` 是摘要（≤200 字，库里就是截断的），**不是完整正文**；
 *      要原文请进会话。
 *   4. 不返回会话的 `userId` / `lawyerId` 之外的隐私字段（律师执业证、用户手机号都不给）。
 */
package com.faze.modules.conversation.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 会话列表行。
 */
@Data
public class ConversationVO {

    /** 会话主键。 */
    private Long id;

    /** 对端标识：C 端 = lawyer.id；律师端 = app_user.id。 */
    private Long peerId;

    /** 对端类型：`lawyer` / `user`。 */
    private String peerType;

    /** 对端名称（律师姓名 / 用户昵称；缺失时由客户端降级文案兜底）。 */
    private String peerName;

    /** 对端头像地址。 */
    private String peerAvatarUrl;

    /** 最后一条消息摘要。 */
    private String lastMessage;

    /** 最后一条消息时间（UTC）。 */
    private LocalDateTime lastMsgAt;

    /** 当前查看者的未读数。 */
    private Integer unread;
}
