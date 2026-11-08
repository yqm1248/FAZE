/*
 * 职责：订阅敏感词重载频道，收到消息后触发本实例的词库重载。
 * 调用方：RedisPubSubConfig 把它注册到 RedisMessageListenerContainer。
 * 数据来源：Redis Pub/Sub 频道 `faze:sensitive:reload`（由 SensitiveWordPublisher 发布）。
 * 注意：
 *   1. **订阅回调线程不是 HTTP 线程**，所以在这里同步重载是安全的（不占请求线程）。
 *      但重载本身是整树构建，仍需 loader 的 synchronized 串行化。
 *   2. **重载失败只打 ERROR，不抛出去**：抛出去会让 Redis 客户端反复重投同一条消息，
 *      刷日志且无意义；旧树继续可用（引擎是原子替换，失败时旧树没被动过）。
 *   3. 本类放在 modules/ops 而不是 config：它依赖本域 loader（modules → infra 方向），
 *      放 config 会让「配置类」承担业务触发逻辑。
 */
package com.faze.modules.ops.listener;

import com.faze.modules.ops.service.SensitiveWordLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * 敏感词重载订阅者。
 */
@Slf4j
@Component
public class SensitiveWordReloadListener implements MessageListener {

    /** 词库加载器（重载入口）。 */
    private final SensitiveWordLoader loader;

    /**
     * 构造订阅者。
     *
     * @param loader 词库加载器
     */
    public SensitiveWordReloadListener(SensitiveWordLoader loader) {
        this.loader = loader;
    }

    /**
     * 收到重载消息。
     *
     * <p>副作用：整树重建并替换引擎引用；失败打 ERROR，继续用旧树。</p>
     *
     * @param message Redis 消息（本类不读消息体，只把它当作「该重载了」的信号）
     * @param pattern 订阅模式（未用）
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            loader.reload();
            log.info("敏感词库已按广播重载，通道消息体={}",
                    message.getBody() == null ? "" : new String(message.getBody()));
        } catch (Exception e) {
            log.error("敏感词库重载失败，继续使用旧树", e);
        }
    }
}
