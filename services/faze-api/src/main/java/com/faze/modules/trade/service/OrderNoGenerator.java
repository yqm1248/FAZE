/*
 * 职责：业务单号生成器（订单 OR / 退款 RF / 充值 RC），Redis INCR 按天分桶。
 * 调用方：trade 域的 OrderService、退款与充值流程。
 * 数据来源：Redis key `faze:order:seq:{前缀}:{yyyyMMdd}`；数据库唯一键兜底。
 * 注意：
 *   1. **禁止用 MAX(order_no)+1**（并发必撞，表大了还慢）——工作流 G1.3 第 4 条。
 *   2. 日期按**东八区**取，不是 UTC：单号是给人看的（用户报单号、客服查单），
 *      晚上 8 点后的订单显示当天日期才合理；这与"库内时间存 UTC"不冲突——单号不是时间字段。
 *   3. key 的 TTL 2 天：跨天后旧 key 自然过期，不需要清理任务。
 *   4. Redis 挂了就**不能下单**（硬依赖，不退化成时间戳随机数，那会撞唯一键）。
 */
package com.faze.modules.trade.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 业务单号生成器。
 *
 * <p>格式：{@code 前缀2位 + yyyyMMdd + 序号6位}，例如 {@code OR20261101000001}。
 * 序号由 Redis {@code INCR} 按天分桶产生，数据库唯一键是最后一道防线。</p>
 */
@Service
@Slf4j
public class OrderNoGenerator {

    /** 序号位数；用尽即拒绝下单（单日 100 万单足够，宁失败不撞号）。 */
    private static final int SEQ_WIDTH = 6;

    /** 当日可用上限（999999）。 */
    private static final long MAX_SEQ = 999_999L;

    /** 序号 key 的存活时间：跨天后自然过期，省掉清理任务。 */
    private static final Duration KEY_TTL = Duration.ofDays(2);

    /** 单号日期用的业务时区：东八区（给人看的单号，见类注释第 2 条）。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Redis key 前缀，与项目统一约定一致（业务 key 统一 `faze:` 前缀）。 */
    private static final String KEY_PREFIX = "faze:order:seq:";

    private final StringRedisTemplate redis;

    private final Clock clock;

    /**
     * Spring 注入用的构造器（系统时钟）。
     *
     * @param redis Redis 客户端
     */
    @Autowired
    public OrderNoGenerator(StringRedisTemplate redis) {
        this(redis, Clock.system(BIZ_ZONE));
    }

    /**
     * 可注入时钟的构造器，仅供单测模拟跨天。
     *
     * @param redis Redis 客户端
     * @param clock 时钟（时区需为 {@link #BIZ_ZONE}）
     */
    OrderNoGenerator(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * 生成订单号。
     *
     * @return 形如 {@code OR20261101000001}
     */
    public String nextOrderNo() {
        return next(BizPrefix.ORDER);
    }

    /**
     * 生成退款单号。
     *
     * @return 形如 {@code RF20261101000001}
     */
    public String nextRefundNo() {
        return next(BizPrefix.REFUND);
    }

    /**
     * 生成充值单号（段 6 用，本段先提供以免段 6 再造一遍）。
     *
     * @return 形如 {@code RC20261101000001}
     */
    public String nextRechargeNo() {
        return next(BizPrefix.RECHARGE);
    }

    /**
     * 生成单号（内部统一实现）。
     *
     * <p>副作用：Redis INCR 一次；首次命中当天 key 时设置 TTL。</p>
     *
     * @param prefix 业务前缀
     * @return 完整单号
     * @throws BusinessException Redis 不可用或当日号段用尽时抛 501001
     */
    private String next(BizPrefix prefix) {
        String day = LocalDate.now(clock).format(DAY_FORMAT);
        String key = KEY_PREFIX + prefix.code + ":" + day;

        Long seq;
        try {
            seq = redis.opsForValue().increment(key);
        } catch (RuntimeException e) {
            // Redis 是硬依赖：宁可下单失败，也不生成可能重复的号
            log.error("单号生成失败：Redis INCR 异常 key={}", key, e);
            throw new BusinessException(ErrorCode.COMMON_INTERNAL, "单号服务不可用");
        }
        if (seq == null) {
            throw new BusinessException(ErrorCode.COMMON_INTERNAL, "单号服务返回空序号");
        }
        if (seq == 1L) {
            redis.expire(key, KEY_TTL);
        }
        if (seq > MAX_SEQ) {
            log.error("当日单号已用尽 prefix={} day={} seq={}", prefix.code, day, seq);
            throw new BusinessException(ErrorCode.COMMON_INTERNAL, "当日单号已用尽");
        }
        return prefix.code + day + String.format("%0" + SEQ_WIDTH + "d", seq);
    }

    /**
     * 业务前缀。
     */
    private enum BizPrefix {
        /** 订单。 */
        ORDER("OR"),
        /** 退款。 */
        REFUND("RF"),
        /** 充值（段 6）。 */
        RECHARGE("RC");

        private final String code;

        BizPrefix(String code) {
            this.code = code;
        }
    }
}
