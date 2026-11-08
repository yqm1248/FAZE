/*
 * 职责：敏感词热更新的发布端——词库变更后广播「需要重载」。
 * 调用方：modules/ops 的 SensitiveWordService（新增/修改/删除/导入后，事务提交时）。
 * 数据来源：Redis Pub/Sub 频道（默认 `faze:sensitive:reload`，可在 V703 的
 *           sys_config.sensitive.reload_pubsub_channel 里改；配置项只读不改）。
 * 注意：
 *   1. **必须在事务提交后发布**（B1 第 1 条）：在事务内发，订阅方可能读到旧数据并覆盖
 *      新树，表现为「改了词但没生效」。调用方用 TransactionSynchronization 的 afterCommit。
 *   2. 消息体只放「变更时间戳」，不放词库内容：订阅方收到后自己查库，
 *      避免大词库在频道里传输、也避免「消息里的副本」与库不一致。
 *   3. **发布失败不抛异常**：Redis 抖动导致广播失败时，打 WARN 即可——本机的
 *      管理端保存逻辑会另外直接调用一次本地 reload（见 SensitiveWordService），
 *      多实例场景下的其余实例最多晚一次请求才同步。
 */
package com.faze.infra.sensitive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 敏感词重载广播发布端。
 */
@Slf4j
@Component
public class SensitiveWordPublisher {

    /** Redis 客户端（业务库 db1）。 */
    private final StringRedisTemplate redis;

    /** 广播频道名。 */
    private final String channel;

    /**
     * 构造发布端。
     *
     * @param redis   Redis 模板
     * @param channel 频道名（默认 faze:sensitive:reload）
     */
    public SensitiveWordPublisher(StringRedisTemplate redis,
                                  @Value("${faze.sensitive.reload-channel:faze:sensitive:reload}") String channel) {
        this.redis = redis;
        this.channel = channel;
    }

    /**
     * 广播一次「词库已变，请重载」。
     *
     * <p>副作用：向 Redis 频道发布一条消息。失败只打 WARN，不阻断业务。</p>
     */
    public void publishReload() {
        try {
            redis.convertAndSend(channel, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("敏感词重载广播失败（不影响本机已生效的改动） channel={}", channel, e);
        }
    }

    /**
     * 当前频道名。
     *
     * @return 频道名
     */
    public String channel() {
        return channel;
    }
}
