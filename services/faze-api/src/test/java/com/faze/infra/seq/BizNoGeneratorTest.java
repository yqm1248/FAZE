/*
 * 职责：BizNoGenerator 的单测——格式、并发唯一性、Redis 降级路径。
 * 调用方：mvn test（surefire）。
 * 数据来源：《第 5 段工作流》§三 A3 的「算做完」三条判据。
 * 注意：
 *   1. 本测试**不连 Redis**：用 Mockito 模拟 INCR（AtomicLong 自增），断网场景用抛异常模拟。
 *   2. 并发用例用 8 线程 × 1250 次 = 10000 个单号，断言 HashSet 大小 = 10000。
 *   3. 降级路径断言的是「仍然生成了合法 16 位单号」，不是「一定不重复」——
 *      降级靠唯一索引兜底，这一点写在工作流里，不在本测试的能力范围。
 */
package com.faze.infra.seq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 业务单号生成器单测。
 */
class BizNoGeneratorTest {

    /** 东八区（与生成器内部一致）。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 5 种前缀都生成合法 16 位单号，且前缀正确。
     */
    @Test
    @DisplayName("5 种前缀格式合法")
    void fivePrefixes() {
        BizNoGenerator gen = newGenerator(new AtomicLong());
        assertThat(gen.nextCaseNo()).matches("^CS\\d{14}$");
        assertThat(gen.nextWithdrawNo()).matches("^WD\\d{14}$");
        assertThat(gen.nextSettlementNo()).matches("^JS\\d{14}$");
        assertThat(gen.nextDeliveryNo()).matches("^SD\\d{14}$");
        assertThat(gen.nextCaseLibraryNo()).matches("^CL\\d{14}$");
        assertThat(BizNoGenerator.isValid(gen.nextCaseNo())).isTrue();
        assertThat(BizNoGenerator.isValid("CS2026")).isFalse();
        assertThat(BizNoGenerator.isValid(null)).isFalse();
    }

    /**
     * 10000 个单号无重复（并发）。
     *
     * @throws Exception 线程池等待异常
     */
    @Test
    @DisplayName("并发 10000 个单号无重复")
    void concurrentUnique() throws Exception {
        BizNoGenerator gen = newGenerator(new AtomicLong());
        int threads = 8;
        int perThread = 1250;
        Set<String> all = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            all.add(gen.nextCaseNo());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(all).hasSize(threads * perThread);
        assertThat(all).allMatch(BizNoGenerator::isValid);
    }

    /**
     * Redis 抛异常时降级：仍返回合法单号（不阻断业务）。
     */
    @Test
    @DisplayName("Redis 不可用时降级仍生成合法单号")
    void fallbackWhenRedisDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new IllegalStateException("connection refused"));

        BizNoGenerator gen = new BizNoGenerator(redis, Clock.system(BIZ_ZONE));
        String no = gen.nextWithdrawNo();
        assertThat(no).matches("^WD\\d{14}$");
        assertThat(BizNoGenerator.isValid(no)).isTrue();
    }

    /**
     * 跨天时日期段变化（用可注入时钟验证，不依赖真实时间）。
     */
    @Test
    @DisplayName("跨天日期段跟随业务时区")
    void dayFollowsBizZone() {
        AtomicLong seq = new AtomicLong();
        Clock day1 = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), BIZ_ZONE);
        Clock day2 = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), BIZ_ZONE);
        BizNoGenerator gen1 = newGenerator(seq, day1);
        BizNoGenerator gen2 = newGenerator(seq, day2);
        assertThat(gen1.nextCaseNo()).startsWith("CS20261108");
        assertThat(gen2.nextCaseNo()).startsWith("CS20261109");
    }

    /**
     * 当日号段用尽（999999 之后）抛 501001。
     */
    @Test
    @DisplayName("号段用尽抛 501001")
    void seqExhausted() {
        AtomicLong counter = new AtomicLong(999_999L);
        BizNoGenerator gen = newGenerator(counter);
        assertThatThrownBy(gen::nextCaseNo)
                .isInstanceOfSatisfying(com.faze.common.exception.BusinessException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo(501001);
                    assertThat(e.getDetail()).contains("用尽");
                });
    }

    /**
     * 构造一个用 AtomicLong 模拟 Redis INCR 的生成器。
     *
     * @param counter 计数器
     * @return 生成器
     */
    private static BizNoGenerator newGenerator(AtomicLong counter) {
        return newGenerator(counter, Clock.system(BIZ_ZONE));
    }

    /**
     * 构造指定时钟的生成器。
     *
     * @param counter 计数器
     * @param clock   时钟
     * @return 生成器
     */
    private static BizNoGenerator newGenerator(AtomicLong counter, Clock clock) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenAnswer(inv -> counter.incrementAndGet());
        return new BizNoGenerator(redis, clock);
    }
}
