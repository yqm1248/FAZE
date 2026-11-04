/*
 * 职责：短信验证码的发送与校验（第 1 段 B1）。开发期走固定码，生产必须接真实通道。
 * 调用方：modules/account/controller/SmsController（发码）、后续注册 / 短信登录 / 重置密码校验。
 * 数据来源：Redis 逻辑库 1（faze:sms:*）；开关与阈值来自 ops 域的 SysConfigQueryService。
 * 注意：
 *   1. **验证码绝不回传给客户端**，dev 也不回传——回传等于把开关漏给任何人，
 *      且日志里也不打印验证码本身，只打印「使用了固定码」。
 *   2. **消费即失效**：校验通过立刻 DEL，不能等 TTL 自然过期，
 *      否则同一验证码可以被重放整个 5 分钟窗口。
 *   3. **限流三层**：同号 60 秒间隔、同号每日上限、同 IP 每分钟上限。
 *      三层各自独立，任何一层先命中就拒绝——只做单层挡不住「换 IP 打一个号」。
 *   4. 固定码只在**非 prod** 且 `sys_config.sms_fixed_code_enabled=true` 时生效；
 *      prod 下的强制校验在 ProdSmsFixedCodeGuard（启动即失败）。
 *   5. 本段没有真实短信通道，固定码关闭时直接抛 502001，**不许静默成功**——
 *      静默成功会让「短信没发出去但流程走通了」这种故障在生产上无法被发现。
 */
package com.faze.modules.account.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 短信验证码服务。
 */
@Service
@Slf4j
public class SmsCodeService {

    /** 验证码 Redis key 前缀。 */
    private static final String KEY_CODE = "faze:sms:code:";
    /** 同号发送间隔 key 前缀。 */
    private static final String KEY_INTERVAL = "faze:sms:interval:";
    /** 同号每日计数 key 前缀（后接 :yyyyMMdd）。 */
    private static final String KEY_DAILY = "faze:sms:daily:";
    /** 同 IP 每分钟计数 key 前缀（后接 :yyyyMMddHHmm）。 */
    private static final String KEY_IP = "faze:sms:ip:";

    /** 验证码有效期：5 分钟。 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    /** 同 IP 每分钟上限。 */
    private static final int IP_LIMIT_PER_MINUTE = 10;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 密码学安全的随机源，不用 Math.random。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final SysConfigQueryService sysConfig;
    private final Environment environment;

    /**
     * 构造服务。
     *
     * @param redis       字符串模板
     * @param sysConfig   平台参数只读查询
     * @param environment 运行环境（判断是否 prod）
     */
    public SmsCodeService(StringRedisTemplate redis,
                          SysConfigQueryService sysConfig,
                          Environment environment) {
        this.redis = redis;
        this.sysConfig = sysConfig;
        this.environment = environment;
    }

    /**
     * 发送验证码。
     *
     * <p>前置：手机号格式已由 BO 校验；scene 只影响日志与后续业务归属，本方法不做场景校验。</p>
     * <p>副作用：写 Redis 三个计数 key + 一个验证码 key。</p>
     *
     * @param phone    手机号
     * @param scene    场景：register / login / reset
     * @param clientIp 客户端 IP，用于 IP 维度限流
     * @throws BusinessException 401007 触发限流 / 502001 无真实通道且固定码关闭
     */
    public void send(String phone, String scene, String clientIp) {
        // 任务 1：同号发送间隔（60 秒）。用 setIfAbsent 保证并发下只有一个请求能占到。
        int intervalSeconds = sysConfig.getInt("sms_send_interval_seconds", 60);
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(KEY_INTERVAL + phone, "1", Duration.ofSeconds(intervalSeconds));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED);
        }

        // 任务 2：同号每日上限。首次计数时把 TTL 设到当天结束，避免计数 key 永久堆积。
        int dailyLimit = sysConfig.getInt("sms_daily_limit", 10);
        String dailyKey = KEY_DAILY + phone + ":" + LocalDate.now(ZoneOffset.UTC).format(DAY_FMT);
        Long dailyCount = redis.opsForValue().increment(dailyKey);
        if (dailyCount != null && dailyCount == 1L) {
            redis.expire(dailyKey, secondsUntilTomorrowUtc());
        }
        if (dailyCount != null && dailyCount > dailyLimit) {
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED);
        }

        // 任务 3：同 IP 每分钟上限。key 里带分钟号，过期即自然清零。
        if (StringUtils.hasText(clientIp)) {
            String ipKey = KEY_IP + clientIp + ":" + LocalDateTime.now(ZoneOffset.UTC).format(MINUTE_FMT);
            Long ipCount = redis.opsForValue().increment(ipKey);
            if (ipCount != null && ipCount == 1L) {
                redis.expire(ipKey, Duration.ofMinutes(1));
            }
            if (ipCount != null && ipCount > IP_LIMIT_PER_MINUTE) {
                throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED);
            }
        }

        // 任务 4：决定用固定码还是随机码，并落 Redis。
        String code = resolveCode(phone, scene);
        redis.opsForValue().set(KEY_CODE + phone, code, CODE_TTL);
        log.info("验证码已生成 scene={} phone={} ip={}", scene, maskPhone(phone), clientIp);
    }

    /**
     * 校验并消费验证码。
     *
     * <p>前置：验证码在 5 分钟有效期内且未被使用过。</p>
     * <p>副作用：校验通过后**立刻删除** Redis 中的验证码，防止重放。</p>
     *
     * @param phone 手机号
     * @param code  用户提交的验证码
     * @throws BusinessException 402005 验证码错误或已过期
     */
    public void verifyAndConsume(String phone, String code) {
        if (!StringUtils.hasText(phone) || !StringUtils.hasText(code)) {
            throw new BusinessException(ErrorCode.AUTH_CODE_INVALID);
        }
        String key = KEY_CODE + phone;
        String expected = redis.opsForValue().get(key);
        if (expected == null || !expected.equals(code.trim())) {
            throw new BusinessException(ErrorCode.AUTH_CODE_INVALID);
        }
        redis.delete(key);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 决定本次发送使用的验证码。
     *
     * @param phone 手机号
     * @param scene 场景（仅日志）
     * @return 6 位验证码
     */
    private String resolveCode(String phone, String scene) {
        boolean dev = !environment.acceptsProfiles(Profiles.of("prod"));
        boolean fixedEnabled = sysConfig.getBoolean("sms_fixed_code_enabled", false);
        if (dev && fixedEnabled) {
            String fixed = sysConfig.getString("sms_fixed_code", "123456");
            log.info("使用固定验证码（dev + sms_fixed_code_enabled=true）scene={} phone={}",
                    scene, maskPhone(phone));
            return fixed;
        }
        // 本段没有真实短信通道：关闭固定码就是「发不出去」，必须明确失败，不许假装成功
        throw new BusinessException(ErrorCode.AUTH_SMS_SEND_FAILED);
    }

    /**
     * 距次日 0 点（UTC）的秒数，用于每日计数 key 的 TTL。
     *
     * @return 秒数，最小 60 秒（防止恰好在 23:59:59 时算出 0 导致 key 立刻过期）
     */
    private Duration secondsUntilTomorrowUtc() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime tomorrow = now.toLocalDate().plusDays(1).atStartOfDay();
        long seconds = Duration.between(now, tomorrow).getSeconds();
        return Duration.ofSeconds(Math.max(seconds, 60));
    }

    /**
     * 日志用手机号脱敏。
     *
     * @param phone 手机号
     * @return 138****8888；过短时返回空串
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 生成 6 位数字验证码（当前只在测试与未来接真实通道时使用）。
     *
     * @return 000000~999999
     */
    String randomCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }
}
