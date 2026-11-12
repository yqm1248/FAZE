/*
 * 职责：锚点 `1-A6`「余额不由字段决定」的自动化验收。
 * 调用方：`mvn test`（需要 FAZE_DB_URL + Redis）。
 * 数据来源：真实 MySQL（隔离用户 id=9998）+ 真实 Redis。
 * 注意：
 *   1. **这条锚点最容易假通过**：如果「缓存值恰好等于流水汇总」，无论实现读哪个都会过。
 *      所以必须**先制造「缓存 ≠ 流水」** 再断言——本类就是照这个顺序写的。
 *   2. 只断言 `BalanceService.getBalance()`（`GET /app/wallet/balance` 的唯一实现），
 *      不断言 `BalanceQueryService`：后者按设计读缓存，只服务「有没有余额」这种粗判断，
 *      其接口注释已写明「精确金额不许用它」。把两者混为一谈会让这条测试失去意义。
 *   3. **不恢复缓存**：断言完直接清理测试用户，避免把一个 999999 元的脏缓存留在库里。
 */
package com.faze;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.service.BalanceService;
import com.faze.modules.trade.vo.BalanceVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 余额唯一权威（流水汇总）验收测试。
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(
        named = "FAZE_DB_URL",
        matches = ".+",
        disabledReason = "缺少 FAZE_DB_URL：需要真实 MySQL，无库环境整类跳过")
class BalanceSourceOfTruthTest {

    /** 隔离测试用户，避开真实数据。 */
    private static final Long TEST_USER_ID = 9998L;
    /** 流水真实净额：只有这一条 +10000。 */
    private static final long FLOW_SUM = 10_000L;
    /** 篡改后的缓存值（故意与流水差一个数量级）。 */
    private static final long TAMPERED = 99_999_900L;

    @Autowired
    private BalanceService balanceService;
    @Autowired
    private UserBalanceMapper userBalanceMapper;
    @Autowired
    private BalanceFlowMapper balanceFlowMapper;

    /** 建缓存行（值 = 流水汇总）+ 一条流水。 */
    @BeforeEach
    void setUp() {
        cleanUp();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        UserBalance cache = new UserBalance();
        cache.setUserId(TEST_USER_ID);
        cache.setAvailableCents(FLOW_SUM);
        cache.setFrozenCents(0L);
        cache.setVersion(0);
        cache.setCreatedAt(now);
        cache.setUpdatedAt(now);
        userBalanceMapper.insert(cache);

        BalanceFlow flow = new BalanceFlow();
        flow.setOwnerType("user");
        flow.setOwnerId(TEST_USER_ID);
        flow.setBizType("recharge");
        flow.setBizId(9_998L);
        flow.setDeltaCents(FLOW_SUM);
        flow.setBalanceAfter(FLOW_SUM);
        flow.setRemark("1-A6 测试初始化");
        flow.setCreatedAt(now);
        balanceFlowMapper.insert(flow);
    }

    /** 清测试数据。 */
    @AfterEach
    void cleanUp() {
        balanceFlowMapper.delete(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerType, "user")
                .eq(BalanceFlow::getOwnerId, TEST_USER_ID));
        userBalanceMapper.delete(Wrappers.<UserBalance>lambdaQuery()
                .eq(UserBalance::getUserId, TEST_USER_ID));
    }

    /**
     * 直接篡改缓存字段后，接口返回值必须仍是流水汇总。
     *
     * <p>这就是锚点 `1-A6` 的现场：若实现读 `user_balance.available_cents`，
     * 这里会返回 999999.00 元。</p>
     */
    @Test
    @DisplayName("1-A6：直接改 user_balance.available_cents 后，余额接口仍返回流水汇总")
    void balanceComesFromFlowsNotCache() {
        BalanceVO before = balanceService.getBalance(TEST_USER_ID);
        assertEquals(FLOW_SUM, before.getTotalCents(), "篡改前：缓存与流水一致");

        // 制造「缓存 ≠ 流水」
        UserBalance patch = new UserBalance();
        patch.setId(cacheRowId());
        patch.setAvailableCents(TAMPERED);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userBalanceMapper.updateById(patch);

        BalanceVO after = balanceService.getBalance(TEST_USER_ID);
        assertEquals(FLOW_SUM, after.getTotalCents(),
                "锚点 1-A6 失败：余额读了缓存字段而不是 balance_flow 汇总");
        assertEquals(FLOW_SUM - after.getFrozenCents(), after.getAvailableCents(),
                "可用额必须 = 流水汇总 − 冻结");
    }

    /**
     * `recalcBalance` 必须把缓存纠正回流水汇总（对账与修复路径）。
     */
    @Test
    @DisplayName("1-A6 配套：recalcBalance 能把被篡改的缓存纠回流水汇总")
    void recalcFixesCache() {
        UserBalance patch = new UserBalance();
        patch.setId(cacheRowId());
        patch.setAvailableCents(TAMPERED);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userBalanceMapper.updateById(patch);

        long fixed = balanceService.recalcBalance(TEST_USER_ID);
        assertEquals(FLOW_SUM, fixed, "recalc 应返回纠正后的可用额");
        assertEquals(FLOW_SUM, cacheRow().getAvailableCents(), "缓存应被纠回流水汇总");
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    private Long cacheRowId() {
        UserBalance row = cacheRow();
        return row == null ? null : row.getId();
    }

    private UserBalance cacheRow() {
        return userBalanceMapper.selectOne(Wrappers.<UserBalance>lambdaQuery()
                .eq(UserBalance::getUserId, TEST_USER_ID));
    }
}
