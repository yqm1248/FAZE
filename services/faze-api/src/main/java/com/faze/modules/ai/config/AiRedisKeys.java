/*
 * 职责：AI 域用到的 Redis key 与 TTL 的**唯一来源**（禁止在 service 里手拼字符串）。
 * 调用方：modules/ai/service/{AiQuestionService,AiStreamService,AiQuotaService}。
 * 数据来源：Redis 逻辑库 1（业务缓存库；RuoYi 用 0、探针用 14，见 deploy/.env 与 application-dev.yml），
 *           key 统一前缀 `faze:`（与 `faze.redis.key-prefix` 的约定一致）。
 * 注意：
 *   1. **前缀必须是 `faze:`**：多环境共用同一 Redis 时靠它隔离；改前缀等于线上缓存/配额全部丢失。
 *   2. **流 delta 的 key 用 assistantMessageId**（不是 conversationId）：断线重连时先查
 *      ACTIVE 拿到 messageId，再读 STREAM；用 conversationId 会让「同一会话两次提问」互相踩。
 *   3. **TTL 600 秒 = 10 分钟**（§零.3 第 13 项）。过期后重连只能回「回答已过期」，不许假装续传。
 *   4. 配额 key 的日期段由 `AiQuotaService` 按 **Asia/Shanghai** 算，本类只拼前缀，不取系统时区。
 */
package com.faze.modules.ai.config;

/**
 * AI 域 Redis key 常量。
 */
public final class AiRedisKeys {

    /** 统一业务前缀（与 dev 的 `faze.redis.key-prefix` 一致）。 */
    public static final String PREFIX = "faze:";

    /** 流式 delta 暂存：`faze:ai:stream:<assistantMessageId>`，元素格式 `<seq>|<content>`。 */
    public static final String STREAM = PREFIX + "ai:stream:";

    /** 当前活跃流映射：`faze:ai:active:<conversationId>`，值为 assistantMessageId。 */
    public static final String ACTIVE = PREFIX + "ai:active:";

    /** 待流式回答的提问载荷：`faze:ai:ask:<conversationId>`，值为 PendingAsk 的 JSON。 */
    public static final String ASK = PREFIX + "ai:ask:";

    /** 每日配额：`faze:ai:quota:<userId>:<yyyyMMdd>`（Asia/Shanghai）。 */
    public static final String QUOTA = PREFIX + "ai:quota:";

    /** delta / active / ask 的 TTL（秒）：10 分钟。 */
    public static final long TTL_SECONDS = 600L;

    /**
     * 私有构造，禁止实例化。
     */
    private AiRedisKeys() {
    }

    /**
     * 拼流 delta key。
     *
     * @param assistantMessageId 助手消息 id
     * @return 完整 key
     */
    public static String stream(Long assistantMessageId) {
        return STREAM + assistantMessageId;
    }

    /**
     * 拼活跃流映射 key。
     *
     * @param conversationId 会话 id
     * @return 完整 key
     */
    public static String active(Long conversationId) {
        return ACTIVE + conversationId;
    }

    /**
     * 拼待答载荷 key。
     *
     * @param conversationId 会话 id
     * @return 完整 key
     */
    public static String ask(Long conversationId) {
        return ASK + conversationId;
    }

    /**
     * 拼配额 key。
     *
     * @param userId 用户 id
     * @param day    Asia/Shanghai 的 yyyyMMdd
     * @return 完整 key
     */
    public static String quota(Long userId, String day) {
        return QUOTA + userId + ":" + day;
    }
}
