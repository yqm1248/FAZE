/*
 * 职责：提现申请幂等占位（`Idempotency-Key` 请求头 → 提现单 id 的短时映射），保证连点只产生一单。
 * 调用方：modules/trade/service/WithdrawService#apply（编排层，**在数据库事务之外**）。
 * 数据来源：Redis 库 1，key `faze:withdraw:idem:{lawyerId}:{clientKey}`。
 * 注意：
 *   1. 与段 2 的 `OrderIdempotencyGuard` 同一套三步状态机（抢锁 → 绑定 → 释放），
 *      不合并成一个类：两者 key 前缀与 TTL 语义不同，合并后改一个会伤到另一个已验收的链路。
 *   2. **按 lawyerId 分桶**：否则 A 律师的幂等键能读到 B 律师的提现单（越权读）。
 *   3. 值仍是 `creating` 说明上一个同 key 请求还在执行 → 抛 405004（请勿重复提交），不傻等。
 *   4. 客户端 key 只接受 `[A-Za-z0-9_-]{1,64}`；非法按「没传」处理并记 warn。
 */
package com.faze.modules.trade.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 提现申请幂等占位器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawIdempotencyGuard {

    /** key 前缀（与段 2 的 `faze:order:idem:` 并列，前缀空间不重叠）。 */
    private static final String KEY_PREFIX = "faze:withdraw:idem:";

    /** 占位值：表示同一 key 的请求正在执行。 */
    private static final String CREATING = "creating";

    /** 占位有效期：一次提现申请不可能跑 10 分钟。 */
    private static final Duration CREATING_TTL = Duration.ofMinutes(10);

    /** 结果有效期：覆盖「点完没反应又点一次」的现实窗口。 */
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    /** 客户端 key 最大长度。 */
    private static final int MAX_KEY_LENGTH = 64;

    private final StringRedisTemplate redis;

    /**
     * 尝试开始一次提现申请。
     *
     * <p>副作用：抢锁成功时写入占位 key。</p>
     *
     * @param lawyerId  律师主键（分桶用）
     * @param clientKey 客户端 `Idempotency-Key` 头，可为空
     * @return 命中幂等时返回**已存在的提现单 id**；空表示可以创建
     * @throws BusinessException 405004 同一个 key 的上一个请求仍在执行
     */
    public Optional<Long> tryStart(Long lawyerId, String clientKey) {
        String key = normalize(lawyerId, clientKey);
        if (key == null) {
            return Optional.empty();
        }
        Boolean acquired = redis.opsForValue().setIfAbsent(key, CREATING, CREATING_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        Long existing = parseId(redis.opsForValue().get(key));
        if (existing != null) {
            log.info("提现申请幂等命中 lawyerId={} withdrawId={}", lawyerId, existing);
            return Optional.of(existing);
        }
        log.warn("提现幂等冲突：上一个同 key 请求仍在执行 lawyerId={}", lawyerId);
        throw new BusinessException(ErrorCode.TRADE_DUPLICATE_SUBMIT);
    }

    /**
     * 绑定「幂等键 → 提现单 id」（业务成功后调用）。
     *
     * @param lawyerId   律师主键
     * @param clientKey  客户端幂等键
     * @param withdrawId 新建的提现单 id
     */
    public void bind(Long lawyerId, String clientKey, Long withdrawId) {
        String key = normalize(lawyerId, clientKey);
        if (key == null || withdrawId == null) {
            return;
        }
        redis.opsForValue().set(key, String.valueOf(withdrawId), RESULT_TTL);
    }

    /**
     * 释放占位（业务失败后调用）。
     *
     * @param lawyerId  律师主键
     * @param clientKey 客户端幂等键
     */
    public void release(Long lawyerId, String clientKey) {
        String key = normalize(lawyerId, clientKey);
        if (key != null) {
            redis.delete(key);
        }
    }

    /**
     * 规整 Redis key。
     *
     * @param lawyerId  律师主键
     * @param clientKey 客户端原始 key
     * @return 完整 key；未传或非法时 null（表示不做幂等）
     */
    private String normalize(Long lawyerId, String clientKey) {
        if (lawyerId == null || !StringUtils.hasText(clientKey)) {
            return null;
        }
        String trimmed = clientKey.trim();
        if (trimmed.length() > MAX_KEY_LENGTH || !trimmed.matches("[A-Za-z0-9_-]+")) {
            log.warn("忽略非法的 Idempotency-Key lawyerId={} length={}", lawyerId, trimmed.length());
            return null;
        }
        return KEY_PREFIX + lawyerId + ":" + trimmed;
    }

    /**
     * 解析 Redis 值。
     *
     * @param value Redis 值
     * @return 提现单 id；creating / 非数字 / null 时返回 null
     */
    private Long parseId(String value) {
        if (!StringUtils.hasText(value) || CREATING.equals(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("提现幂等键的值不是提现单 id，按未命中处理");
            return null;
        }
    }
}
