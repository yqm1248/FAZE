/*
 * 职责：A-28「AI 对话检索」会话列表行。
 * 调用方：modules/ai/admin/controller/AdminAiConversationController#list（RuoYi TableDataInfo 的 rows）。
 * 数据来源：AiConversationAdminService#list → AiConversationMapper#selectAdminPage（1 条聚合 SQL）。
 * 注意：
 *   1. **含已软删会话**：`deletedAt` 非空时页面要标「用户已删除」（《AI对话留存策略》§三），
 *      所以本 VO 必须带 `deletedAt`，不能过滤掉。
 *   2. `userName` 来自 `COALESCE(app_user.nickname, app_user.phone)`：设计稿左栏「用户」列
 *      需要可读名称，后台可见原手机号（脱敏口径只管日志与导出，不管后台展示）。
 *   3. `questionSummary` = 首条 user 消息前 30 字；`refCount` = 该会话 assistant 消息 refs 总条数。
 *      这两个是设计稿左栏的两个信息位，跟着列表一次返回，避免前端 N+1。
 *   4. `id` / `userId` 是 Long，序列化为字符串（JacksonConfig 全局规则）。
 */
package com.faze.modules.ai.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * A-28 会话列表行。
 */
@Data
public class AdminAiConversationRowVO {

    /** 会话主键（序列化为字符串）。 */
    private Long id;

    /** 归属用户 id（序列化为字符串）。 */
    private Long userId;

    /** 用户展示名：昵称优先，昵称空回退手机号。 */
    private String userName;

    /** 会话标题（首问前 30 字）。 */
    private String title;

    /** 会话语言（段 3 固定 zh-Hans）。 */
    private String lang;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）；非空 = 用户已删除，后台仍可查。 */
    private LocalDateTime deletedAt;

    /** 首条 user 消息的前 30 字（设计稿左栏「问题摘要」）。 */
    private String questionSummary;

    /** 该会话 assistant 消息 refs 快照的总条数。 */
    private Long refCount;
}
