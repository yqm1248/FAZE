/*
 * 职责：ai_message 表的实体映射（AI 消息，**C 类状态机表**，16 字段）。
 * 调用方：modules/ai 下的 AiMessageMapper、C2/C3 流式落库、A-28 后台检索、E2 内容编号。
 * 数据来源：Flyway V300__create_ai_tables.sql。
 * 注意：
 *   1. `content` 是 **L4 级敏感字段**（《数据表规范》§9.2）：禁止进日志、错误上报、APM。
 *      日志里只允许出现 id / conversationId / token 数 / 耗时。
 *   2. `role` 只有 `user` / `assistant`，**不存 `system`**——系统 Prompt 不落库（D1 已定）。
 *   3. `refs` / `export_urls` 是 MySQL JSON 列，这里映射成 **String（JSON 原文）**：
 *      由 service 用 Jackson 序列化后写入、读出后反序列化。**不映射成 List** 的原因：
 *      MyBatis-Plus 默认不带 JSON TypeHandler，映射成 List 会在 insert/update 时报类型错；
 *      而自定义 TypeHandler 又要在全局注册，容易与段 1/段 2 的实体互相影响。
 *      空引用的语义是 **`[]` 而不是 NULL**（[] = 查过没引用，NULL = 没查过），见 D4。
 *   4. `content_id` 有唯一索引 `uk_ai_message_content_id`，只有 assistant 消息有值（E2）。
 *   5. C 类表必须有 `updated_at`（工作流 §零.3 第 12 项），流式过程中会被多次 UPDATE。
 *   6. `is_safety_passed` 本段恒为 1、不驱动业务（段 3 不接机审，《零之二》第 4 项）。
 */
package com.faze.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 消息实体（对应 {@code ai_message}）。
 */
@Data
@TableName("ai_message")
public class AiMessage {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话，引用 {@code ai_conversation.id}。 */
    private Long conversationId;

    /** 归属用户（冗余一份，后台按用户检索少一次 join）。 */
    private Long userId;

    /** {@code user} / {@code assistant}；不存 {@code system}。 */
    private String role;

    /** 消息正文（MEDIUMTEXT，**L4 敏感**，禁止进日志）。 */
    private String content;

    /** 模型 id（含上下文后缀），如 {@code doubao-seed-1.6-32k}；user 消息为 NULL。 */
    private String model;

    /** 上游返回的输入 token。 */
    private Integer promptTokens;

    /** 上游返回的输出 token。 */
    private Integer completeTokens;

    /** 法条引用快照（JSON 数组，schema 见 D4）；空引用是 {@code []} 不是 NULL。 */
    private String refs;

    /** 消息语言；本段固定 {@code zh-Hans}。 */
    private String lang;

    /** 是否通过安全检查；本段恒为 1，不驱动业务。 */
    private Integer isSafetyPassed;

    /** 内容编号（隐式标识，{@code AI-} + 32 位十六进制），仅 assistant 消息有值，唯一。 */
    private String contentId;

    /** 是否被导出过（A-28 单条导出时置 1）。 */
    private Integer isExported;

    /** 导出产物列表（JSON 数组，元素 {type,url,at}）。 */
    private String exportUrls;

    /** 创建时间（UTC，应用层填）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC，流式过程中多次刷新）。 */
    private LocalDateTime updatedAt;
}
