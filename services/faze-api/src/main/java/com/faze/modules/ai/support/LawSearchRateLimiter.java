/*
 * 职责：`GET /api/v1/pub/law-items` 的每 IP 限流（B2）——最小 Redis 计数实现，不引入第三方限流组件。
 * 调用方：modules/ai/controller/LawItemPublicController（每个请求调一次 check）。
 * 数据来源：Redis（StringRedisTemplate）；阈值来自 sys_config.law_search_rate_per_min（缺省 60）。
 * 注意：
 *   1. key 形如 `faze:law:search:rate:<ip>:<yyyyMMddHHmm>`（按分钟窗口），**INCR + EXPIRE 120s**：
 *      TTL 比窗口多 60s，保证跨分钟瞬间不会因为 key 提前过期而放宽。
 *   2. **不加登录校验，所以必须限流**（B2 第 4 条）：公开法条接口既服务未登录用户，
 *      也承担 AI 关闭后的降级检索，不限流就是一个免费的全表 LIKE 入口。
 *   3. 超限抛 `BusinessException(COMMON_RATE_LIMITED)`（401007），由全局异常处理器统一出 R。
 *   4. Redis 不可用时**放行并记 warn**（fail-open）：法条检索是降级通道，宁可短时限流失效，
 *      也不能让「AI 关闭 + Redis 抖动」时用户连法条都查不到。这一点在验收文档里写明。
 *   5. 不做分布式锁：限流只需要近似计数，INCR 本身是原子的；EXPIRE 与 INCR 之间的
 *      极小概率「有计数无 TTL」用「TTL<0 时补 EXPIRE」兜住。
 */
package com.faze.modules.ai.support;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 法条检索限流器（每 IP 每分钟）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LawSearchRateLimiter {

    /** 阈值配置键（sys_config）。 */
    public static final String CONFIG_KEY = "law_search_rate_per_min";

    /** 缺省阈值：每 IP 每分钟 60 次（B2 第 4 条）。 */
    public static final int DEFAULT_PER_MINUTE = 60;

    /** Redis key 前缀。 */
    public static final String KEY_PREFIX = "faze:law:search:rate:";

    /** 计数窗口 key 的 TTL：60s 窗口 + 60s 缓冲。 */
    private static final Duration KEY_TTL = Duration.ofSeconds(120);

    /** 分钟 key 的时间格式。 */
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final StringRedisTemplate redis;
    private final SysConfigQueryService sysConfigQueryService;

    /**
     * 检查并累加当前 IP 的本分钟请求数。
     *
     * <p>副作用：INCR 一个 Redis key，必要时设置 120s TTL。超限抛 401007。</p>
     *
     * @param ip 客户端 IP（Controller 已用 RequestUtils.clientIp 解析；可空，空记为 unknown）
     * @throws BusinessException 401007 超过每分钟阈值
     */
    public void check(String ip) {
        String safeIp = StringUtils.hasText(ip) ? ip.trim() : "unknown";
        int limit = Math.max(1, sysConfigQueryService.getInt(CONFIG_KEY, DEFAULT_PER_MINUTE));
        String minute = LocalDateTime.now().format(MINUTE_FORMAT);
        String key = KEY_PREFIX + safeIp + ":" + minute;
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
                log.warn("法条检索触发限流 ip={} count={} limit={}", safeIp, count, limit);
                throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // fail-open：降级通道不能因为 Redis 抖动整条不可用
            log.warn("法条检索限流不可用（Redis），本次放行 原因={}", e.getClass().getSimpleName());
        }
    }
}
