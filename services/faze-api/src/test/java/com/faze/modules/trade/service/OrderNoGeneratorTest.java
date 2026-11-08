/*
 * 职责：OrderNoGenerator 的单测——并发不重复、格式正确、跨天重新计数。
 * 调用方：mvn test（@SpringBootTest，需要真实 Redis）。
 * 数据来源：本地 Redis（db1）+ 注入时钟。
 * 注意：
 *   1. 并发用例用 16 线程发 100 个号，断言 100 个互不重复。
 *   2. 跨天用例用**未来日期**（2099 年）的固定时钟，保证 key 是全新的，不污染当天序号。
 *   3. 跨天用例开头**必须清掉那两个 2099 年的 key**：否则用例只能跑一次，
 *      第二次执行时序号已被上一次累加（2026-11-06 实测：第二次跑必红）。
 */
package com.faze.modules.trade.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单号生成器测试。
 */
@SpringBootTest
@ActiveProfiles("dev")
class OrderNoGeneratorTest {

    private static final Pattern ORDER_NO = Pattern.compile("^OR\\d{14}$");
    private static final Pattern REFUND_NO = Pattern.compile("^RF\\d{14}$");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private OrderNoGenerator generator;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("格式：OR/RF + 8 位日期 + 6 位序号")
    void format() {
        assertTrue(ORDER_NO.matcher(generator.nextOrderNo()).matches());
        assertTrue(REFUND_NO.matcher(generator.nextRefundNo()).matches());
    }

    @Test
    @DisplayName("100 并发生成 100 个号，无重复")
    void concurrent100() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<String>> futures = new ArrayList<>(100);
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(generator::nextOrderNo));
            }
            Set<String> unique = new HashSet<>(100);
            for (Future<String> f : futures) {
                unique.add(f.get(10, TimeUnit.SECONDS));
            }
            assertEquals(100, unique.size(), "并发下出现重复单号：" + unique.size() + " 个唯一值");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("跨天序号从 000001 重新开始（用 2099 年的固定时钟）")
    void crossDay() {
        // **先清 key**：这个用例断言的是「全新一天的第一个号」。
        // 不清的话第二次执行时 Redis 里已有 seq=2，期望 000001 实际 000003 直接失败
        // （2026-11-06 实测过：该用例第一次跑绿、第二次必红，属于测试不自洁）。
        redis.delete("faze:order:seq:OR:20990101");
        redis.delete("faze:order:seq:OR:20990102");

        Clock day1 = Clock.fixed(Instant.parse("2099-01-01T02:00:00Z"), ZONE);
        Clock day2 = Clock.fixed(Instant.parse("2099-01-02T02:00:00Z"), ZONE);
        OrderNoGenerator g1 = new OrderNoGenerator(redis, day1);
        OrderNoGenerator g2 = new OrderNoGenerator(redis, day2);

        assertEquals("OR20990101000001", g1.nextOrderNo());
        assertEquals("OR20990101000002", g1.nextOrderNo());
        assertEquals("OR20990102000001", g2.nextOrderNo(), "跨天必须从 1 重新开始");
    }
}
