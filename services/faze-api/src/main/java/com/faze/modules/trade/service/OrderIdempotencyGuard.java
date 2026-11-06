/*
 * 职责：下单幂等占位（`Idempotency-Key` 请求头 → 订单 id 的短时映射），保证连点只产生一单。
 * 调用方：modules/trade/service/OrderService#create（编排层，**在数据库事务之外**）。
 * 数据来源：Redis 逻辑库 1，key `faze:order:idem:{userId}:{clientKey}`。
 * 注意：
 *   1. **为什么用 Redis 而不是 orders 表加一列**：`orders` 表结构已冻结（V200 已执行），
 *      加唯一索引列要再发一次迁移；而幂等键只在「点按钮后几十秒内」有意义，天然适合短 TTL。
 *   2. **三步状态机**：`setIfAbsent("creating")` 抢锁 → 业务成功 `set(orderId)` →
 *      业务失败 `delete`。少了 release，一次失败会让用户 10 分钟内都提交不了。
 *   3. 值是 `creating` 时再收到同一 key 说明**上一个请求还在执行**，
 *      立即返回 405004（请勿重复提交），不要傻等——等下去只会把线程池占满。
 *   4. key 必须按 **userId 分桶**：否则 A 用户的幂等键能读到 B 用户的订单 id（越权读）。
 *   5. 客户端传的 key 只接受 `[A-Za-z0-9_-]{1,64}`；非法值按「没传」处理并记 warn——
 *      把任意字符串拼进 Redis key 会制造不可控的 key 空间（超长、含分隔符）。
 *   6. 幂等键没传时**不报错**：下单是正常业务，客户端的疏忽不该阻断用户；
 *      真正防连点的第一道是前端按钮禁用，这里只是服务端兜底。
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
 * 下单幂等占位器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIdempotencyGuard {

    /** key 前缀，与项目统一约定一致（业务 key 统一 `faze:` 前缀）。 */
    private static final String KEY_PREFIX = "faze:order:idem:";

    /** 占位值：表示「同一 key 的请求正在执行中」。 */
    private static final String CREATING = "creating";

    /** 占位有效期：一次下单不可能跑 10 分钟，超过当作客户端异常遗留。 */
    private static final Duration CREATING_TTL = Duration.ofMinutes(10);

    /** 结果有效期：覆盖「用户点完发现没反应又点一次」的现实窗口。 */
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    /** 客户端 key 的最大长度（接口规范约定的幂等键是 UUID 级别，64 足够）。 */
    private static final int MAX_KEY_LENGTH = 64;

    private final StringRedisTemplate redis;

    /**
     * 尝试开始一次下单。
     *
     * <p>副作用：抢锁成功时写入占位 key（TTL 10 分钟）。</p>
     *
     * @param userId    下单用户（用于分桶，不允许跨用户复用 key）
     * @param clientKey 客户端 `Idempotency-Key` 头，可为空
     * @return 命中幂等时返回**已存在的订单 id**；返回空表示「本次可以创建」，调用方必须
     *         在成功时 {@link #bind} 、失败时 {@link #release}
     * @throws BusinessException 405004：同一 key 的上一个请求仍在执行中
     */
    public Optional<Long> tryStart(Long userId, String clientKey) {
        String key = normalize(userId, clientKey);
        if (key == null) {
            return Optional.empty();
        }

        Boolean acquired = redis.opsForValue().setIfAbsent(key, CREATING, CREATING_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }

        String existing = redis.opsForValue().get(key);
        Long orderId = parseOrderId(existing);
        if (orderId != null) {
            log.info("下单幂等命中 userId={} orderId={}", userId, orderId);
            return Optional.of(orderId);
        }
        // 值仍是 creating（或 TTL 刚过读到 null）：上一个请求没跑完
        log.warn("下单幂等冲突：上一个同 key 请求仍在执行 userId={}", userId);
        throw new BusinessException(ErrorCode.TRADE_DUPLICATE_SUBMIT);
    }

    /**
     * 把「幂等键 → 订单 id」绑定起来（业务成功后调用，**必须在事务提交之后**）。
     *
     * <p>副作用：覆盖占位值为订单 id，TTL 改为 24 小时。</p>
     *
     * @param userId    下单用户
     * @param clientKey 客户端 `Idempotency-Key` 头
     * @param orderId   新建的订单 id
     */
    public void bind(Long userId, String clientKey, Long orderId) {
        String key = normalize(userId, clientKey);
        if (key == null || orderId == null) {
            return;
        }
        redis.opsForValue().set(key, String.valueOf(orderId), RESULT_TTL);
    }

    /**
     * 释放占位（业务失败后调用）。
     *
     * <p>副作用：删除 key。**只在确实没建成订单时调用**——订单已落库却删了 key，
     * 用户再点一次就会产生第二单。</p>
     *
     * @param userId    下单用户
     * @param clientKey 客户端 `Idempotency-Key` 头
     */
    public void release(Long userId, String clientKey) {
        String key = normalize(userId, clientKey);
        if (key == null) {
            return;
        }
        redis.delete(key);
    }

    /**
     * 把客户端 key 规整成完整 Redis key。
     *
     * @param userId    下单用户
     * @param clientKey 客户端原始 key
     * @return 完整 key；未传或格式非法时返回 null（表示不做幂等）
     */
    private String normalize(Long userId, String clientKey) {
        if (userId == null || !StringUtils.hasText(clientKey)) {
            return null;
        }
        String trimmed = clientKey.trim();
        if (trimmed.length() > MAX_KEY_LENGTH || !trimmed.matches("[A-Za-z0-9_-]+")) {
            log.warn("忽略非法的 Idempotency-Key（长度或字符集不符）userId={} length={}",
                    userId, trimmed.length());
            return null;
        }
        return KEY_PREFIX + userId + ":" + trimmed;
    }

    /**
     * 解析 Redis 里的值。
     *
     * @param value Redis 值
     * @return 订单 id；值为 null / creating / 非数字时返回 null
     */
    private Long parseOrderId(String value) {
        if (!StringUtils.hasText(value) || CREATING.equals(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("下单幂等键的值不是订单 id，按未命中处理 value={}", value);
            return null;
        }
    }
}
