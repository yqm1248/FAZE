/*
 * 职责：C2 与 C3 之间的「待流式回答的提问载荷」——C2 组装好后存 Redis，C3 订阅 SSE 时取出。
 * 调用方：modules/ai/service/AiQuestionService（写入 Redis）、AiStreamService（读出后串起上游调用）。
 * 数据来源：C2 的规则链路（D1 组装的 messages + B1 的 hits + 配额日期 + 模型 id）。
 * 注意：
 *   1. **为什么要经 Redis 中转**：C2（POST 提交）与 C3（GET SSE）是两个 HTTP 请求，
 *      中间没有会话状态可放。放内存 Map 在多实例部署下会丢失；放 Redis 才可续。
 *   2. **只放 10 分钟**（`AiRedisKeys.TTL_SECONDS`）：用户提交后不订阅流，载荷自动过期，
 *      不会有「旧提问被新流误用」的长期残留。
 *   3. `messages` 已按上游格式排好（system → 历史 → user），C3 直接塞进 ChatRequest，
 *      **不要再拼一次**（重复拼会把系统指令拼两遍）。
 *   4. `quotaDay` 是预扣当天的 yyyyMMdd：C3 首字节超时/上游失败回退配额时必须用它，
 *      不能用「今天」——跨日边界会回退到错误的 key。
 *   5. 本类会 JSON 序列化进 Redis，字段必须保持可 Jackson 3 反序列化（无参构造 + setter）。
 *      其中 `ChatMessage` / `LawHit` 都是项目内可序列化 POJO，别改成接口/抽象类。
 */
package com.faze.modules.ai.dto;

import com.faze.infra.ai.dto.ChatMessage;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待流式回答的提问载荷。
 */
@Data
@NoArgsConstructor
public class PendingAsk {

    /** 本次请求的追踪 id（C2 响应里的 messageId，UUID）。 */
    private String requestId;

    /** 会话 id。 */
    private Long conversationId;

    /** 提问用户 id（C3 归属校验与配额回退用）。 */
    private Long userId;

    /** 模型 id（含上下文后缀）；C3 写 ai_message.model。 */
    private String model;

    /** 上游消息列表：system → 历史（升序）→ 本次 user。 */
    private List<ChatMessage> messages;

    /** B1 检索命中（C3 交给 D3/D4 用）。 */
    private List<LawHit> hits;

    /** 预扣配额所在的日期（yyyyMMdd，Asia/Shanghai）。 */
    private String quotaDay;
}
