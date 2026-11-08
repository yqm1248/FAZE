/*
 * 职责：B3 用户维度限流单测——未超限放行、第 4 次拒绝（401007）、Redis 故障时 fail-open、阈值可配。
 * 调用方：mvn test。
 * 数据来源：StringRedisTemplate 与 SysConfigQueryService 全部 Mockito 替换；时钟固定到某一分钟。
 * 注意：
 *   1. 断言的是**抛不抛 401007**，不是 Redis 调用次数：限流的关键行为是「第 N+1 次被拒」，
 *      INCR 的细节属于实现。
 *   2. fail-open 必须有用例：Redis 挂了把所有人挡在充值页外，比「短暂失去防连点」严重得多
 *      （类注释第 3 条）。
 *   3. 阈值读配置必须有用例：写死 3 的实现在这里有配置为 5 的用例时会放过第 4 次，
 *      测试会红——防止后人把 sys_config 那层删掉。
 */
package com.faze.modules.trade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.faze.common.exception.ErrorCode;
import com.faze.common.exception.RateLimitedException;
import com.faze.config.SysConfigKeys;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 充值下单限流单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RechargeRateLimitGuardTest {

    private static final Long USER_ID = 7011L;

    /** 固定时刻：东八区 2026-11-08 22:30（对应 minute key 202611082230）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-11-08T14:30:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> ops;

    @Mock
    private SysConfigQueryService sysConfig;

    private RechargeRateLimitGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RechargeRateLimitGuard(redis, sysConfig, FIXED_CLOCK);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.getExpire(anyString())).thenReturn(100L);
    }

    @Test
    @DisplayName("默认阈值 3：前 3 次放行")
    void allowsFirstThree() {
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_RATE_LIMIT_PER_MIN,
                RechargeRateLimitGuard.DEFAULT_PER_MINUTE)).thenReturn(3);
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);

        assertThatCode(() -> {
            guard.check(USER_ID);
            guard.check(USER_ID);
            guard.check(USER_ID);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("默认阈值 3：第 4 次抛 401007（B3「算做完」的判据）")
    void rejectsFourth() {
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_RATE_LIMIT_PER_MIN,
                RechargeRateLimitGuard.DEFAULT_PER_MINUTE)).thenReturn(3);
        when(ops.increment(anyString())).thenReturn(4L);

        assertThatThrownBy(() -> guard.check(USER_ID))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(e -> {
                    RateLimitedException rle = (RateLimitedException) e;
                    // 专用码 405030（不是被短信/登录/法条检索复用的 401007）
                    assertThat(rle.getErrorCode()).isEqualTo(ErrorCode.TRADE_RECHARGE_RATE_LIMITED);
                    // HTTP 429，不是业务限流的 200
                    assertThat(rle.getErrorCode().getHttpStatus()).isEqualTo(429);
                    // 固定时钟 22:30:00 → 距下一分钟 60 秒
                    assertThat(rle.getRetryAfterSeconds()).isEqualTo(60L);
                });
    }

    @Test
    @DisplayName("阈值可配：配置成 5 时第 4 次不拦")
    void usesConfiguredLimit() {
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_RATE_LIMIT_PER_MIN,
                RechargeRateLimitGuard.DEFAULT_PER_MINUTE)).thenReturn(5);
        when(ops.increment(anyString())).thenReturn(4L);

        assertThatCode(() -> guard.check(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 故障时 fail-open：放行，不把用户挡在充值页外")
    void failOpenWhenRedisDown() {
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_RATE_LIMIT_PER_MIN,
                RechargeRateLimitGuard.DEFAULT_PER_MINUTE)).thenReturn(3);
        when(ops.increment(anyString())).thenThrow(new RuntimeException("Redis 连接超时"));

        assertThatCode(() -> guard.check(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("首次数（INCR=1）时设置 120s TTL，避免窗口 key 永不过期")
    void setsTtlOnFirstHit() {
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_RATE_LIMIT_PER_MIN,
                RechargeRateLimitGuard.DEFAULT_PER_MINUTE)).thenReturn(3);
        when(ops.increment(anyString())).thenReturn(1L);

        guard.check(USER_ID);

        org.mockito.Mockito.verify(redis).expire(eq(
                RechargeRateLimitGuard.KEY_PREFIX + USER_ID + ":202611082230"), any(java.time.Duration.class));
    }
}
