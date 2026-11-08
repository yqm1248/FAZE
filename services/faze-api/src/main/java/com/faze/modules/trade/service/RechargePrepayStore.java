/*
 * 职责：缓存「充值单号 → 拉起渠道支付的参数」，供幂等命中时回放（第 6 段 B1）。
 * 调用方：modules/trade/service/RechargeOrderService#create 与 #rebuildOnIdempotentHit。
 * 数据来源：渠道 prepay() 的返回值；写 Redis 逻辑库 1，key `faze:recharge:payparams:{orderNo}`。
 * 注意：
 *   1. **为什么要把 payParams 存起来**：`Idempotency-Key` 重复提交时必须返回**同一张单**，
 *      而 App 拿到单号后还要靠 payParams 去拉起渠道。不缓存的话，第二次请求只能返回空参数，
 *      用户点「再试一次」就没法付钱。
 *   2. **不重新调 prepay() 来「再拿一次」**：微信/支付宝对同一 `out_trade_no` 重复下单
 *      会报「订单已存在/已支付」，把幂等命中变成渠道错误是最常见的自伤写法。
 *   3. **TTL 24 小时与幂等键一致**：过了 24 小时用户还点旧按钮，本来也该重新下单。
 *   4. 值用 URL 编码的 `k=v&k2=v2` 存，不用 JSON——`payParams` 里的 `callbackBody` 含引号与花括号，
 *      走 URL 编码不会与分隔符冲突，也不依赖 Jackson 3 的 `TypeReference` API 细节。
 *   5. **缓存里不含密钥**：payParams 本身就是给客户端看的公开参数（见 RechargeCreateVO 注释）。
 */
package com.faze.modules.trade.service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 充值预支付参数缓存。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RechargePrepayStore {

    /** key 前缀；与其他 `faze:` 业务 key 并列。 */
    private static final String KEY_PREFIX = "faze:recharge:payparams:";

    /** 缓存存活时间，与幂等键的 24 小时对齐。 */
    private static final Duration TTL = Duration.ofHours(24);

    /** 键值对分隔符。 */
    private static final String PAIR_SEP = "&";

    /** 键值分隔符。 */
    private static final String KV_SEP = "=";

    private final StringRedisTemplate redis;

    /**
     * 保存一次 prepay 的参数。
     *
     * <p>副作用：写 Redis，TTL 24 小时；空参数直接跳过（Mock 关闭时不会有这种情况）。</p>
     *
     * @param orderNo   平台充值单号
     * @param payParams 渠道返回的公开参数；为空时不写
     */
    public void save(String orderNo, Map<String, String> payParams) {
        if (!StringUtils.hasText(orderNo) || payParams == null || payParams.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : payParams.entrySet()) {
            if (builder.length() > 0) {
                builder.append(PAIR_SEP);
            }
            builder.append(encode(entry.getKey())).append(KV_SEP).append(encode(entry.getValue()));
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + orderNo, builder.toString(), TTL);
        } catch (RuntimeException e) {
            // 缓存失败不影响本次下单：用户当前这次请求已经拿到 payParams 了
            log.warn("充值预支付参数缓存失败 orderNo={}", orderNo, e);
        }
    }

    /**
     * 读回一次 prepay 的参数。
     *
     * @param orderNo 平台充值单号
     * @return 参数 map；缓存缺失或格式异常时返回空 map（调用方按「无参数」处理，不抛异常）
     */
    public Map<String, String> load(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            return Map.of();
        }
        String raw;
        try {
            raw = redis.opsForValue().get(KEY_PREFIX + orderNo);
        } catch (RuntimeException e) {
            log.warn("充值预支付参数读取失败 orderNo={}", orderNo, e);
            return Map.of();
        }
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : raw.split(PAIR_SEP)) {
            int idx = pair.indexOf(KV_SEP);
            if (idx <= 0) {
                continue;
            }
            result.put(decode(pair.substring(0, idx)), decode(pair.substring(idx + KV_SEP.length())));
        }
        return result;
    }

    /**
     * URL 编码，null 安全。
     *
     * @param raw 原文
     * @return 编码结果；null 返回空串
     */
    private String encode(String raw) {
        return URLEncoder.encode(raw == null ? "" : raw, StandardCharsets.UTF_8);
    }

    /**
     * URL 解码，失败时原样返回（不让一条脏缓存把整个下单接口打挂）。
     *
     * @param raw 编码串
     * @return 解码结果
     */
    private String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("充值预支付参数缓存含非法编码，按原样返回");
            return raw;
        }
    }
}
