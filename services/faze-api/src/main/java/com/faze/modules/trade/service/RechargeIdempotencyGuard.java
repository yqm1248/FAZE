/*
 * 职责：充值下单的幂等占位（`Idempotency-Key` → 充值单 id 的短时映射），保证连点只产生一单。
 * 调用方：modules/trade/service/RechargeOrderService#create（编排层，**在数据库事务之外**）。
 * 数据来源：Redis 逻辑库 1，key `faze:recharge:idem:{userId}:{clientKey}`。
 * 注意：
 *   1. **为什么不复用段 2 的 OrderIdempotencyGuard**：那个类的 key 前缀是
 *      `faze:order:idem:`，值语义是「咨询订单 id」，且它属于段 2 已验收代码；
 *      充值单混进同一 key 空间会让运维排查时无法区分业务，改动已验收文件也无收益。
 *      两者是**同构复制**而非继承——注入一份「通用幂等器」反而要把它从段 2 抽出来重构。
 *   2. 三步状态机与段 2 一致：`setIfAbsent("creating")` 抢锁 → 成功 `bind(recordId)` →
 *      失败 `release`。少了 release，一次失败会让用户 24 小时内都提交不了同一 key。
 *   3. 值是 `creating` 时说明上一个请求仍在执行 → 抛 405004（请勿重复提交），不傻等。
 *   4. key 按 `userId` 分桶：否则 A 用户的幂等键能读到 B 用户的单（越权读）。
 *   5. 客户端 key 只接受 `[A-Za-z0-9_-]{1,64}`；非法值按「没传」处理并 warn。
 *   6. **幂等键没传不报错**：充值下单是正常业务，服务端只是兜底；防连点第一道是前端按钮禁用。
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
 * 充值下单幂等占位器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RechargeIdempotencyGuard {

    /** key 前缀；与 `faze:order:idem:` 并列但独立，见类注释第 1 条。 */
    private static final String KEY_PREFIX = "faze:recharge:idem:";

    /** 占位值：同一 key 的请求正在执行中。 */
    private static final String CREATING = "creating";

    /** 抢锁占位有效期；一次下单不可能跑 10 分钟。 */
    private static final Duration CREATING_TTL = Duration.ofMinutes(10);

    /** 结果有效期：24 小时（B1 第 1 条要求「Redis 24 小时去重」）。 */
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    /** 客户端 key 最大长度。 */
    private static final int MAX_KEY_LENGTH = 64;

    private final StringRedisTemplate redis;

    /**
     * 尝试开始一次充值下单。
     *
     * <p>副作用：抢锁成功时写入占位 key（TTL 10 分钟）。</p>
     *
     * @param userId    下单用户（用于分桶）
     * @param clientKey 客户端 `Idempotency-Key` 头，可为空
     * @return 命中幂等时返回**已存在的充值单 id**；空表示可以创建，
     *         调用方必须在成功时 {@link #bind}、失败时 {@link #release}
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
        Long recordId = parseRecordId(existing);
        if (recordId != null) {
            log.info("充值下单幂等命中 userId={} recordId={}", userId, recordId);
            return Optional.of(recordId);
        }
        log.warn("充值下单幂等冲突：上一个同 key 请求仍在执行 userId={}", userId);
        throw new BusinessException(ErrorCode.TRADE_DUPLICATE_SUBMIT);
    }

    /**
     * 绑定「幂等键 → 充值单 id」（落单成功后调用）。
     *
     * <p>副作用：覆盖占位值为单 id，TTL 改为 24 小时。</p>
     *
     * @param userId    下单用户
     * @param clientKey 客户端幂等键
     * @param recordId  新建的充值单 id
     */
    public void bind(Long userId, String clientKey, Long recordId) {
        String key = normalize(userId, clientKey);
        if (key == null || recordId == null) {
            return;
        }
        redis.opsForValue().set(key, String.valueOf(recordId), RESULT_TTL);
    }

    /**
     * 释放占位（确实没建成单时调用）。
     *
     * <p>副作用：删除 key。**单已落库时不许调用**——删了 key 用户再点就产生第二单。</p>
     *
     * @param userId    下单用户
     * @param clientKey 客户端幂等键
     */
    public void release(Long userId, String clientKey) {
        String key = normalize(userId, clientKey);
        if (key == null) {
            return;
        }
        redis.delete(key);
    }

    /**
     * 规整成完整 Redis key。
     *
     * @param userId    下单用户
     * @param clientKey 客户端原始 key
     * @return 完整 key；未传或格式非法返回 null（表示不做幂等）
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
     * @return 充值单 id；null / creating / 非数字时返回 null
     */
    private Long parseRecordId(String value) {
        if (!StringUtils.hasText(value) || CREATING.equals(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("充值幂等键的值不是单 id，按未命中处理");
            return null;
        }
    }
}
