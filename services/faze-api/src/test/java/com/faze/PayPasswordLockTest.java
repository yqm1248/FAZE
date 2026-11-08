/*
 * 职责：锚点 `1-A5`「支付密码连错锁定」的自动化验收。
 * 调用方：`mvn test`（需要 FAZE_DB_URL + Redis）。
 * 数据来源：真实 MySQL（隔离用户，每次新建）+ 真实 Redis。
 * 注意：
 *   1. **没有等 30 分钟**：把锁定 key 的 TTL 改成 1 秒来模拟到期（`redis.expire`）。
 *      这正是这条锚点用「后端集成测试」而不是 E2E 才能做的原因——E2E 里只能真等，
 *      或者改前端代码造假。
 *   2. 第 5 次失败必须**直接**回「已锁定 + 剩余秒数」，不是回「还可尝试 0 次」。
 *      这是 2026-11-05 顺带修掉的实现偏差（原实现要到第 6 次才回锁定）。
 *   3. 每个用例都新建一个手机号唯一的隔离账号，用例结束连 app_user 行一起删，
 *      可重复跑、不影响真实数据。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.PayPasswordLockedException;
import com.faze.common.exception.PayPasswordWrongException;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.service.PayPasswordService;
import com.faze.modules.account.service.SmsCodeService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 支付密码锁定测试。
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class PayPasswordLockTest {

    /** 支付密码正确值。 */
    private static final String RIGHT = "123456";
    /** 支付密码错误值。 */
    private static final String WRONG = "000000";
    /** 重置后使用的新密码。 */
    private static final String NEW_PWD = "654321";
    /** dev 固定验证码（sys_config.sms_fixed_code_enabled=true 时生效）。 */
    private static final String FIXED_SMS_CODE = "123456";

    /** Redis key 前缀，与 PayPasswordService 中的常量保持一致（故意复制：契约要能被测试钉住）。 */
    private static final String KEY_FAIL = "faze:paypwd:fail:";
    private static final String KEY_LOCKED = "faze:paypwd:locked:";

    @Autowired
    private PayPasswordService payPasswordService;
    @Autowired
    private SmsCodeService smsCodeService;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private StringRedisTemplate redis;

    private Long userId;
    private String phone;

    /** 建一个干净的隔离账号并设好支付密码。 */
    @BeforeEach
    void setUp() {
        // 手机号按时间取尾号，避免与历史残留冲突（残留会被 tearDown 删掉，这里只是兜底）
        phone = "199" + String.format("%08d", System.nanoTime() % 100_000_000L);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AppUser user = new AppUser();
        user.setPhone(phone);
        user.setNickname("支付密码测试");
        user.setRole("user");
        user.setRealnameStatus("none");
        user.setIsMinor(false);
        user.setStatus("active");
        user.setTokenVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        accountMapper.insert(user);
        userId = user.getId();
        payPasswordService.set(userId, RIGHT);
        clearRedis();
    }

    /** 清账号与 Redis（两个 key 都是「测试留下的状态」，必须清干净才能重复跑）。 */
    @AfterEach
    void tearDown() {
        clearRedis();
        if (phone != null) {
            accountMapper.delete(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getPhone, phone));
        }
    }

    /**
     * 第 1~4 次错提示剩余次数；第 5 次错直接锁定并给出剩余秒数。
     */
    @Test
    @DisplayName("1-A5：前 4 次错提示剩余次数，第 5 次错直接锁定并给剩余时间")
    void locksOnFifthFailure() {
        for (int i = 1; i <= 4; i++) {
            int expectedRemaining = 5 - i;
            PayPasswordWrongException ex = assertThrows(PayPasswordWrongException.class,
                    () -> payPasswordService.verify(userId, WRONG),
                    "第 " + i + " 次错误应抛「密码错」");
            assertEquals(expectedRemaining, ex.getRemainingAttempts(),
                    "第 " + i + " 次错后剩余次数");
        }
        assertNull(redis.opsForValue().get(KEY_LOCKED + userId), "前 4 次错不应锁定");

        PayPasswordLockedException locked = assertThrows(PayPasswordLockedException.class,
                () -> payPasswordService.verify(userId, WRONG),
                "第 5 次错必须直接回锁定，而不是「还可尝试 0 次」");
        // 默认 30 分钟 = 1800 秒；给 5 秒容差（执行耗时）
        assertTrue(locked.getRemainingSeconds() > 1_700 && locked.getRemainingSeconds() <= 1_800,
                "剩余秒数应接近 1800，实际=" + locked.getRemainingSeconds());

        Long ttl = redis.getExpire(KEY_LOCKED + userId);
        assertTrue(ttl != null && ttl > 1_700, "锁定 key 必须有 TTL（没有 TTL 会永久锁死），实际 ttl=" + ttl);
        assertEquals(Boolean.FALSE, redis.hasKey(KEY_FAIL + userId), "锁定时应清掉失败计数");
    }

    /**
     * 锁定期内即使输入正确密码也必须拒绝。
     */
    @Test
    @DisplayName("1-A5：锁定期内输入正确密码也拒绝")
    void correctPasswordRejectedWhileLocked() {
        lockIt();
        PayPasswordLockedException ex = assertThrows(PayPasswordLockedException.class,
                () -> payPasswordService.verify(userId, RIGHT),
                "锁定期内正确密码也必须被拒（否则锁定形同虚设）");
        assertTrue(ex.getRemainingSeconds() > 0);
    }

    /**
     * 锁定到期后自动解锁（把 TTL 改成 1 秒模拟 30 分钟到期）。
     */
    @Test
    @DisplayName("1-A5：锁定 TTL 到期后自动解锁（1 秒模拟 30 分钟）")
    void autoUnlocksAfterTtl() throws Exception {
        lockIt();
        assertThrows(PayPasswordLockedException.class,
                () -> payPasswordService.verify(userId, RIGHT), "锁定中");

        // 模拟「30 分钟到了」：只改 TTL，不改任何业务状态
        redis.expire(KEY_LOCKED + userId, Duration.ofSeconds(1));
        Thread.sleep(1_300L);

        payPasswordService.verify(userId, RIGHT); // 不抛即通过
        assertEquals(Boolean.FALSE, redis.hasKey(KEY_FAIL + userId), "解锁后计数也应清空");
    }

    /**
     * 走短信重置后，失败计数清零。
     */
    @Test
    @DisplayName("1-A5：重置支付密码后失败计数清零")
    void resetClearsFailureCounter() {
        // 先错 3 次（剩余 2）
        for (int i = 0; i < 3; i++) {
            assertThrows(PayPasswordWrongException.class, () -> payPasswordService.verify(userId, WRONG));
        }
        assertEquals(3L, Long.parseLong(String.valueOf(redis.opsForValue().get(KEY_FAIL + userId))));

        smsCodeService.send(phone, "RESET_PASSWORD", "127.0.0.1");
        payPasswordService.reset(userId, FIXED_SMS_CODE, NEW_PWD);

        // 重置后：旧密码失效、新密码可用、计数归零
        payPasswordService.verify(userId, NEW_PWD);
        PayPasswordWrongException again = assertThrows(PayPasswordWrongException.class,
                () -> payPasswordService.verify(userId, WRONG));
        assertEquals(4, again.getRemainingAttempts(), "重置应把计数清零，本次错后剩余 4 次");
    }

    /**
     * 未设置支付密码时校验应报 401006（状态非法），而不是 401007。
     */
    @Test
    @DisplayName("1-A5 边界：未设置时校验报状态非法")
    void verifyWithoutSetupFails() {
        AppUser fresh = new AppUser();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String freshPhone = phone + "0";
        fresh.setPhone(freshPhone.length() > 20 ? freshPhone.substring(0, 20) : freshPhone);
        fresh.setNickname("未设置支付密码");
        fresh.setRole("user");
        fresh.setRealnameStatus("none");
        fresh.setIsMinor(false);
        fresh.setStatus("active");
        fresh.setTokenVersion(0);
        fresh.setCreatedAt(now);
        fresh.setUpdatedAt(now);
        accountMapper.insert(fresh);
        try {
            var ex = org.junit.jupiter.api.Assertions.assertThrows(
                    com.faze.common.exception.BusinessException.class,
                    () -> payPasswordService.verify(fresh.getId(), RIGHT));
            assertEquals(com.faze.common.exception.ErrorCode.COMMON_ILLEGAL_STATE, ex.getErrorCode());
        } finally {
            accountMapper.delete(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getId, fresh.getId()));
        }
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    /** 把账号打到锁定态（错满 5 次）。 */
    private void lockIt() {
        for (int i = 0; i < 4; i++) {
            assertThrows(PayPasswordWrongException.class, () -> payPasswordService.verify(userId, WRONG));
        }
        assertThrows(PayPasswordLockedException.class, () -> payPasswordService.verify(userId, WRONG));
    }

    private void clearRedis() {
        if (userId != null) {
            redis.delete(KEY_FAIL + userId);
            redis.delete(KEY_LOCKED + userId);
        }
    }

    private static Object assertNull(Object actual, String message) {
        org.junit.jupiter.api.Assertions.assertNull(actual, message);
        return actual;
    }
}
