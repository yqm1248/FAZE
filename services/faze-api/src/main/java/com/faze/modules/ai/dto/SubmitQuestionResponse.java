/*
 * 职责：C2 提交接口的响应体——`{ conversationId, messageId, streamUrl, degraded }`。
 * 调用方：modules/ai/controller/AiConversationController、App 的 AI 页（F2/F3）。
 * 数据来源：AiQuestionService 的编排结果。
 * 注意：
 *   1. **`messageId` 不是 assistant 消息 id**（工作流 C2 第 3 条第 ⑧ 项，重点）：
 *      此时 assistant 消息还没生成，这里是**本次请求的追踪 id（UUID）**。
 *      assistant 的真实 id 在 SSE 的 `event: start` 里给，前端别拿这个当消息 id 落库。
 *   2. `conversationId` 用包装类 `Long`：JacksonConfig 会序列化成字符串（雪花 ID 精度问题）。
 *   3. `degraded=true` 只在 `ai_enabled=false` 时出现，此时另外三个字段都是 null：
 *      HTTP 仍是 200、`code` 仍是 200，**不报错**（工作流 C2 第 4 条），F4 据此切纯法条检索。
 *   4. `streamUrl` 固定带 `fromSeq=0`：客户端拿到后立刻订阅 SSE；重连时自己改成已收到的最大 seq。
 */
package com.faze.modules.ai.dto;

import lombok.Data;

/**
 * 提交问题的响应体。
 */
@Data
public class SubmitQuestionResponse {

    /** 会话 id（Long → JSON 字符串）；降级时为 null。 */
    private Long conversationId;

    /** 本次请求的追踪 id（UUID，**不是** assistant 消息 id）。 */
    private String messageId;

    /** SSE 订阅地址：`/api/v1/app/ai/stream?conversationId=<id>&fromSeq=0`。 */
    private String streamUrl;

    /** 是否降级（AI 开关关闭）：true 时其余字段为 null。 */
    private Boolean degraded;
}
