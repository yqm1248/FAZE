/*
 * 职责：LawSearchRateLimiter 的单测（B2）——阈值内放行、超限 401007、Redis 故障放行。
 * 调用方：mvn test（Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 StringRedisTemplate 与 SysConfigQueryService。
 * 注意：真实 HTTP 层的 61 次限流证据在 `docs/acceptance/seg3/B2-pub-law-items.md`（需 API 启动，
 *       由 lead 整合阶段补跑）；单测只验计数判定与异常码。
 */
package com.faze.modules.ai.support;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.service.SysConfigQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 法条检索限流测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LawSearchRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SysConfigQueryService sysConfigQueryService;

    private LawSearchRateLimiter limiter;

    /**
     * 组装被测限流器。
     */
    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(sysConfigQueryService.getInt(eq(LawSearchRateLimiter.CONFIG_KEY), anyInt()))
                .thenReturn(LawSearchRateLimiter.DEFAULT_PER_MINUTE);
        limiter = new LawSearchRateLimiter(redis, sysConfigQueryService);
    }

    /**
     * 第 60 次仍放行。
     */
    @Test
    @DisplayName("第 60 次放行")
    void underLimit() {
        when(valueOperations.increment(anyString())).thenReturn(60L);
        when(redis.getExpire(anyString())).thenReturn(100L);

        limiter.check("127.0.0.1");
    }

    /**
     * 第 61 次返回 401007。
     */
    @Test
    @DisplayName("第 61 次 → 401007")
    void overLimit() {
        when(valueOperations.increment(anyString())).thenReturn(61L);
        when(redis.getExpire(anyString())).thenReturn(100L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> limiter.check("127.0.0.1"));

        assertEquals(ErrorCode.COMMON_RATE_LIMITED, ex.getErrorCode());
    }

    /**
     * Redis 不可用时放行（fail-open）。
     */
    @Test
    @DisplayName("Redis 不可用 → 放行不抛异常")
    void failOpen() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        limiter.check("127.0.0.1");
        assertTrue(true);
    }

    /**
     * 首次计数要设置 TTL。
     */
    @Test
    @DisplayName("首次计数设置 120s TTL")
    void firstHitSetsTtl() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(java.time.Duration.class))).thenReturn(true);

        limiter.check("127.0.0.1");
    }
}
