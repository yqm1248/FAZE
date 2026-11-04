/*
 * 职责：登录失败计数与临时锁定（第 1 段 B2，Redis 状态）。
 * 调用方：modules/account/service/AuthService（密码登录路径）。
 * 数据来源：Redis 逻辑库 1；阈值来自 sys_config 的 login_max_fail / login_lock_minutes。
 * 注意：
 *   1. **状态必须放 Redis 而不是进程内**：多实例部署时进程内计数各算各的，
 *      攻击者只要打到不同实例就能绕过阈值（wgcloud 的 ExpiringMap 就是这个毛病，
 *      见《后端代码规范》§1.5 机制 4）。
 *   2. 计数 key 的 TTL 与锁定 TTL 都用 login_lock_minutes：
 *      计数窗口太长会让「几个月前的 3 次失败」也算进来，太短则单次爆破打不满阈值。
 *   3. 锁定用独立 key（faze:login:lock:<phone>），不靠计数判断——
 *      否则锁定期间又有失败时计数继续涨，解锁时间会被无限推后。
 *   4. 锁定错误码用 401007（限流）：错误码规范没有「账号锁定」专用码，
 *      自行发明码会破坏客户端错误码归一化。剩余分钟只写日志，不进响应。
 */
package com.faze.modules.account.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 登录失败守卫。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginGuardService {

    /** 失败计数 key 前缀。 */
    private static final String KEY_FAIL = "faze:login:fail:";
    /** 锁定标记 key 前缀。 */
    private static final String KEY_LOCK = "faze:login:lock:";

    private static final int DEFAULT_MAX_FAIL = 5;
    private static final int DEFAULT_LOCK_MINUTES = 15;

    private final StringRedisTemplate redis;
    private final SysConfigQueryService sysConfig;

    /**
     * 登录前检查是否处于锁定期。
     *
     * @param phone 手机号
     * @throws BusinessException 401007 仍在锁定期内
     */
    public void assertNotLocked(String phone) {
        if (Boolean.TRUE.equals(redis.hasKey(KEY_LOCK + phone))) {
            Long ttl = redis.getExpire(KEY_LOCK + phone);
            log.warn("账号处于登录锁定期 phone={} 剩余={}s", mask(phone), ttl);
            throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED);
        }
    }

    /**
     * 记录一次登录失败；达到阈值时写锁定标记。
     *
     * <p>副作用：写/更新两个 Redis key。</p>
     *
     * @param phone 手机号
     */
    public void recordFailure(String phone) {
        int maxFail = sysConfig.getInt("login_max_fail", DEFAULT_MAX_FAIL);
        int lockMinutes = sysConfig.getInt("login_lock_minutes", DEFAULT_LOCK_MINUTES);
        Duration window = Duration.ofMinutes(lockMinutes);

        String failKey = KEY_FAIL + phone;
        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            redis.expire(failKey, window);
        }
        if (count != null && count >= maxFail) {
            redis.opsForValue().set(KEY_LOCK + phone, "1", window);
            redis.delete(failKey);
            log.warn("登录失败达到阈值，账号锁定 phone={} 次数={} 时长={}分",
                    mask(phone), count, lockMinutes);
        }
    }

    /**
     * 登录成功后清除失败计数。
     *
     * @param phone 手机号
     */
    public void clearFailure(String phone) {
        redis.delete(KEY_FAIL + phone);
    }

    /**
     * 日志脱敏。
     *
     * @param phone 手机号
     * @return 138****8888；过短返回空串
     */
    private String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
