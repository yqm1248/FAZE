/*
 * 职责：Redis Pub/Sub 的容器装配——把敏感词重载订阅者注册到频道上。
 * 调用方：Spring 容器启动时自动加载。
 * 数据来源：Redis 连接工厂（业务库 db1，由 application-dev.yml 的 spring.data.redis 指定）。
 * 注意：
 *   1. **Spring Boot 不自动配置 RedisMessageListenerContainer**（它只配 RedisTemplate），
 *      所以想用 Pub/Sub 必须自己声明容器 Bean，否则订阅永远不会生效——这是「看起来配了、
 *      实际没订阅」的常见坑。
 *   2. 容器连不上 Redis 时**不阻断应用启动**（默认 recoveryInterval 会一直重试）：
 *      敏感词热更新是增强能力，Redis 挂了业务还得能跑（引擎用旧树）。
 *   3. 频道名从 SensitiveWordPublisher 取，保证「发布端」与「订阅端」用的是同一个常量，
 *      不允许两边各写一份字符串。
 */
package com.faze.config;

import com.faze.infra.sensitive.SensitiveWordPublisher;
import com.faze.modules.ops.listener.SensitiveWordReloadListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 订阅容器配置。
 */
@Configuration
public class RedisPubSubConfig {

    /**
     * 构造 Redis 消息监听容器，并订阅敏感词重载频道。
     *
     * @param connectionFactory 业务 Redis 连接工厂
     * @param listener          敏感词重载订阅者
     * @param publisher         发布端（只用它取频道名，保证两端一致）
     * @return 已注册订阅的容器
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            SensitiveWordReloadListener listener,
            SensitiveWordPublisher publisher) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(5000L);
        container.addMessageListener(listener, new ChannelTopic(publisher.channel()));
        return container;
    }
}
