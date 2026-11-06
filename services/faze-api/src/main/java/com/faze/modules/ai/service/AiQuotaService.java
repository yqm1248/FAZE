/*
 * 职责：C5 每日配额——预扣、回退、查询。`3-A8`「断线不重复计费」的落地靠它。
 * 调用方：modules/ai/service/AiQuestionService（C2 第 ② 步预扣、拦截/异常时回退）、
 *         modules/ai/service/AiStreamService（C3 首字节超时 / 上游失败且未出字时回退）、
 *         modules/ai/controller/AiQuotaController（`GET /app/ai/quota`）。
 * 数据来源：Redis `faze:ai:quota:<userId>:<yyyyMMdd>`（逻辑库 1，前缀 `faze:`）。
 *          日期**固定按 Asia/Shanghai 算**，不跟机器时区（工作流 C5 卡住项第 2 条）。
 *          上限来自 `sys_config.ai_daily_quota`（AiProperties）。
 * 注意：
 *   1. **判定与回退必须在一次 Lua 脚本里完成**（工作流 C5 卡住项第 1 条）：
 *      「先 INCR 再判断再 DECR」三步不是原子的，多实例并发时会把别人的名额回退掉。
 *      本类的 RESERVE_SCRIPT 是唯一入口，**禁止在业务代码里再写单独的 INCR/DECR 判定**。
 *   2. **预扣语义**：C2 提交时先占 1 次；正常 `done` 保留；首字节超时 / 上游失败 / 生成开始前中断
 *      调 `refund`。**已经开始出字的失败照扣**（用户已得到部分内容，见工作流 C5 第 3 条）。
 *   3. **`3-A8` 口径**：同一条 `ai_message` 从开始到结束只扣 1 次。
 *      C3 的断线重连**不经过本类的 reserve**（走 Redis `active` 续传分支），所以天然不重复扣。
 *   4. Redis 不可用时 **fail-open**（放行 + error 日志）：配额是成本保护，不是安全边界；
 *      Redis 抖动时让用户完全用不了 AI 的代价更大。日志 error 便于告警与人工补账。
 *   5. 回退允许跨日边界差 1：极端情况下 23:59:59 预扣、次日 00:00:01 才失败回退，会回退到
 *      新一天的 key（旧 key 已经快过期）。为此 PendingAsk 里存了预扣当天的日期，
 *      C3 回退时带上它，**不要用「今天」去猜**。
 */
package com.faze.modules.ai.service;

import com.faze.modules.ai.config.AiProperties;
import com.faze.modules.ai.config.AiRedisKeys;
import com.faze.modules.ai.dto.QuotaReservation;
import com.faze.modules.ai.dto.QuotaVO;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * AI 每日配额服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiQuotaService {

    /** 配额日期固定按东八区算（不跟机器时区）。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 日期格式 yyyyMMdd。 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /** TTL 额外缓冲（秒）：到次日 0 点的秒数 + 缓冲，避免边界上刚 INCR 就过期导致计数丢失。 */
    private static final long TTL_BUFFER_SECONDS = 120L;

    /**
     * 预扣 Lua：INCR → 判超 →（超限则 DECR 回退）→ 首次或没有 TTL 时设过期。
     *
     * <p>返回值：>=1 表示预扣成功后的已用次数；-1 表示超限（已回退，不占用名额）。</p>
     *
     * <p><b>为什么必须用脚本</b>：INCR / 判断 / DECR 三步分开执行时，
     * 两个实例可能同时越过上限判断，或把对方刚 INCR 的名额 DECR 回去。</p>
     */
    private static final String RESERVE_LUA = """
            local used = redis.call('INCR', KEYS[1])
            local limit = tonumber(ARGV[1])
            if used > limit then
              redis.call('DECR', KEYS[1])
              return -1
            end
            if used == 1 or redis.call('TTL', KEYS[1]) < 0 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            end
            return used
            """;

    /**
     * 回退 Lua：只在计数 > 0 时 DECR，**不允许出现负数**（负数会让次日重置前的额度变多）。
     *
     * <p>返回值：回退后的计数（0 表示本来就没占用）。</p>
     */
    private static final String REFUND_LUA = """
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            if used > 0 then
              return redis.call('DECR', KEYS[1])
            end
            return 0
            """;

    /** 预扣脚本。 */
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT =
            new DefaultRedisScript<>(RESERVE_LUA, Long.class);

    /** 回退脚本。 */
    private static final DefaultRedisScript<Long> REFUND_SCRIPT =
            new DefaultRedisScript<>(REFUND_LUA, Long.class);

    /** sys_config / 环境变量配置。 */
    private final AiProperties aiProperties;

    /** Redis 字符串模板（逻辑库 1）。 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 预扣 1 次配额。
     *
     * <p>副作用：一次 Redis EVAL（成功时 INCR 并可能 EXPIRE；超限时 INCR+DECR 原子回退）。</p>
     *
     * @param userId 用户 id
     * @return 预扣结果；`allowed=false` 时**不存在**需要回退的占用（脚本已回退）
     */
    public QuotaReservation reserve(Long userId) {
        String day = today();
        int limit = aiProperties.dailyQuota();
        long retryAfterSeconds = secondsUntilReset();
        if (limit <= 0) {
            // 运营把配额配成 0 = 当日关闭 AI（比「不检查」更安全）
            log.warn("AI 配额上限为 0，拒绝提问 userId={}", userId);
            return new QuotaReservation(false, 0, limit, retryAfterSeconds, day);
        }
        String key = AiRedisKeys.quota(userId, day);
        long ttl = secondsUntilReset() + TTL_BUFFER_SECONDS;
        try {
            Long result = redisTemplate.execute(RESERVE_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(limit), String.valueOf(ttl));
            if (result == null) {
                // 脚本返回 null 只可能是 Redis 异常路径；按 fail-open 处理并告警
                log.error("AI 配额脚本返回 null，放行本次请求 userId={}", userId);
                return new QuotaReservation(true, 0, limit, 0L, day);
            }
            if (result < 0) {
                log.warn("AI 配额超限 userId={} limit={} day={}", userId, limit, day);
                return new QuotaReservation(false, limit, limit, retryAfterSeconds, day);
            }
            return new QuotaReservation(true, result.intValue(), limit, 0L, day);
        } catch (RuntimeException e) {
            // Redis 抖动：fail-open，日志 error 便于告警；不把 Redis 细节抛给客户端
            log.error("AI 配额预扣失败，放行本次请求 userId={} error={}", userId,
                    e.getClass().getSimpleName());
            return new QuotaReservation(true, 0, limit, 0L, day);
        }
    }

    /**
     * 回退 1 次配额（预扣当天）。
     *
     * @param userId 用户 id
     * @param day    预扣时使用的日期（yyyyMMdd，来自 QuotaReservation）；为空时回退到今天
     */
    public void refund(Long userId, String day) {
        if (userId == null) {
            return;
        }
        String targetDay = day == null || day.isBlank() ? today() : day;
        String key = AiRedisKeys.quota(userId, targetDay);
        try {
            Long remaining = redisTemplate.execute(REFUND_SCRIPT, Collections.singletonList(key));
            log.info("AI 配额已回退 userId={} day={} remaining={}", userId, targetDay, remaining);
        } catch (RuntimeException e) {
            // 回退失败不能影响主流程（本次请求已经失败），但必须留痕便于人工核对
            log.error("AI 配额回退失败 userId={} day={} error={}", userId, targetDay,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 回退 1 次配额（今天；C2 内部失败路径用）。
     *
     * @param userId 用户 id
     */
    public void refund(Long userId) {
        refund(userId, today());
    }

    /**
     * 查询今日配额（`GET /app/ai/quota`）。
     *
     * @param userId 用户 id
     * @return 已用 / 上限 / 重置时间
     */
    public QuotaVO current(Long userId) {
        int limit = aiProperties.dailyQuota();
        long used = 0L;
        try {
            String raw = redisTemplate.opsForValue().get(AiRedisKeys.quota(userId, today()));
            used = raw == null ? 0L : Long.parseLong(raw);
        } catch (NumberFormatException e) {
            // 值被外部写脏：当 0 处理并告警，不让查询接口 500
            log.warn("AI 配额值不是合法数字 userId={}", userId);
        } catch (RuntimeException e) {
            log.error("读取 AI 配额失败 userId={} error={}", userId, e.getClass().getSimpleName());
        }
        return new QuotaVO(used, limit, resetAt());
    }

    /**
     * 今天（Asia/Shanghai）的 yyyyMMdd。
     *
     * @return 日期串
     */
    public String today() {
        return LocalDate.now(SHANGHAI).format(DAY_FORMAT);
    }

    /**
     * 距离下一次配额重置（次日 0 点）的秒数。
     *
     * @return 秒数，>=0
     */
    public long secondsUntilReset() {
        OffsetDateTime now = OffsetDateTime.now(SHANGHAI);
        OffsetDateTime next = LocalDate.now(SHANGHAI).plusDays(1)
                .atStartOfDay(SHANGHAI)
                .toOffsetDateTime();
        long seconds = Duration.between(now, next).getSeconds();
        return Math.max(0L, seconds);
    }

    /**
     * 配额重置时间（ISO-8601 带时区）。
     *
     * @return 如 2026-11-08T00:00:00+08:00
     */
    public String resetAt() {
        return LocalDate.now(SHANGHAI).plusDays(1)
                .atStartOfDay(SHANGHAI)
                .toOffsetDateTime()
                .toString();
    }

    /**
     * 批量读取多个用户的今日用量（后台/巡检用；本段 controller 未用，保留给 A-28 报表）。
     *
     * @param userIds 用户 id 列表；空集合直接返回空
     * @return 与入参顺序一致的用量列表
     */
    public List<Long> usedOf(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        String day = today();
        return userIds.stream().map(userId -> {
            try {
                String raw = redisTemplate.opsForValue().get(AiRedisKeys.quota(userId, day));
                return raw == null ? 0L : Long.parseLong(raw);
            } catch (RuntimeException e) {
                return 0L;
            }
        }).toList();
    }
}
