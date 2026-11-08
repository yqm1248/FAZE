/*
 * 职责：G-9（注销前置余额口径）的自动化验收——注销时余额判定必须同时看账本与缓存。
 * 调用方：`mvn test`（需要 FAZE_DB_URL + Redis）。
 * 数据来源：真实 MySQL（隔离用户 id=9001~9003）+ 真实 Redis（吊销 refresh）。
 * 注意：
 *   1. 这条测试的存在理由：`AccountSecurityService.cancelAccount` 原先只读
 *      `user_balance` 缓存口径。缓存是派生值，某笔流水写了但缓存更新失败时缓存会偏小，
 *      用户就能带着余额注销，钱再也退不出去（资金悬空）。
 *   2. **必须制造「缓存 ≠ 流水」** 才有意义：两边一致时无论实现对错都会通过。
 *      本类两个方向各测一次——缓存偏小（流水大）、缓存偏大（流水小）。
 *   3. 注销会把 phone 改成占位值释放唯一键，所以清理不能靠手机号，只能靠固定 id。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.service.AccountSecurityService;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * G-9：注销前置余额校验必须偏保守（账本与缓存取较大值）。
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class CancelAccountBalanceTest {

    /** 隔离用户 id，避开真实数据（app_user 自增段目前远低于此）。 */
    private static final long USER_ID_CACHE_LOW = 9001L;
    private static final long USER_ID_CACHE_HIGH = 9002L;
    private static final long USER_ID_CLEAN = 9003L;

    /** 流水里真实存在的余额（分）。 */
    private static final long FLOW_CENTS = 10_000L;

    @Autowired
    private AccountSecurityService accountSecurityService;
    @Autowired
    private AccountMapper accountMapper;
    @Autowired
    private UserBalanceMapper userBalanceMapper;
    @Autowired
    private BalanceFlowMapper balanceFlowMapper;

    /** 清掉三个隔离用户的所有痕迹。 */
    @AfterEach
    void cleanUp() {
        for (long id : new long[] {USER_ID_CACHE_LOW, USER_ID_CACHE_HIGH, USER_ID_CLEAN}) {
            balanceFlowMapper.delete(Wrappers.<BalanceFlow>lambdaQuery()
                    .eq(BalanceFlow::getOwnerType, "user")
                    .eq(BalanceFlow::getOwnerId, id));
            userBalanceMapper.delete(Wrappers.<UserBalance>lambdaQuery()
                    .eq(UserBalance::getUserId, id));
            accountMapper.deleteById(id);
        }
    }

    /**
     * 缓存偏小：`user_balance` 说 0，流水账本说还有 100 元 —— 必须拒绝注销。
     *
     * <p>这是 G-9 的原始现场：只看缓存的话这里会放行，用户带着 100 元注销，
     * 钱留在库里没有归属，退款流程无处落地。</p>
     */
    @Test
    @DisplayName("G-9：缓存=0 但流水有余额时，注销必须被拒（否则资金悬空）")
    void cancelRejectedWhenLedgerHasBalanceButCacheIsZero() {
        createUser(USER_ID_CACHE_LOW, "19999000001");
        createCache(USER_ID_CACHE_LOW, 0L);
        createFlow(USER_ID_CACHE_LOW, FLOW_CENTS);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountSecurityService.cancelAccount(USER_ID_CACHE_LOW),
                "缓存与流水取较大值后仍有余额，注销必须被拒");
        assertEquals(ErrorCode.COMMON_ILLEGAL_STATE, ex.getErrorCode());

        AppUser after = accountMapper.selectById(USER_ID_CACHE_LOW);
        assertEquals("active", after.getStatus(), "被拒后账号状态不能被动过");
    }

    /**
     * 缓存偏大：流水账本 0，`user_balance` 说还有 100 元 —— 同样拒绝。
     *
     * <p>方向相反但道理相同：两个口径任一非零都意味着「可能有还没退的钱」，
     * 注销不可逆，只能按保守的一侧判。</p>
     */
    @Test
    @DisplayName("G-9：流水=0 但缓存有余额时，注销同样必须被拒")
    void cancelRejectedWhenCacheHasBalanceButLedgerIsZero() {
        createUser(USER_ID_CACHE_HIGH, "19999000002");
        createCache(USER_ID_CACHE_HIGH, FLOW_CENTS);
        // 故意不建任何流水：SUM(delta_cents) 为 NULL，实现必须把它当 0 而不是 NPE

        BusinessException ex = assertThrows(BusinessException.class,
                () -> accountSecurityService.cancelAccount(USER_ID_CACHE_HIGH));
        assertEquals(ErrorCode.COMMON_ILLEGAL_STATE, ex.getErrorCode());
        assertEquals("active", accountMapper.selectById(USER_ID_CACHE_HIGH).getStatus());
    }

    /**
     * 两个口径都为 0 时才允许注销，并确认副作用落地（状态、手机号占位、token_version+1）。
     */
    @Test
    @DisplayName("G-9 反向：账本与缓存都为 0 时注销成功且副作用完整")
    void cancelSucceedsWhenBothZero() {
        createUser(USER_ID_CLEAN, "19999000003");
        createCache(USER_ID_CLEAN, 0L);

        accountSecurityService.cancelAccount(USER_ID_CLEAN);

        AppUser after = accountMapper.selectById(USER_ID_CLEAN);
        assertEquals("cancelled", after.getStatus());
        assertTrue(after.getTokenVersion() >= 1, "注销必须 token_version+1 踢掉旧 token");
        assertEquals("19999000003", after.getOriginalPhone(), "原手机号必须留存供审计");
        assertTrue(!("19999000003".equals(after.getPhone())), "手机号必须改写成占位值释放唯一键");
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    private void createUser(long id, String phone) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AppUser u = new AppUser();
        u.setId(id);
        u.setPhone(phone);
        u.setRole("user");
        u.setLang("zh-Hans");
        u.setRealnameStatus("none");
        u.setIsMinor(false);
        u.setGender(0);
        u.setStatus("active");
        u.setTokenVersion(0);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        accountMapper.insert(u);
    }

    private void createCache(long userId, long balanceCents) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserBalance cache = new UserBalance();
        cache.setUserId(userId);
        cache.setBalanceCents(balanceCents);
        cache.setFrozenCents(0L);
        cache.setVersion(0);
        cache.setCreatedAt(now);
        cache.setUpdatedAt(now);
        userBalanceMapper.insert(cache);
    }

    private void createFlow(long userId, long deltaCents) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        BalanceFlow flow = new BalanceFlow();
        flow.setOwnerType("user");
        flow.setOwnerId(userId);
        flow.setBizType("recharge");
        flow.setBizId(0L);
        flow.setDeltaCents(deltaCents);
        flow.setBalanceAfter(deltaCents);
        flow.setRemark("G-9 测试");
        flow.setCreatedAt(now);
        balanceFlowMapper.insert(flow);
    }
}
