/*
 * 职责：dev profile 下的 Redis 连通性自检（第 1 段 A2 的验收载体）。
 * 调用方：Spring 启动流程（ApplicationRunner），只在 dev 生效，生产不注册。
 * 数据来源：Redis 逻辑库 spring.data.redis.database（第 1 段定为 1，RuoYi 用 0）。
 * 注意：
 *   1. 只写一个 TTL 60 秒的探针 key，过期自动消失，不在库里留垃圾数据；
 *   2. 不打印任何凭据，只打印库号与读写一致性；
 *   3. 这个类的唯一价值是「让启动日志证明 faze-api 真的连上了 db 1」，
 *      否则 Lettuce 懒连接下，配置写错也要等第一次业务调用才暴露。
 */
package com.faze.infra.redis;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * dev 环境的 Redis 自检。生产 profile 不注册本类。
 */
@Component
@Profile("dev")
public class RedisBootProbe implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedisBootProbe.class);

    /** 探针 key，带 TTL，60 秒后自动消失。 */
    private static final String PROBE_KEY = "faze:boot:probe";

    /** 自检 key 的存活时间。 */
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    /** 当前逻辑库号，仅用于日志断言，不参与业务。 */
    private final int database;

    /**
     * 构造自检组件。
     *
     * @param redis    字符串模板（spring-boot-starter-data-redis 自动配置）
     * @param database spring.data.redis.database 的值
     */
    public RedisBootProbe(StringRedisTemplate redis,
                          @Value("${spring.data.redis.database:0}") int database) {
        this.redis = redis;
        this.database = database;
    }

    /**
     * 写一个探针 key 再读回，证明连接与逻辑库隔离都生效。
     *
     * <p>失败时直接抛出：Redis 在段 1 是验证码、限流、令牌、支付密码锁定的硬依赖，
     * 连不上就不该让应用"看起来启动了"。</p>
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        String value = UUID.randomUUID().toString();
        redis.opsForValue().set(PROBE_KEY, value, TTL);
        String readBack = redis.opsForValue().get(PROBE_KEY);
        if (!value.equals(readBack)) {
            throw new IllegalStateException("Redis 自检失败：写入后读回不一致");
        }
        log.info("Redis 自检通过：database={}，key={}，TTL={}s", database, PROBE_KEY, TTL.toSeconds());
    }
}
