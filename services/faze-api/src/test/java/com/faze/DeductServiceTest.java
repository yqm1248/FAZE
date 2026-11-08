/*
 * 职责：扣费引擎的单元测试（第 1 段 D3，锚点 1-A8 的核心）。
 * 调用方：`mvn test`（需要 FAZE_DB_URL）。
 * 数据来源：真实 MySQL（用 userId=9999 的隔离测试数据，前后清理）。
 * 注意：
 *   1. **不用 @Transactional 包测试方法**：并发用例要在多线程里各自提交事务，
 *      测试事务会把所有线程包进同一个事务，测不出并发行为。
 *   2. 用固定的 userId=9999 + 明确的 bizId 区间，避免与真实数据互相干扰；
 *      每个用例前后都清理，保证可重复跑。
 *   3. 断言重点是「守恒」与「幂等」：流水条数、余额只减一次、balance_after 正确。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.service.DeductService;
import com.faze.modules.trade.vo.DeductResult;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 扣费引擎测试。
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class DeductServiceTest {

    /** 隔离测试用户，避免与真实数据混淆。 */
    private static final Long TEST_USER_ID = 9999L;
    /** 测试业务类型。 */
    private static final String BIZ_TYPE = "consume";

    @Autowired
    private DeductService deductService;
    @Autowired
    private UserBalanceMapper userBalanceMapper;
    @Autowired
    private BalanceFlowMapper balanceFlowMapper;

    /** 每个用例前：清空测试用户数据，建余额行 + 充值 10000 分。 */
    @BeforeEach
    void setUp() {
        cleanUp();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserBalance balance = new UserBalance();
        balance.setUserId(TEST_USER_ID);
        balance.setBalanceCents(10_000L);
        balance.setFrozenCents(0L);
        balance.setVersion(0);
        balance.setCreatedAt(now);
        balance.setUpdatedAt(now);
        userBalanceMapper.insert(balance);

        BalanceFlow seed = new BalanceFlow();
        seed.setOwnerType("user");
        seed.setOwnerId(TEST_USER_ID);
        seed.setBizType("recharge");
        seed.setBizId(1L);
        seed.setDeltaCents(10_000L);
        seed.setBalanceAfter(10_000L);
        seed.setRemark("测试初始化");
        seed.setCreatedAt(now);
        balanceFlowMapper.insert(seed);
    }

    /** 每个用例后：清理测试数据。 */
    @AfterEach
    void cleanUp() {
        balanceFlowMapper.delete(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerType, "user")
                .eq(BalanceFlow::getOwnerId, TEST_USER_ID));
        userBalanceMapper.delete(Wrappers.<UserBalance>lambdaQuery()
                .eq(UserBalance::getUserId, TEST_USER_ID));
    }

    /**
     * 同一业务键调 3 次，只产生 1 条流水、余额只减一次。
     */
    @Test
    @DisplayName("幂等：同 biz 调 3 次只有 1 条流水，余额只减一次")
    void idempotentOnSameBiz() {
        DeductResult first = deductService.deduct("user", TEST_USER_ID, BIZ_TYPE, 100L, 1_000L, "测试扣费");
        assertFalse(first.duplicated());
        assertEquals(9_000L, first.balanceAfterCents());

        DeductResult second = deductService.deduct("user", TEST_USER_ID, BIZ_TYPE, 100L, 1_000L, "测试扣费");
        assertTrue(second.duplicated());
        assertEquals(9_000L, second.balanceAfterCents());

        DeductResult third = deductService.deduct("user", TEST_USER_ID, BIZ_TYPE, 100L, 1_000L, "测试扣费");
        assertTrue(third.duplicated());

        long flowCount = countTestFlows();
        assertEquals(2L, flowCount, "初始化 1 条 + 扣费 1 条");
        assertEquals(9_000L, currentBalance(), "余额只应减一次");
    }

    /**
     * 并发 10 次同一业务键：只有 1 条扣费流水。
     */
    @Test
    @DisplayName("并发：10 线程同一 biz 只产生 1 条扣费流水")
    void concurrentTenTimes() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DeductResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return deductService.deduct("user", TEST_USER_ID, BIZ_TYPE, 200L, 1_000L, "并发测试");
            }));
        }
        start.countDown();
        int success = 0;
        for (Future<DeductResult> future : futures) {
            DeductResult result = future.get();
            if (result != null) {
                success++;
            }
        }
        pool.shutdown();
        assertEquals(threads, success, "10 个调用都应正常返回（幂等命中也算成功）");
        assertEquals(2L, countTestFlows(), "初始化 1 条 + 扣费 1 条");
        assertEquals(9_000L, currentBalance(), "余额只应减一次");
    }

    /**
     * 余额不足：抛 405001，不产生流水，余额不变。
     */
    @Test
    @DisplayName("余额不足：抛 405001 且不产生流水")
    void insufficientBalance() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deductService.deduct("user", TEST_USER_ID, BIZ_TYPE, 300L, 10_001L, "余额不足"));
        assertEquals(ErrorCode.TRADE_BALANCE_INSUFFICIENT, ex.getErrorCode());
        assertEquals(1L, countTestFlows(), "只有初始化那一条");
        assertEquals(10_000L, currentBalance(), "余额不应变化");
    }

    /**
     * 金额非法：0 与负数都拒绝。
     */
    @Test
    @DisplayName("金额非法：0 / 负数抛 405006")
    void rejectsInvalidAmount() {
        BusinessException zero = assertThrows(BusinessException.class,
                () -> deductService.deduct("user", TEST_USER_ID, BIZ_TYPE, 400L, 0L, "零金额"));
        assertEquals(ErrorCode.TRADE_AMOUNT_INVALID, zero.getErrorCode());
        BusinessException negative = assertThrows(BusinessException.class,
                () -> deductService.deduct("user", TEST_USER_ID, BIZ_TYPE, 401L, -100L, "负数"));
        assertEquals(ErrorCode.TRADE_AMOUNT_INVALID, negative.getErrorCode());
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    private long countTestFlows() {
        return balanceFlowMapper.selectCount(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerType, "user")
                .eq(BalanceFlow::getOwnerId, TEST_USER_ID));
    }

    private long currentBalance() {
        UserBalance row = userBalanceMapper.selectOne(Wrappers.<UserBalance>lambdaQuery()
                .eq(UserBalance::getUserId, TEST_USER_ID));
        return row == null || row.getBalanceCents() == null ? 0L : row.getBalanceCents();
    }
}
