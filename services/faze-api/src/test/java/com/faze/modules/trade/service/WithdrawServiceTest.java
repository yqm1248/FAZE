/*
 * 职责：WithdrawService 的 F1 提现申请单测——四条服务端校验、手续费取整、幂等命中。
 * 调用方：mvn test（surefire，纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：《第 5 段工作流》§三 F1「产出：提现面板 + POST /app/withdrawals + 单测
 *         （4 条校验 + 幂等）」与 §五 5-A5 的实测口径。
 * 注意：
 *   1. 四条校验的**顺序**是：最低额 → 绑卡 → 单日笔数 → 锁余额后查余额。
 *      所以「额度已满 + 余额不足」会先报 405027（段 5 偏差 12 记录的就是这个），
 *      测试里必须把前置条件造干净，否则测不到目标分支。
 *   2. 手续费 `floor(金额 × 0.006)`：10000 分 → 60 分，10 分 → 0 分
 *      （「不足 1 分按 0 计」）。这里只验 service 层的算术，页面文案不在单测范围。
 *   3. 幂等命中时**不能**再 insert、不能再冻一次，返回的是库里那张单。
 */

package com.faze.modules.trade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.seq.BizNoGenerator;
import com.faze.infra.storage.FileRecordService;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.bo.WithdrawApplyBO;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.LawyerBalance;
import com.faze.modules.trade.entity.WithdrawRecord;
import com.faze.modules.trade.enums.WithdrawStatus;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.WithdrawRecordMapper;
import com.faze.modules.trade.vo.WithdrawVO;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

/**
 * 提现申请单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawServiceTest {

    private static final Long USER_ID = 9001L;
    private static final Long LAWYER_ID = 9002L;
    private static final Long WITHDRAW_ID = 8001L;

    @Mock
    private WithdrawRecordMapper withdrawRecordMapper;

    @Mock
    private BalanceFlowMapper balanceFlowMapper;

    @Mock
    private LawyerBalanceLocker lawyerBalanceLocker;

    @Mock
    private LawyerQueryService lawyerQueryService;

    @Mock
    private AccountQueryService accountQueryService;

    @Mock
    private SysConfigQueryService sysConfigQueryService;

    @Mock
    private BizNoGenerator bizNoGenerator;

    @Mock
    private WithdrawIdempotencyGuard idempotencyGuard;

    @Mock
    private FileRecordService fileRecordService;

    @Mock
    private JsonMapper jsonMapper;

    @InjectMocks
    private WithdrawService withdrawService;

    /** 已绑卡、余额充足的律师（每个用例按需覆写）。 */
    private Lawyer lawyer;

    @BeforeEach
    void setUp() {
        lawyer = new Lawyer();
        lawyer.setId(LAWYER_ID);
        lawyer.setUserId(USER_ID);
        lawyer.setRealName("验收律师甲");
        lawyer.setWithdrawBankName("中国工商银行");
        lawyer.setWithdrawBankAccount("6222021234567890123");

        when(lawyerQueryService.findByUserId(USER_ID)).thenReturn(Optional.of(lawyer));
        // 最低提现额 10 分、手续费率 0.6%
        when(sysConfigQueryService.getInt("withdraw.min_amount_cents", 10)).thenReturn(10);
        when(sysConfigQueryService.getString("withdraw.fee_rate", "0.006")).thenReturn("0.006");
        // 默认：无幂等命中、当天 0 笔
        when(idempotencyGuard.tryStart(any(), any())).thenReturn(Optional.empty());
        when(withdrawRecordMapper.selectCount(any())).thenReturn(0L);
        when(bizNoGenerator.nextWithdrawNo()).thenReturn("WD20261108000009");
        // insert 时回填主键（真实库由自增生成，这里手工模拟）
        when(withdrawRecordMapper.insert(any(WithdrawRecord.class))).thenAnswer(inv -> {
            WithdrawRecord record = inv.getArgument(0);
            record.setId(WITHDRAW_ID);
            return 1;
        });
        when(balanceFlowMapper.insert(any(BalanceFlow.class))).thenReturn(1);
    }

    /**
     * 造一次申请入参。
     *
     * @param amountCents 金额（分）
     * @return 入参
     */
    private WithdrawApplyBO bo(long amountCents) {
        WithdrawApplyBO bo = new WithdrawApplyBO();
        bo.setAmountCents(amountCents);
        return bo;
    }

    /**
     * 造一个「可提现 200 元、待结算 0」的余额。
     *
     * @param availableCents 可提现（分）
     * @return 余额行
     */
    private LawyerBalance balance(long availableCents) {
        LawyerBalance row = new LawyerBalance();
        row.setId(1L);
        row.setLawyerId(LAWYER_ID);
        row.setAvailableCents(availableCents);
        row.setFrozenCents(0L);
        row.setTotalIncomeCents(availableCents);
        row.setVersion(1);
        when(lawyerBalanceLocker.lock(LAWYER_ID)).thenReturn(row);
        return row;
    }

    /**
     * 校验 1：低于最低提现额 → 405023，且不建单。
     */
    @Test
    @DisplayName("低于最低提现额：405023 且不建单")
    void belowMinAmountRejected() {
        assertThatThrownBy(() -> withdrawService.apply(USER_ID, bo(5L), "k1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TRADE_WITHDRAW_BELOW_MIN));
        verify(withdrawRecordMapper, never()).insert(any(WithdrawRecord.class));
    }

    /**
     * 校验 2：未绑定收款账户 → 405025，且不建单。
     */
    @Test
    @DisplayName("未绑定收款账户：405025 且不建单")
    void unboundCardRejected() {
        lawyer.setWithdrawBankAccount(null);

        assertThatThrownBy(() -> withdrawService.apply(USER_ID, bo(10000L), "k1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TRADE_WITHDRAW_STATE_INVALID));
        verify(withdrawRecordMapper, never()).insert(any(WithdrawRecord.class));
    }

    /**
     * 校验 3：单日笔数已达 3 笔 → 405027，且不建单。
     */
    @Test
    @DisplayName("单日达上限：405027 且不建单")
    void dailyLimitRejected() {
        when(withdrawRecordMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> withdrawService.apply(USER_ID, bo(10000L), "k1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TRADE_WITHDRAW_TOO_FREQUENT));
        verify(withdrawRecordMapper, never()).insert(any(WithdrawRecord.class));
    }

    /**
     * 校验 4：可提现余额不足 → 405024（要在锁余额之后才报，所以单据已经先落库再回滚）。
     */
    @Test
    @DisplayName("可提现余额不足：405024")
    void insufficientBalanceRejected() {
        balance(500L);

        assertThatThrownBy(() -> withdrawService.apply(USER_ID, bo(10000L), "k1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TRADE_WITHDRAW_INSUFFICIENT));
    }

    /**
     * 100 元提现：手续费 60 分、实际到账 9940 分，冻结额与总余额都对。
     */
    @Test
    @DisplayName("手续费：100 元 → fee 60 / actual 9940，冻结后总余额不变")
    void feeRoundedDownForHundredYuan() {
        LawyerBalance row = balance(20000L);

        WithdrawVO vo = withdrawService.apply(USER_ID, bo(10000L), "k1");

        assertThat(vo.getFeeCents()).as("floor(10000 × 0.006) = 60").isEqualTo(60L);
        assertThat(vo.getActualCents()).isEqualTo(9940L);
        assertThat(vo.getStatus()).isEqualTo(WithdrawStatus.APPLYING.getCode());
        // available 20000 → 10000，frozen 0 → 10000，总余额仍是 20000
        verify(lawyerBalanceLocker).apply(row, 10000L, 10000L, null);
    }

    /**
     * 10 分提现：手续费 0 分（不足 1 分按 0 计）。
     */
    @Test
    @DisplayName("手续费：10 分 → fee 0 / actual 10")
    void feeZeroForTenCents() {
        balance(20000L);

        WithdrawVO vo = withdrawService.apply(USER_ID, bo(10L), "k1");

        assertThat(vo.getFeeCents()).as("floor(10 × 0.006) = 0").isZero();
        assertThat(vo.getActualCents()).isEqualTo(10L);
    }

    /**
     * 幂等命中：返回库里那张单，不再 insert、不再冻第二次。
     */
    @Test
    @DisplayName("幂等命中：返回同一张单，不重复建单")
    void idempotencyHitReturnsSameRecord() {
        WithdrawRecord existing = new WithdrawRecord();
        existing.setId(WITHDRAW_ID);
        existing.setWithdrawNo("WD20261108000001");
        existing.setAmountCents(10000L);
        existing.setFeeCents(60L);
        existing.setActualCents(9940L);
        existing.setStatus(WithdrawStatus.APPLYING.getCode());
        existing.setCreatedAt(LocalDateTime.now());

        when(idempotencyGuard.tryStart(LAWYER_ID, "k1")).thenReturn(Optional.of(WITHDRAW_ID));
        when(withdrawRecordMapper.selectById(WITHDRAW_ID)).thenReturn(existing);

        WithdrawVO vo = withdrawService.apply(USER_ID, bo(10000L), "k1");

        assertThat(vo.getId()).isEqualTo(WITHDRAW_ID);
        assertThat(vo.getWithdrawNo()).isEqualTo("WD20261108000001");
        verify(withdrawRecordMapper, never()).insert(any(WithdrawRecord.class));
        verify(lawyerBalanceLocker, never()).apply(any(), anyLong(), anyLong(), any());
    }

    /**
     * 非律师账号：401008（403），不是 404。
     */
    @Test
    @DisplayName("非律师：401008")
    void nonLawyerRejected() {
        when(lawyerQueryService.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawService.apply(USER_ID, bo(10000L), "k1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_FORBIDDEN));
        verify(withdrawRecordMapper, never()).insert(any(WithdrawRecord.class));
    }
}
