/*
 * 职责：充值下单的**用户维度**频次限流（第 6 段 B3 第 3 条）——同一用户每分钟最多 N 次下单。
 * 调用方：modules/trade/service/RechargeOrderService#create（金额校验之后、任何 I/O 之前）。
 * 数据来源：Redis（StringRedisTemplate，固定窗口计数）+ sys_config 的 `recharge.rate_limit_per_min`。
 * 注意：
 *   1. **为什么不引第三方限流组件**：规则只有一条，固定窗口计数用 INCR + TTL 就是十几行；
 *      引 Sentinel/Resilience4j 要多一套配置面和故障面，而本段只需要「第 4 次拒掉」。
 *      实现口径照抄项目已有的 `modules/ai/support/LawSearchRateLimiter`（同款 Redis 计数）。
 *   2. **超限抛 `RateLimitedException`（HTTP 429 + `Retry-After` 头），不用 401007**：
 *      负责人 2026-11-08 明确要求按工作流 B3 第 3 条原文的「超限返回 429」执行。
 *      不复用 `COMMON_RATE_LIMITED(401007)` 有两个原因：① 401007 被短信、登录锁定、
 *      法条检索、支付密码锁定共用，充值混进去会让值班无法区分告警来源；② 401007 的
 *      HTTP 状态是 200，与 429 是两条客户端处理路径。冲突与取舍登记在工作流 §十一 第 35 条。
 *   3. **fail-open**：Redis 不可用时放行并记 warn。理由与法条检索限流不同——充值有
 *      「单日金额/笔数限额 + 幂等键」三道硬约束兜底，而 Redis 抖动时把所有人挡在充值页外
 *      是更大的可用性事故。真正防重复入账的是 C4 的数据库唯一键 + CAS，不靠这里。
 *   4. **窗口按东八区分分钟**：与 `recharge.*` 其它时间口径一致（`RechargeQuotaPolicy` 第 4 条）。
 *      跨分钟瞬间不会漏计：TTL 比窗口多 60 秒。
 *   5. 不按 IP 限流：充值必须登录，IP 维度（含运营商 NAT）会误伤同一出口的正常用户。
 */
package com.faze.modules.trade.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.exception.RateLimitedException;
import com.faze.config.SysConfigKeys;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 充值下单用户维度限流器。
 */
@Component
@Slf4j
public class RechargeRateLimitGuard {

    /** 缺省阈值：每分钟 3 次（B3 第 3 条示例值；配置读不到时用它）。 */
    public static final int DEFAULT_PER_MINUTE = 3;

    /** Redis key 前缀。 */
    public static final String KEY_PREFIX = "faze:trade:recharge:rate:";

    /** 计数窗口 key 的 TTL：60 秒窗口 + 60 秒缓冲。 */
    private static final Duration KEY_TTL = Duration.ofSeconds(120);

    /** 分钟 key 的时间格式。 */
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 业务时区：东八区（与 recharge.* 其它时间口径一致）。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final StringRedisTemplate redis;

    private final SysConfigQueryService sysConfig;

    private final Clock clock;

    /**
     * Spring 注入用的构造器（系统时钟，东八区）。
     *
     * @param redis     Redis 模板
     * @param sysConfig 平台参数只读查询（读阈值）
     */
    @Autowired
    public RechargeRateLimitGuard(StringRedisTemplate redis, SysConfigQueryService sysConfig) {
        this(redis, sysConfig, Clock.system(BIZ_ZONE));
    }

    /**
     * 可注入时钟的构造器，仅供单测固定「当前是哪一分钟」。
     *
     * @param redis     Redis 模板
     * @param sysConfig 平台参数只读查询
     * @param clock     时钟（时区需为东八区）
     */
    RechargeRateLimitGuard(StringRedisTemplate redis, SysConfigQueryService sysConfig, Clock clock) {
        this.redis = redis;
        this.sysConfig = sysConfig;
        this.clock = clock;
    }

    /**
     * 检查并累加当前用户本分钟的下单次数。
     *
     * <p>副作用：INCR 一个 Redis key，必要时设置 120s TTL。超限抛 429。</p>
     *
     * @param userId 下单用户（调用方已保证非空，见 RechargeOrderService#create 第 99 行）
     * @throws RateLimitedException 405030 / HTTP 429 超过每分钟下单次数（带 `Retry-After`）
     */
    public void check(Long userId) {
        if (userId == null) {
            return;
        }
        int limit = Math.max(1, sysConfig.getInt(SysConfigKeys.RECHARGE_RATE_LIMIT_PER_MIN,
                DEFAULT_PER_MINUTE));
        String minute = MINUTE_FORMAT.format(clock.instant().atZone(BIZ_ZONE));
        String key = KEY_PREFIX + userId + ":" + minute;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return;
            }
            if (count == 1L) {
                redis.expire(key, KEY_TTL);
            } else {
                Long ttl = redis.getExpire(key);
                if (ttl == null || ttl < 0) {
                    redis.expire(key, KEY_TTL);
                }
            }
            if (count > limit) {
                long retryAfter = secondsUntilNextMinute();
                log.warn("充值下单触发用户维度限流 userId={} count={} limit={} retryAfter={}s",
                        userId, count, limit, retryAfter);
                throw new RateLimitedException(ErrorCode.TRADE_RECHARGE_RATE_LIMITED, retryAfter);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // fail-open：限流是防连点，不是资金安全边界（资金由限额 + 幂等 + 唯一键兜底）
            log.warn("充值限流不可用（Redis），本次放行 userId={} 原因={}",
                    userId, e.getClass().getSimpleName());
        }
    }

    /**
     * 距下一分钟（窗口边界）还有多少秒，用于 `Retry-After` 头。
     *
     * @return 秒数，最小 1
     */
    private long secondsUntilNextMinute() {
        int second = clock.instant().atZone(BIZ_ZONE).getSecond();
        return Math.max(1L, 60L - second);
    }
}
