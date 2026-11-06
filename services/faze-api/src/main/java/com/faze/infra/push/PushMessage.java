/*
 * 职责：一条离线推送的内容（标题 / 正文 / 业务载荷 / 接收方）。
 * 调用方：infra/push/PushSender 的实现、modules/conversation/task/PushTask。
 * 数据来源：由 PushTask 从 conversation_message + conversation 组装。
 * 注意：
 *   1. **正文里不许出现消息内容**（技术路线 §7.5：推送只带「您有一条新消息」）：
 *      锁屏可见的通知会泄露聊天内容，这是隐私红线。
 *   2. `payload` 必须带 `conversationId` 与 `messageId`（G7.3 点击直达会话的依据），
 *      客户端只依赖这两个字段做跳转，不要塞整条消息。
 *   3. `title` / `body` 是**系统通知栏文案**，无法走客户端 i18n（通知在系统层渲染），
 *      由 `sys_config` 配置（默认中文），段 8 做多语言时按用户 `lang` 取对应配置。
 */
package com.faze.infra.push;

import java.util.Map;

/**
 * 离线推送内容。
 *
 * @param userId  接收方用户（app_user.id）
 * @param title   通知标题
 * @param body    通知正文（**不含聊天内容**）
 * @param payload 业务载荷（conversationId / messageId）
 */
public record PushMessage(Long userId, String title, String body, Map<String, String> payload) {
}
