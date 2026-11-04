/*
 * 职责：支付密码的设置、修改、重置、校验（第 1 段 E1 + E2，锚点 1-A5）。
 * 调用方：modules/account/controller/PayPasswordController（设置/修改/重置）、
 *         段 2 起的支付链路（verify）。
 * 数据来源：app_user.pay_password（BCrypt）；锁定状态在 Redis；阈值在 sys_config。
 * 注意：
 *   1. **支付密码与登录密码完全独立**：字段不同、强度不同（6 位纯数字 vs ≥8 位含字母）、
 *      锁定策略不同（5 次/30 分钟 vs 5 次/15 分钟）、重置方式不同（短信 vs 无）。
 *   2. 6 位纯数字只有 100 万种组合，**锁定是它的安全前提**，不是可选项。
 *      没有锁定时，脚本每秒可以试几百个，几小时就能穷举。
 *   3. 锁定 key 与失败计数 key 分开：锁定期间又失败不应延长锁定
 *      （E2 只在 >= 阈值时写一次锁定，之后计数被清掉）。
 *   4. **锁定不可手工解除**（E2 第 5 条）：本类不提供任何"解锁"方法。
 *      运维只能清 Redis，这是刻意的——有解锁接口就等于有后门。
 *   5. 锁定状态存 Redis，重启会丢锁：可接受，因为只会「放宽」不会「误锁」。
 */
package com.faze.modules.account.service;

import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.AppUser;
import com.faze.common.exception.PayPasswordLockedException;
import com.faze.common.exception.PayPasswordWrongException;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.account.service.SmsCodeService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 支付密码服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayPasswordService {

    private static final String MODULE_ACCOUNT = "account";
    private static final String KEY_FAIL = "faze:paypwd:fail:";
    private static final String KEY_LOCKED = "faze:paypwd:locked:";
    private static final int DEFAULT_MAX_FAIL = 5;
    private static final int DEFAULT_LOCK_MINUTES = 30;

    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final SysConfigQueryService sysConfig;
    private final SmsCodeService smsCodeService;

    /**
     * 首次设置支付密码。
     *
     * <p>前置：尚未设置过支付密码。</p>
     * <p>副作用：写入 pay_password（BCrypt）；写 operation_log。</p>
     *
     * @param userId      当前用户
     * @param newPassword 6 位数字
     * @throws BusinessException 401006 已设置过（请走修改）
     */
    @OperationLog(module = MODULE_ACCOUNT, action = "SET_PAY_PASSWORD", targetType = "APP_USER")
    public void set(Long userId, String newPassword) {
        AppUser user = requireUser(userId);
        if (user.getPayPassword() != null && !user.getPayPassword().isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        applyPassword(userId, newPassword);
        AuditContext.mark(MODULE_ACCOUNT, "SET_PAY_PASSWORD", "APP_USER", String.valueOf(userId),
                "{\"action\":\"set\"}");
        log.info("支付密码已设置 userId={}", userId);
    }

    /**
     * 修改支付密码（需旧支付密码）。
     *
     * <p>前置：已设置过；旧密码正确；未处于锁定期。</p>
     * <p>副作用：更新 pay_password；清失败计数；写 operation_log。</p>
     *
     * @param userId      当前用户
     * @param oldPassword 旧支付密码
     * @param newPassword 新支付密码
     * @throws BusinessException 401006 未设置 / 402001 旧密码错 / 锁定异常
     */
    @OperationLog(module = MODULE_ACCOUNT, action = "CHANGE_PAY_PASSWORD", targetType = "APP_USER")
    public void change(Long userId, String oldPassword, String newPassword) {
        AppUser user = requireUser(userId);
        if (user.getPayPassword() == null || user.getPayPassword().isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        // 旧密码校验走统一入口（含锁定与计数），与支付场景共用同一套防爆破状态
        checkPassword(userId, user.getPayPassword(), oldPassword);
        applyPassword(userId, newPassword);
        AuditContext.mark(MODULE_ACCOUNT, "CHANGE_PAY_PASSWORD", "APP_USER", String.valueOf(userId),
                "{\"action\":\"change\"}");
        log.info("支付密码已修改 userId={}", userId);
    }

    /**
     * 重置支付密码（短信验证码）。
     *
     * <p>前置：验证码有效；已设置过（未设置时用 set 接口）。</p>
     * <p>副作用：更新 pay_password；**清失败计数与锁定**；写 operation_log。</p>
     *
     * @param userId      当前用户
     * @param smsCode     短信验证码
     * @param newPassword 新支付密码
     * @throws BusinessException 402005 验证码错 / 401006 未设置
     */
    @OperationLog(module = MODULE_ACCOUNT, action = "RESET_PAY_PASSWORD", targetType = "APP_USER")
    public void reset(Long userId, String smsCode, String newPassword) {
        AppUser user = requireUser(userId);
        // 验证码按手机号存，必须用用户真实手机号校验（不接受调用方传号码，防越权重置他人）
        smsCodeService.verifyAndConsume(user.getPhone(), smsCode);
        applyPassword(userId, newPassword);
        // 重置成功即证明本人，清掉锁定与计数（E2 验收第 5 条）
        clearLock(userId);
        AuditContext.mark(MODULE_ACCOUNT, "RESET_PAY_PASSWORD", "APP_USER", String.valueOf(userId),
                "{\"action\":\"reset\"}");
        log.info("支付密码已重置 userId={}", userId);
    }

    /**
     * 校验支付密码（供段 2 起的支付链路调用）。
     *
     * <p>前置：已设置；未处于锁定期。</p>
     * <p>副作用：失败时 INCR 计数、达到阈值写锁定；成功时清两个 key。</p>
     *
     * @param userId   用户主键
     * @param password 待校验的 6 位数字
     * @throws BusinessException 401006 未设置 / 402001 密码错 / 锁定异常
     */
    public void verify(Long userId, String password) {
        AppUser user = requireUser(userId);
        if (user.getPayPassword() == null || user.getPayPassword().isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        checkPassword(userId, user.getPayPassword(), password);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 统一的密码校验（含锁定检查、失败计数、成功清零）。
     *
     * @param userId 用户主键
     * @param hash   库中的 BCrypt 哈希
     * @param input  用户输入的明文
     * @throws PayPasswordLockedException 锁定期内
     * @throws PayPasswordWrongException  密码错误（带剩余次数）
     */
    private void checkPassword(Long userId, String hash, String input) {
        // 任务 1：锁定期内直接拒绝，连 BCrypt 都不跑（省 CPU 且不给时序信息）
        assertNotLocked(userId);
        // 任务 2：校验
        if (input == null || !passwordEncoder.matches(input, hash)) {
            int remaining = recordFailure(userId);
            throw new PayPasswordWrongException(remaining);
        }
        // 任务 3：成功清计数
        clearLock(userId);
    }

    /**
     * 检查是否处于锁定期。
     *
     * @param userId 用户主键
     * @throws PayPasswordLockedException 仍在锁定期
     */
    private void assertNotLocked(Long userId) {
        Long ttl = redis.getExpire(KEY_LOCKED + userId);
        if (ttl != null && ttl > 0) {
            log.warn("支付密码处于锁定期 userId={} 剩余={}s", userId, ttl);
            throw new PayPasswordLockedException(ttl);
        }
    }

    /**
     * 记录一次失败，达到阈值时写锁定。
     *
     * @param userId 用户主键
     * @return 剩余尝试次数（0 表示本次触发锁定）
     */
    private int recordFailure(Long userId) {
        int maxFail = sysConfig.getInt("pay_pwd_max_fail", DEFAULT_MAX_FAIL);
        int lockMinutes = sysConfig.getInt("pay_pwd_lock_minutes", DEFAULT_LOCK_MINUTES);
        Duration window = Duration.ofMinutes(lockMinutes);

        String failKey = KEY_FAIL + userId;
        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            redis.expire(failKey, window);
        }
        if (count != null && count >= maxFail) {
            redis.opsForValue().set(KEY_LOCKED + userId, "1", window);
            redis.delete(failKey);
            log.warn("支付密码连续错误达到阈值，已锁定 userId={} 时长={}分", userId, lockMinutes);
            return 0;
        }
        long remaining = (count == null ? 0L : count);
        return (int) Math.max(maxFail - remaining, 0L);
    }

    /**
     * 清失败计数与锁定。
     *
     * @param userId 用户主键
     */
    private void clearLock(Long userId) {
        redis.delete(KEY_FAIL + userId);
        redis.delete(KEY_LOCKED + userId);
    }

    /**
     * 写入新支付密码哈希。
     *
     * @param userId      用户主键
     * @param newPassword 明文
     */
    private void applyPassword(Long userId, String newPassword) {
        AppUser patch = new AppUser();
        patch.setId(userId);
        patch.setPayPassword(passwordEncoder.encode(newPassword));
        patch.setUpdatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        accountMapper.updateById(patch);
    }

    /**
     * 取用户并校验存在。
     *
     * @param userId 用户主键
     * @return 用户实体
     */
    private AppUser requireUser(Long userId) {
        AppUser user = accountMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return user;
    }
}
