/*
 * 职责：充值回调幂等的**集成测试**（第 6 段 C4）——串行重放 3 次 + JVM 内 2 线程并发，验证「只入账一次」。
 * 调用方：mvn test（@SpringBootTest，需要真实 MySQL；无 FAZE_DB_URL 整类跳过）。
 * 数据来源：真实库（隔离用户 id=9997）。测试自己造 recharge_record / user_balance，跑完按前缀清理。
 * 注意：
 *   1. **为什么必须是真库、真线程**（C4 第 4 条）：`RechargeSettleService` 的幂等靠
 *      「CAS `WHERE status='pending'` 影响 0 行」+ `uk_recharge_channel_txn` / `uk_flow_biz`
 *      两把唯一键，这三条都是**数据库行为**。Mockito 把 Mapper 换掉之后，这部分根本没被执行，
 *      单测全绿也证明不了并发安全（`TaskQueueIntegrationTest` 的教训同源）。
 *   2. **2 线程真的同时起跑**：用 `CountDownLatch` 做发令枪，两个线程各自开事务，
 *      而不是「先后调两次」——后者测的是串行重放，不是并发。
 *   3. **断言的是「量」不是「返回码」**：`recharge_record` 成功行 = 1、`balance_flow`
 *      recharge 行 = 1、余额增量 = 1 份额（C4「算做完」原文）。返回码只有一个 SETTLED 也算量。
 *   4. 测试数据全部带 `ITC4-` 前缀、隔离用户 9997，`@AfterEach` 按前缀删除，
 *      不依赖也不污染种子数据。
 *   5. `AuditContext.mark` 未 init 时是 no-op（看 AuditContext 源码），所以测试线程不需要初始化审计上下文。
 */
package com.faze.modules.trade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.mapper.TaskQueueMapper;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 充值幂等证据生成器（`6-A2` 的证据来源）。
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class RechargeIdempotentTest {

    /** 隔离测试用户，避开真实数据（与 BalanceSourceOfTruthTest 的 9998 区分开）。 */
    private static final Long TEST_USER_ID = 9997L;

    /** 测试单号前缀：清理与人工排查都靠它。 */
    private static final String ORDER_PREFIX = "ITC4-";

    /** 每次重放的金额：100 元，与 `6-A2` 的走查口径一致。 */
    private static final long AMOUNT_CENTS = 10_000L;

    @Autowired
    private RechargeSettleService rechargeSettleService;

    @Autowired
    private RechargeRecordMapper rechargeRecordMapper;

    @Autowired
    private BalanceFlowMapper balanceFlowMapper;

    @Autowired
    private UserBalanceMapper userBalanceMapper;

    @Autowired
    private TaskQueueMapper taskQueueMapper;

    /**
     * 每个用例前清干净，并建一行 0 余额（断言「余额增量 = 1 份额」需要一个明确的起点）。
     */
    @BeforeEach
    void setUp() {
        cleanUp();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserBalance cache = new UserBalance();
        cache.setUserId(TEST_USER_ID);
        cache.setBalanceCents(0L);
        cache.setFrozenCents(0L);
        cache.setVersion(0);
        cache.setCreatedAt(now);
        cache.setUpdatedAt(now);
        userBalanceMapper.insert(cache);
    }

    /**
     * 按前缀/用户清理测试数据；不删真实数据。
     */
    @AfterEach
    void cleanUp() {
        rechargeRecordMapper.delete(Wrappers.<RechargeRecord>lambdaQuery()
                .likeRight(RechargeRecord::getOrderNo, ORDER_PREFIX));
        balanceFlowMapper.delete(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerId, TEST_USER_ID)
                .eq(BalanceFlow::getBizType, RechargeSettleService.BIZ_TYPE_RECHARGE));
        userBalanceMapper.delete(Wrappers.<UserBalance>lambdaQuery()
                .eq(UserBalance::getUserId, TEST_USER_ID));
        taskQueueMapper.delete(Wrappers.<TaskQueue>lambdaQuery()
                .likeRight(TaskQueue::getBizNo, ORDER_PREFIX));
    }

    @Test
    @DisplayName("串行重放 3 次：recharge_record 成功行=1、balance_flow=1、余额+1 份额（6-A2 串行部分）")
    void serialReplayThreeTimes() {
        String orderNo = ORDER_PREFIX + "S" + System.currentTimeMillis();
        String channelTxnId = "TXNC4S" + System.currentTimeMillis();
        insertPending(orderNo);
        dump("串行重放前", orderNo, channelTxnId);

        List<RechargeSettleService.Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            outcomes.add(rechargeSettleService.settleByCallback(
                    orderNo, channelTxnId, AMOUNT_CENTS, LocalDateTime.now(ZoneOffset.UTC), "{\"seq\":" + i + "}"));
        }
        dump("串行重放后", orderNo, channelTxnId);

        assertEquals(RechargeSettleService.Outcome.SETTLED, outcomes.get(0), "第 1 次应真正入账");
        assertEquals(RechargeSettleService.Outcome.ALREADY_SETTLED, outcomes.get(1), "第 2 次应幂等返回");
        assertEquals(RechargeSettleService.Outcome.ALREADY_SETTLED, outcomes.get(2), "第 3 次应幂等返回");
        assertOnlySettledOnce(orderNo, channelTxnId);
    }

    @Test
    @DisplayName("JVM 内 2 线程并发打同一回调：一个成功、一个幂等返回，只入账一次（6-A2 并发双打）")
    void concurrentTwoThreads() throws Exception {
        String orderNo = ORDER_PREFIX + "C" + System.currentTimeMillis();
        String channelTxnId = "TXNC4C" + System.currentTimeMillis();
        insertPending(orderNo);
        dump("并发双打前", orderNo, channelTxnId);

        // 发令枪：两个线程同时起跑，各自开事务
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<RechargeSettleService.Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                final int seq = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return rechargeSettleService.settleByCallback(orderNo, channelTxnId, AMOUNT_CENTS,
                            LocalDateTime.now(ZoneOffset.UTC), "{\"concurrent\":" + seq + "}");
                }));
            }
            start.countDown();
            List<RechargeSettleService.Outcome> outcomes = new ArrayList<>();
            for (Future<RechargeSettleService.Outcome> future : futures) {
                outcomes.add(future.get(15, TimeUnit.SECONDS));
            }
            dump("并发双打后", orderNo, channelTxnId);

            long settled = outcomes.stream()
                    .filter(o -> o == RechargeSettleService.Outcome.SETTLED).count();
            long idempotent = outcomes.stream()
                    .filter(o -> o == RechargeSettleService.Outcome.ALREADY_SETTLED).count();
            assertEquals(1L, settled, "并发下应恰好一个线程真正入账，实际=" + outcomes);
            assertEquals(1L, idempotent, "另一个线程应幂等返回（不报错），实际=" + outcomes);
            assertOnlySettledOnce(orderNo, channelTxnId);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 断言「只入账一次」的三个量（C4「算做完」原文：成功行=1、流水=1、余额增量=1 份额）。
     *
     * @param orderNo      平台单号
     * @param channelTxnId 渠道交易号（验证 uk_recharge_channel_txn 生效）
     */
    private void assertOnlySettledOnce(String orderNo, String channelTxnId) {
        RechargeRecord record = rechargeRecordMapper.selectOne(Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getOrderNo, orderNo));
        assertEquals(RechargeStatus.SUCCESS.getCode(), record.getStatus(), "单据状态应为 success");
        long successRows = rechargeRecordMapper.selectCount(Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getChannelTxnId, channelTxnId));
        assertEquals(1L, successRows, "同一 channel_txn_id 只允许 1 行（uk_recharge_channel_txn）");
        long flowRows = balanceFlowMapper.selectCount(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerId, TEST_USER_ID)
                .eq(BalanceFlow::getBizType, RechargeSettleService.BIZ_TYPE_RECHARGE)
                .eq(BalanceFlow::getBizId, record.getId()));
        assertEquals(1L, flowRows, "该单只允许 1 条 recharge 流水（uk_flow_biz）");
        Long flowSum = balanceFlowMapper.selectList(Wrappers.<BalanceFlow>lambdaQuery()
                        .eq(BalanceFlow::getOwnerId, TEST_USER_ID)
                        .eq(BalanceFlow::getBizType, RechargeSettleService.BIZ_TYPE_RECHARGE))
                .stream().map(BalanceFlow::getDeltaCents).reduce(0L, Long::sum);
        assertEquals(AMOUNT_CENTS, flowSum, "流水净额应为 1 份额（不是 2 份、3 份）");
        UserBalance cache = userBalanceMapper.selectByUserIdForUpdate(TEST_USER_ID);
        assertEquals(AMOUNT_CENTS, cache.getBalanceCents(), "余额增量应为 1 份额");
        System.out.println("[C4] 断言通过 orderNo=" + orderNo + " successRows=" + successRows
                + " flowRows=" + flowRows + " flowSum=" + flowSum
                + " balance=" + cache.getBalanceCents());
    }

    /**
     * 造一张 pending 充值单。
     *
     * @param orderNo 平台单号
     */
    private void insertPending(String orderNo) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RechargeRecord record = new RechargeRecord();
        record.setUserId(TEST_USER_ID);
        record.setOrderNo(orderNo);
        record.setChannel(PayChannel.MOCK.getCode());
        record.setAmountCents(AMOUNT_CENTS);
        record.setStatus(RechargeStatus.PENDING.getCode());
        record.setExpireAt(now.plusMinutes(30));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        rechargeRecordMapper.insert(record);
    }

    /**
     * 打印三组对照数据（证据文件 `C4-idempotent.txt` 的内容来源）。
     *
     * @param phase        阶段说明
     * @param orderNo      平台单号
     * @param channelTxnId 渠道交易号
     */
    private void dump(String phase, String orderNo, String channelTxnId) {
        RechargeRecord record = rechargeRecordMapper.selectOne(Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getOrderNo, orderNo));
        long txnRows = rechargeRecordMapper.selectCount(Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getChannelTxnId, channelTxnId));
        List<BalanceFlow> flows = balanceFlowMapper.selectList(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerId, TEST_USER_ID)
                .eq(BalanceFlow::getBizType, RechargeSettleService.BIZ_TYPE_RECHARGE));
        long flowSum = flows.stream().map(BalanceFlow::getDeltaCents).reduce(0L, Long::sum);
        UserBalance cache = userBalanceMapper.selectByUserIdForUpdate(TEST_USER_ID);
        System.out.println("[C4] " + phase + " | orderNo=" + orderNo
                + " status=" + (record == null ? "无" : record.getStatus())
                + " | channel_txn_id 行数=" + txnRows
                + " | balance_flow 行数=" + flows.size()
                + " | SUM(delta_cents)=" + flowSum
                + " | user_balance.balance_cents=" + (cache == null ? "无" : cache.getBalanceCents()));
    }
}
