/*
 * 职责：OrderIdempotencyGuard 的单测——下单幂等占位的四种命中/拒绝分支。
 * 调用方：mvn test（纯 Mockito，不连 Redis）。
 * 数据来源：Mock 的 StringRedisTemplate / ValueOperations。
 * 注意：
 *   1. 核心断言是 `2-A8` ②：「同一 key 第二次进来返回同一个订单 id，不会走到创建」。
 *   2. `creating`（上一个请求还在跑）必须抛 405004，不能让第二个请求也去建单。
 *   3. 非法 key（超长 / 含非法字符）**按没传处理**，且不许碰 Redis——
 *      把任意字符串拼进 key 会污染 Redis key 空间。
 */
package com.faze.modules.trade.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单幂等占位器测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderIdempotencyGuardTest {

    private static final Long USER_ID = 1001L;
    private static final String CLIENT_KEY = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String REDIS_KEY = "faze:order:idem:1001:" + CLIENT_KEY;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private OrderIdempotencyGuard guard;

    @Test
    @DisplayName("没传幂等键：直接放行，不碰 Redis")
    void noKeyPassThrough() {
        Optional<Long> result = guard.tryStart(USER_ID, null);

        assertTrue(result.isEmpty());
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("首次请求：抢锁成功，返回空（表示可以去创建）")
    void firstRequestAcquiresLock() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(REDIS_KEY), eq("creating"), any(Duration.class))).thenReturn(true);

        assertTrue(guard.tryStart(USER_ID, CLIENT_KEY).isEmpty());
        verify(valueOps).setIfAbsent(eq(REDIS_KEY), eq("creating"), any(Duration.class));
    }

    @Test
    @DisplayName("重复请求：返回已存在的订单 id（2-A8 ②）")
    void secondRequestReturnsSameOrder() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(REDIS_KEY)).thenReturn("3003");

        Optional<Long> result = guard.tryStart(USER_ID, CLIENT_KEY);

        assertTrue(result.isPresent());
        assertEquals(3003L, result.get());
    }

    @Test
    @DisplayName("上一个请求仍在执行（creating）：抛 405004")
    void creatingConflict() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(REDIS_KEY)).thenReturn("creating");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.tryStart(USER_ID, CLIENT_KEY));
        assertEquals(ErrorCode.TRADE_DUPLICATE_SUBMIT, ex.getErrorCode());
    }

    @Test
    @DisplayName("非法幂等键（含空格/中文）：按没传处理，不碰 Redis")
    void illegalKeyIgnored() {
        assertTrue(guard.tryStart(USER_ID, "含中文的键").isEmpty());
        assertTrue(guard.tryStart(USER_ID, "has space").isEmpty());
        assertTrue(guard.tryStart(USER_ID, "a".repeat(65)).isEmpty());
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("bind：把幂等键覆盖成订单 id（24 小时）")
    void bindOrderId() {
        when(redis.opsForValue()).thenReturn(valueOps);

        guard.bind(USER_ID, CLIENT_KEY, 3003L);

        verify(valueOps).set(eq(REDIS_KEY), eq("3003"), any(Duration.class));
    }

    @Test
    @DisplayName("release：删除占位，让用户能立刻重试")
    void releaseOnFailure() {
        guard.release(USER_ID, CLIENT_KEY);

        verify(redis).delete(REDIS_KEY);
    }

    @Test
    @DisplayName("没传 key 时 bind/release 都是空操作")
    void binderNoopWithoutKey() {
        guard.bind(USER_ID, null, 3003L);
        guard.release(USER_ID, null);

        verify(redis, never()).delete(anyString());
    }
}
