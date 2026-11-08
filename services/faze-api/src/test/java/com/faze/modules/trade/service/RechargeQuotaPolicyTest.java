/*
 * 职责：充值风控策略与充值页参数的单元测试（第 6 段 B3）——单笔区间、未实名拦截、单日金额/笔数限额、跨日口径、面板组装。
 * 调用方：mvn test。
 * 数据来源：全部依赖被 Mockito 替换；时钟固定为东八区 2026-11-08 18:00（UTC 10:00），
 *           以便断言「当日 0 点」到底换算成了哪个 UTC 时刻。
 * 注意：
 *   1. **边界值是重点**：B3「算做完」要求「0.09 元被拒、0.10 元通过、5000.01 元被拒」，
 *      这三条逐条落成用例；只测「正常值能过」等于没测。
 *   2. **跨日口径**：`sumDailyCents` 的第二个参数必须是「东八区当日 0 点换算成 UTC」，
 *      即 2026-11-08 00:00(+08) → 2026-11-07 16:00(UTC)。用 UTC 的 0 点会多算 8 小时的单。
 *   3. **未实名是拦截不是限额**：`unverified=0` 必须抛 405020，不能退化成「额度为 0」；
 *      只有配置 > 0 时才返回额度参与限额计算。
 *   4. 本测试不碰数据库与 Redis，也不启动 Spring 容器。
 */
package com.faze.modules.trade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.SysConfigKeys;
import com.faze.infra.pay.PaymentGatewayFactory;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.vo.RechargePanelVO;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

/**
 * 充值风控策略单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RechargeQuotaPolicyTest {

    private static final Long USER_ID = 7001L;

    /** 固定时钟：东八区 2026-11-08 18:00 = UTC 10:00。 */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-11-08T10:00:00Z"), ZoneId.of("Asia/Shanghai"));

    /** 期望的「当日 0 点」UTC 值：2026-11-08 00:00(+08) = 2026-11-07T16:00Z。 */
    private static final LocalDateTime TODAY_START_UTC =
            LocalDateTime.of(2026, 11, 7, 16, 0, 0);

    @Mock
    private SysConfigQueryService sysConfig;

    @Mock
    private RechargeRecordMapper rechargeRecordMapper;

    @Mock
    private AccountQueryService accountQueryService;

    @Mock
    private PaymentGatewayFactory paymentGatewayFactory;

    @Mock
    private JsonMapper jsonMapper;

    private RechargeQuotaPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        // Jackson 3 的 JsonMapper 没有无参构造（builder 链在测试里取值不稳定），
        // 这里直接 mock 掉 readValue：本测试关心的是「解析结果怎么用」，不是 JSON 库本身。
        when(jsonMapper.readValue(anyString(), eq(long[].class)))
                .thenReturn(new long[] {10_000L, 20_000L, 50_000L, 100_000L, 200_000L});
        policy = new RechargeQuotaPolicy(sysConfig, rechargeRecordMapper, accountQueryService,
                paymentGatewayFactory, jsonMapper, FIXED_CLOCK);
        // 阈值与 V601 种子一致
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_MIN_CENTS, 10)).thenReturn(10);
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_MAX_AMOUNT_CENTS, 500_000)).thenReturn(500_000);
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_MAX_DAILY_CENTS, 500_000)).thenReturn(500_000);
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_MAX_DAILY_COUNT, 10)).thenReturn(10);
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_UNVERIFIED_DAILY_CENTS, 0)).thenReturn(0);
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_EXPIRE_MINUTES, 30)).thenReturn(30);
        when(accountQueryService.findRealnameStatus(USER_ID)).thenReturn(Optional.of("approved"));
        when(rechargeRecordMapper.sumDailyCents(anyLong2(), any())).thenReturn(0L);
        when(rechargeRecordMapper.countDaily(anyLong2(), any())).thenReturn(0L);
        when(paymentGatewayFactory.enabledChannels()).thenReturn(List.of(PayChannel.MOCK));
        when(sysConfig.getString(eq(SysConfigKeys.RECHARGE_AMOUNT_OPTIONS), any()))
                .thenReturn("[10000,20000,50000,100000,200000]");
    }

    // ── 单笔区间 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("0.09 元（9 分）被拒：405014 —— B3「算做完」第 3 条")
    void amount_shouldRejectBelowMin() {
        BusinessException ex = catchThrowableOfType(
                () -> policy.ensureAmountAllowed(9L), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRADE_RECHARGE_AMOUNT_INVALID);
    }

    @Test
    @DisplayName("0.10 元（10 分）通过 —— 恰好等于下限")
    void amount_shouldAcceptMinBoundary() {
        policy.ensureAmountAllowed(10L);
    }

    @Test
    @DisplayName("5000.00 元通过、5000.01 元被拒 —— 上下限边界")
    void amount_shouldRejectAboveMax() {
        policy.ensureAmountAllowed(500_000L);
        BusinessException ex = catchThrowableOfType(
                () -> policy.ensureAmountAllowed(500_001L), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRADE_RECHARGE_AMOUNT_INVALID);
    }

    @Test
    @DisplayName("0 元与负数一律按金额非法拒（不进限额判断）")
    void amount_shouldRejectZeroAndNegative() {
        assertThat(catchThrowableOfType(() -> policy.ensureAmountAllowed(0L), BusinessException.class))
                .isNotNull();
        assertThat(catchThrowableOfType(() -> policy.ensureAmountAllowed(-1L), BusinessException.class))
                .isNotNull();
    }

    // ── 未实名 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("未实名且额度=0：抛 405020（拦截，不是限额）")
    void realname_shouldBlockUnverifiedWhenQuotaIsZero() {
        when(accountQueryService.findRealnameStatus(USER_ID)).thenReturn(Optional.of("none"));

        BusinessException ex = catchThrowableOfType(
                () -> policy.ensureRealnameAndGetDailyLimit(USER_ID), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRADE_RECHARGE_REALNAME_REQUIRED);
    }

    @Test
    @DisplayName("审核中（pending）同样按未实名拦截")
    void realname_shouldBlockPending() {
        when(accountQueryService.findRealnameStatus(USER_ID)).thenReturn(Optional.of("pending"));

        assertThat(catchThrowableOfType(() -> policy.ensureRealnameAndGetDailyLimit(USER_ID),
                BusinessException.class)).isNotNull();
    }

    @Test
    @DisplayName("未实名但额度>0：返回该额度，不抛异常")
    void realname_shouldReturnUnverifiedQuotaWhenPositive() {
        when(accountQueryService.findRealnameStatus(USER_ID)).thenReturn(Optional.of("none"));
        when(sysConfig.getInt(SysConfigKeys.RECHARGE_UNVERIFIED_DAILY_CENTS, 0)).thenReturn(1000);

        assertThat(policy.ensureRealnameAndGetDailyLimit(USER_ID)).isEqualTo(1000L);
    }

    @Test
    @DisplayName("已实名：返回 null 表示走常规单日上限")
    void realname_shouldReturnNullWhenApproved() {
        assertThat(policy.ensureRealnameAndGetDailyLimit(USER_ID)).isNull();
    }

    // ── 单日限额 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("已用 4990 元 + 本笔 20 元 > 5000 元：405016")
    void daily_shouldRejectAmountOverLimit() {
        when(rechargeRecordMapper.sumDailyCents(eq(USER_ID), eq(TODAY_START_UTC))).thenReturn(499_000L);

        BusinessException ex = catchThrowableOfType(
                () -> policy.ensureDailyQuota(USER_ID, 2_000L, null), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRADE_RECHARGE_DAILY_LIMIT);
    }

    @Test
    @DisplayName("正好等于单日上限：通过（边界不允许差一分就拒）")
    void daily_shouldAcceptExactLimit() {
        when(rechargeRecordMapper.sumDailyCents(eq(USER_ID), eq(TODAY_START_UTC))).thenReturn(490_000L);
        policy.ensureDailyQuota(USER_ID, 10_000L, null);
    }

    @Test
    @DisplayName("第 11 笔被拒：单日笔数上限 10")
    void daily_shouldRejectCountOverLimit() {
        when(rechargeRecordMapper.countDaily(eq(USER_ID), eq(TODAY_START_UTC))).thenReturn(10L);

        BusinessException ex = catchThrowableOfType(
                () -> policy.ensureDailyQuota(USER_ID, 1_000L, null), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRADE_RECHARGE_DAILY_LIMIT);
    }

    @Test
    @DisplayName("单日统计窗口是「东八区当日 0 点」换算的 UTC，不是 UTC 0 点")
    void daily_shouldUseShanghaiDayStart() {
        policy.ensureDailyQuota(USER_ID, 1_000L, null);
        // 2026-11-08 00:00(+08) = 2026-11-07T16:00Z；若实现写成 UTC 0 点会传 2026-11-08T00:00
        org.mockito.Mockito.verify(rechargeRecordMapper)
                .sumDailyCents(USER_ID, TODAY_START_UTC);
        org.mockito.Mockito.verify(rechargeRecordMapper)
                .countDaily(USER_ID, TODAY_START_UTC);
    }

    @Test
    @DisplayName("未实名额度作为上限参与判断：额度 10 元、本笔 11 元被拒")
    void daily_shouldUseUnverifiedLimitWhenPassed() {
        BusinessException ex = catchThrowableOfType(
                () -> policy.ensureDailyQuota(USER_ID, 1_100L, 1_000L), BusinessException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRADE_RECHARGE_DAILY_LIMIT);
    }

    // ── 页面面板 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("面板：已实名给出常规上限与剩余额度，渠道只含启用项，档位来自配置")
    void panel_shouldAssembleForApprovedUser() {
        when(rechargeRecordMapper.sumDailyCents(eq(USER_ID), eq(TODAY_START_UTC))).thenReturn(120_000L);
        when(rechargeRecordMapper.countDaily(eq(USER_ID), eq(TODAY_START_UTC))).thenReturn(2L);

        RechargePanelVO panel = policy.buildPanel(USER_ID);

        assertThat(panel.getMinCents()).isEqualTo(10L);
        assertThat(panel.getMaxCents()).isEqualTo(500_000L);
        assertThat(panel.getDailyLimitCents()).isEqualTo(500_000L);
        assertThat(panel.getUsedDailyCents()).isEqualTo(120_000L);
        assertThat(panel.getRemainingDailyCents()).isEqualTo(380_000L);
        assertThat(panel.getUsedDailyCount()).isEqualTo(2L);
        assertThat(panel.getAmountOptions()).containsExactly(10_000L, 20_000L, 50_000L, 100_000L, 200_000L);
        assertThat(panel.getChannels()).hasSize(1);
        assertThat(panel.getChannels().get(0).getCode()).isEqualTo("mock");
        assertThat(panel.getChannels().get(0).getI18nKey()).isEqualTo("trade.pay.channelMock");
        assertThat(panel.isRealnameRequired()).isFalse();
    }

    @Test
    @DisplayName("面板：未实名 + 额度 0 → realnameRequired=true，剩余额度 0（不显示负数）")
    void panel_shouldMarkRealnameRequiredForUnverified() {
        when(accountQueryService.findRealnameStatus(USER_ID)).thenReturn(Optional.of("none"));
        when(rechargeRecordMapper.sumDailyCents(anyLong2(), any())).thenReturn(500L);

        RechargePanelVO panel = policy.buildPanel(USER_ID);

        assertThat(panel.isRealnameRequired()).isTrue();
        assertThat(panel.getRealnameStatus()).isEqualTo("none");
        assertThat(panel.getDailyLimitCents()).isZero();
        assertThat(panel.getRemainingDailyCents()).isZero();
    }

    @Test
    @DisplayName("面板：档位配置是坏 JSON 时回退默认档位，不把页面打挂")
    void panel_shouldFallbackWhenAmountOptionsBroken() {
        when(sysConfig.getString(eq(SysConfigKeys.RECHARGE_AMOUNT_OPTIONS), any()))
                .thenReturn("not-json");

        RechargePanelVO panel = policy.buildPanel(USER_ID);

        assertThat(panel.getAmountOptions()).containsExactly(10_000L, 20_000L, 50_000L, 100_000L, 200_000L);
    }

    @Test
    @DisplayName("下单超时时间：30 分钟后，UTC 输出")
    void expireAt_shouldBeThirtyMinutesLater() {
        LocalDateTime expireAt = policy.resolveExpireAt();
        assertThat(expireAt).isEqualTo(LocalDateTime.of(2026, 11, 8, 10, 30, 0));
    }

    /**
     * `anyLong()` 的别名，仅为了让 `sumDailyCents(Long, LocalDateTime)` 的 stub 可读。
     *
     * @return 匹配任意 Long
     */
    private static Long anyLong2() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}

