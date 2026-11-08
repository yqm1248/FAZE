/*
 * 职责：A-28 会话主体信息（详情接口里的 `conversation` 字段）。
 * 调用方：modules/ai/admin/vo/AdminAiConversationDetailVO。
 * 数据来源：faze.ai_conversation + app_user（用户名）。
 * 注意：
 *   1. **含已软删会话**：`deletedAt` 非空 = 用户已删除，页面要标标签（《AI对话留存策略》§三）。 
 *   2. 与列表行的区别：详情不需要 questionSummary / refCount（消息已经全量返回，前端自己算得出来），
 *      但保留 userName 让右侧面板能显示「谁问的」。
 */
package com.faze.modules.ai.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * A-28 会话主体信息。
 */
@Data
public class AdminAiConversationVO {

    /** 会话主键（序列化为字符串）。 */
    private Long id;

    /** 归属用户 id（序列化为字符串）。 */
    private Long userId;

    /** 用户展示名：昵称优先，昵称空回退手机号。 */
    private String userName;

    /** 会话标题（首问前 30 字）。 */
    private String title;

    /** 会话语言。 */
    private String lang;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）；非空 = 用户已删除。 */
    private LocalDateTime deletedAt;
}
