/*
 * 职责：ai_conversation 表的实体映射（AI 会话，A 类业务实体）。
 * 调用方：modules/ai 下的 AiConversationMapper、C2 提交接口、G 组会话列表/删除。
 * 数据来源：Flyway V300__create_ai_tables.sql（7 字段）。
 * 注意：
 *   1. 用户「删除会话」只写 `deleted_at`（软删），**不删 ai_message**——AI 对话是合规留痕证据，
 *      与《AI对话留存策略》一致；后台 A-28 仍要能查到已软删的会话。
 *   2. `lang` 本段固定 `zh-Hans`（工作流 §零.3 第 4 项：不做多语言回答），
 *      字段留着是为了段 8 不迁移表结构，**不许**现在就从 Accept-Language 读它。
 *   3. `created_at` / `updated_at` 由应用层填 UTC，不依赖数据库 `CURRENT_TIMESTAMP`
 *      （《V1基线表结构》§5.1）；本实体不做自动填充，由 service 显式赋值。
 */
package com.faze.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 会话实体（对应 {@code ai_conversation}）。
 */
@Data
@TableName("ai_conversation")
public class AiConversation {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户，引用 {@code app_user.id}（不建物理外键）。 */
    private Long userId;

    /** 会话标题，取首问前 30 字（超出截断加「…」）。 */
    private String title;

    /** 会话语言；本段固定 {@code zh-Hans}。 */
    private String lang;

    /** 创建时间（UTC，应用层填）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC，应用层填）。 */
    private LocalDateTime updatedAt;

    /** 软删时间；非空表示用户已删除，C 端列表不显示，后台仍可查。 */
    private LocalDateTime deletedAt;
}
