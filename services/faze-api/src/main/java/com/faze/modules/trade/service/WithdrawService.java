/*
 * 职责：律师提现闭环（第 5 段 F 组 F1~F3）——申请冻结 / 审核通过 / 审核驳回回滚 / 出款扣减。
 * 调用方：modules/trade/controller/WithdrawController（App 66-income 提现面板）、
 *         modules/trade/admin/controller/AdminWithdrawController（PC A-10 提现审核）。
 * 数据来源：withdraw_record（单据状态）、lawyer_balance（缓存）、balance_flow（资金真相）、
 *          lawyer / app_user（列表展示）、sys_config（最低额与手续费率）。
 * 注意：
 *   1. **唯一的资金出口**。四条动账路径，全部在同一事务里「改单 + 动账 + 写流水」：
 *      - 申请：`available -= amount`、`frozen += amount`，总余额不变，写 `freeze`（delta=0）；
 *      - 驳回：`frozen -= amount`、`available += amount`，写 `unfreeze`（delta=0，biz_id=提现单）；
 *      - 出款：`frozen -= amount`，写 `withdraw`（delta=−amount，总余额真减少）；
 *      - 通过（`applying → approved`）：**不动账**，只改状态与凭证。
 *   2. **手续费不单独写流水**：`balance_flow.delta = −amount`（申请金额）而不是 `−actual`。
 *      手续费 0.6% 是渠道扣的，平台账上是全额出账；手续费只记在 `withdraw_record.fee_cents`，
 *      否则恒等式要多一个科目（工作流 F3 第 1 条）。
 *   3. `unfreeze` 的 biz_type 与收入解冻（`income_unfreeze`）**刻意不同**：
 *      两者的 biz_id 分属 withdraw_record 与 balance_flow 两张自增表，同 biz_type 必然撞 uk_flow_biz。
 *   4. 状态迁移一律 CAS（`status = from`），不允许「先查再无条件改」。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.common.pojo.TableDataInfo;
import com.faze.infra.seq.BizNoGenerator;
import com.faze.infra.storage.FileRecordService;
import com.faze.infra.storage.entity.FileRecord;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.admin.bo.AdminWithdrawQueryBO;
import com.faze.modules.trade.admin.vo.AdminWithdrawRowVO;
import com.faze.modules.trade.admin.vo.AdminWithdrawSummaryVO;
import com.faze.modules.trade.bo.WithdrawApplyBO;
import com.faze.modules.trade.bo.WithdrawAuditBO;
import com.faze.modules.trade.bo.WithdrawVoucherBO;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.LawyerBalance;
import com.faze.modules.trade.entity.WithdrawRecord;
import com.faze.modules.trade.enums.WithdrawStatus;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.WithdrawRecordMapper;
import com.faze.modules.trade.rule.WithdrawStateMachine;
import com.faze.modules.trade.vo.WithdrawVO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 律师提现服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawService {

    /** 流水归属类型：律师。 */
    private static final String OWNER_LAWYER = "lawyer";

    /** 流水业务类型：冻结（申请提现，delta=0）。 */
    private static final String BIZ_FREEZE = "freeze";

    /** 流水业务类型：解冻（提现驳回，delta=0）。 */
    private static final String BIZ_UNFREEZE = "unfreeze";

    /** 流水业务类型：出款（钱离开平台，delta=−金额）。 */
    private static final String BIZ_WITHDRAW = "withdraw";

    /** 配置键：最低提现额（分，默认 10）。 */
    private static final String CFG_MIN_AMOUNT = "withdraw.min_amount_cents";

    /** 配置键：手续费率（默认 0.006 = 0.6%）。 */
    private static final String CFG_FEE_RATE = "withdraw.fee_rate";

    /** 最低提现额默认值（0.1 元）。 */
    private static final int DEFAULT_MIN_AMOUNT_CENTS = 10;

    /** 手续费率默认值（0.6%）。 */
    private static final String DEFAULT_FEE_RATE = "0.006";

    /** 单日提现申请笔数上限（防刷）。 */
    private static final int MAX_DAILY_APPLY = 3;

    /** 业务时区：东八区（「今日」按北京时间切）。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final WithdrawRecordMapper withdrawRecordMapper;
    private final BalanceFlowMapper balanceFlowMapper;
    private final LawyerBalanceLocker lawyerBalanceLocker;
    private final LawyerQueryService lawyerQueryService;
    private final com.faze.modules.account.service.AccountQueryService accountQueryService;
    private final SysConfigQueryService sysConfigQueryService;
    private final BizNoGenerator bizNoGenerator;
    private final WithdrawIdempotencyGuard idempotencyGuard;
    private final FileRecordService fileRecordService;
    private final JsonMapper jsonMapper;

    // ── F1：提现申请 ────────────────────────────────────────────────────────

    /**
     * 律师申请提现：校验四条规则 → 建单 → 冻结余额 → 写 freeze 流水。
     *
     * <p>幂等：同一 `Idempotency-Key` 重复提交返回**同一张单**，不冻第二次。</p>
     *
     * @param userId         当前登录用户（律师）
     * @param bo             金额
     * @param idempotencyKey 客户端幂等键（可空）
     * @return 新建（或幂等命中）的提现单
     * @throws BusinessException 401008 非律师 / 405023 低于最低额 / 405024 余额不足 /
     *                           405025 未绑卡或状态非法 / 405027 单日过于频繁 / 405004 重复提交
     */
    @Transactional(rollbackFor = Exception.class)
    public WithdrawVO apply(Long userId, WithdrawApplyBO bo, String idempotencyKey) {
        Lawyer lawyer = requireLawyer(userId);
        // 任务 1：幂等闸门（命中且单据还在 → 直接返回；单据已删/不存在则释放后继续）
        Optional<Long> existingId = idempotencyGuard.tryStart(lawyer.getId(), idempotencyKey);
        if (existingId.isPresent()) {
            WithdrawRecord existing = withdrawRecordMapper.selectById(existingId.get());
            if (existing != null && existing.getDeletedAt() == null) {
                return toVO(existing);
            }
            idempotencyGuard.release(lawyer.getId(), idempotencyKey);
        }

        long amount = bo.getAmountCents() == null ? 0L : bo.getAmountCents();
        // 任务 2：四条服务端校验（前端校验只是体验）
        long minAmount = sysConfigQueryService.getInt(CFG_MIN_AMOUNT, DEFAULT_MIN_AMOUNT_CENTS);
        if (amount < minAmount) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_BELOW_MIN);
        }
        String bankAccount = lawyer.getWithdrawBankAccount();
        if (bankAccount == null || bankAccount.length() < 4 || !StringUtils.hasText(lawyer.getWithdrawBankName())) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID, "请先绑定收款账户");
        }
        assertDailyApplyLimit(lawyer.getId());

        // 任务 3：手续费与到账金额（向下取整到分；10 分时 fee=0）
        long fee = calcFee(amount);
        long actual = amount - fee;

        // 任务 4：建单（先落单再动账：动账失败会整单回滚，不会留下没单的冻结）
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        WithdrawRecord record = new WithdrawRecord();
        record.setWithdrawNo(bizNoGenerator.nextWithdrawNo());
        record.setLawyerId(lawyer.getId());
        record.setAmountCents(amount);
        record.setFeeCents(fee);
        record.setActualCents(actual);
        record.setChannel("bank");
        record.setBankName(lawyer.getWithdrawBankName());
        record.setAccountName(lawyer.getRealName());
        record.setAccountNumber(bankAccount);
        record.setBankAccountTail(bankAccount.substring(bankAccount.length() - 4));
        record.setStatus(WithdrawStatus.APPLYING.getCode());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        withdrawRecordMapper.insert(record);

        // 任务 5：冻结（available -= amount, frozen += amount，总余额不变）
        LawyerBalance balance = lawyerBalanceLocker.lock(lawyer.getId());
        long available = nz(balance.getAvailableCents());
        if (available < amount) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_INSUFFICIENT);
        }
        long newAvailable = available - amount;
        long newFrozen = nz(balance.getFrozenCents()) + amount;
        lawyerBalanceLocker.apply(balance, newAvailable, newFrozen, null);

        // 任务 6：写 freeze 流水（delta=0：转移不是收支，只为留「何时冻结」的证据）
        writeFlow(lawyer.getId(), BIZ_FREEZE, record.getId(), 0L, newAvailable + newFrozen,
                "提现申请冻结", now);

        idempotencyGuard.bind(lawyer.getId(), idempotencyKey, record.getId());
        log.info("提现申请已受理 lawyerId={} no={} amount={} fee={} actual={}",
                lawyer.getId(), record.getWithdrawNo(), amount, fee, actual);
        return toVO(record);
    }

    /**
     * 当前律师的提现记录（分页，66-income 的提现记录列表）。
     *
     * @param userId   当前登录用户
     * @param pageNum  页码（可空）
     * @param pageSize 每页条数（可空）
     * @return 分页结果
     * @throws BusinessException 401008 非律师
     */
    public PageResult<WithdrawVO> listMine(Long userId, Integer pageNum, Integer pageSize) {
        Lawyer lawyer = requireLawyer(userId);
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), 100);
        IPage<WithdrawRecord> page = withdrawRecordMapper.selectPage(new Page<>(num, size),
                Wrappers.<WithdrawRecord>lambdaQuery()
                        .eq(WithdrawRecord::getLawyerId, lawyer.getId())
                        .isNull(WithdrawRecord::getDeletedAt)
                        .orderByDesc(WithdrawRecord::getCreatedAt)
                        .orderByDesc(WithdrawRecord::getId));
        List<WithdrawVO> list = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(list, page.getTotal(), num, size);
    }

    /**
     * 提现规则（最低额 + 手续费率），供 App 提现面板提示用。
     *
     * @return 规则
     */
    public com.faze.modules.trade.vo.WithdrawConfigVO config() {
        com.faze.modules.trade.vo.WithdrawConfigVO vo = new com.faze.modules.trade.vo.WithdrawConfigVO();
        vo.setMinAmountCents((long) sysConfigQueryService.getInt(CFG_MIN_AMOUNT, DEFAULT_MIN_AMOUNT_CENTS));
        vo.setFeeRate(sysConfigQueryService.getString(CFG_FEE_RATE, DEFAULT_FEE_RATE));
        return vo;
    }

    // ── F2：PC 审核 ─────────────────────────────────────────────────────────
    /**
     * 审核通过（`applying → approved`）：**不动账**，只记凭证与审核人。
     *
     * <p>真实打款由财务在网银完成后回来点「已出款」（`markPaid`）。分开两步是
     * §零之二 第 5 项的裁决，不合并。</p>
     *
     * @param id 提现单主键
     * @param bo 审核入参（`voucherFileId` 必填）
     * @throws BusinessException 405006 单据不存在 / 405025 状态非法 / 400001 缺凭证
     */
    @OperationLog(module = "trade", action = "WITHDRAW_APPROVE", targetType = "WITHDRAW_RECORD")
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, WithdrawAuditBO bo) {
        WithdrawRecord record = requireRecord(id);
        // 先判状态再判参数：单据已经是终态时应报 405025（状态非法），
        // 而不是把用户引去补一个补了也没用的凭证。
        WithdrawStateMachine.check(WithdrawStatus.of(record.getStatus()), WithdrawStatus.APPROVED);
        if (bo.getVoucherFileId() == null) {
            // 通过 = 已经在银行完成转账，没有凭证的通过等于无凭据出款（F2 第 5 条）
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "通过审核必须上传出款凭证");
        }
        FileRecord voucher = fileRecordService.findById(bo.getVoucherFileId())
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_NOT_FOUND, "出款凭证不存在"));
        if (!FileRecordService.BIZ_WITHDRAW.equals(voucher.getBizType())
                || !Objects.equals(voucher.getBizId(), id)) {
            // 防止把别人的凭证串用过来：凭证必须属于本提现单
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "出款凭证不属于本提现单");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = withdrawRecordMapper.casTransit(id, WithdrawStatus.APPLYING.getCode(),
                WithdrawStatus.APPROVED.getCode(), currentOperatorId(), now, null, null,
                bo.getTransactionId(), now);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID);
        }
        withdrawRecordMapper.update(null, Wrappers.<WithdrawRecord>lambdaUpdate()
                .set(WithdrawRecord::getVoucherFileId, bo.getVoucherFileId())
                .eq(WithdrawRecord::getId, id));
        AuditContext.mark("trade", "WITHDRAW_APPROVE", "WITHDRAW_RECORD", String.valueOf(id),
                detailJson(Map.of(
                        "before", Map.of("status", WithdrawStatus.APPLYING.getCode()),
                        "after", Map.of("status", WithdrawStatus.APPROVED.getCode()),
                        "withdrawNo", String.valueOf(record.getWithdrawNo()),
                        "amountCents", nz(record.getAmountCents()),
                        "voucherFileId", bo.getVoucherFileId())));
        log.info("提现审核通过（未出款）withdrawId={} no={}", id, record.getWithdrawNo());
    }

    /**
     * 审核驳回（`applying → rejected`）：解冻回滚 —— `frozen -= amount`、`available += amount`。
     *
     * @param id 提现单主键
     * @param bo 审核入参（`reason` 必填）
     * @throws BusinessException 405006 / 405025 / 400001 缺理由
     */
    @OperationLog(module = "trade", action = "WITHDRAW_REJECT", targetType = "WITHDRAW_RECORD")
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, WithdrawAuditBO bo) {
        WithdrawRecord record = requireRecord(id);
        WithdrawStateMachine.check(WithdrawStatus.of(record.getStatus()), WithdrawStatus.REJECTED);
        if (bo.getReason() == null || bo.getReason().isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "驳回必须填写理由");
        }
        long amount = nz(record.getAmountCents());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = withdrawRecordMapper.casTransit(id, WithdrawStatus.APPLYING.getCode(),
                WithdrawStatus.REJECTED.getCode(), currentOperatorId(), now, null,
                bo.getReason().trim(), null, now);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID);
        }
        // 解冻回滚：钱被驳回就必须还给律师（不还 = 永久锁死）
        LawyerBalance balance = lawyerBalanceLocker.lock(record.getLawyerId());
        long frozen = nz(balance.getFrozenCents());
        if (frozen < amount) {
            throw new BusinessException(ErrorCode.COMMON_INTERNAL, "冻结额不足，无法回滚提现");
        }
        long newFrozen = frozen - amount;
        long newAvailable = nz(balance.getAvailableCents()) + amount;
        lawyerBalanceLocker.apply(balance, newAvailable, newFrozen, null);
        writeFlow(record.getLawyerId(), BIZ_UNFREEZE, id, 0L, newAvailable + newFrozen,
                "提现驳回解冻", now);
        AuditContext.mark("trade", "WITHDRAW_REJECT", "WITHDRAW_RECORD", String.valueOf(id),
                detailJson(Map.of(
                        "before", Map.of("status", WithdrawStatus.APPLYING.getCode()),
                        "after", Map.of("status", WithdrawStatus.REJECTED.getCode()),
                        "withdrawNo", String.valueOf(record.getWithdrawNo()),
                        "reason", bo.getReason().trim())));
        log.info("提现审核驳回并已解冻 withdrawId={} no={} amount={}", id, record.getWithdrawNo(), amount);
    }

    /**
     * 出款完成（`approved → paid`）：`frozen -= amount`，写 `withdraw` 流水（delta=−amount）。
     *
     * <p>这是钱真正离开平台的一步。**delta 是 −金额而不是 −实际到账**：
     * 手续费是渠道扣的，平台全额出账，手续费只记在单据上（F3 第 1 条）。</p>
     *
     * @param id 提现单主键
     * @throws BusinessException 405006 / 405025 状态非法
     */
    @OperationLog(module = "trade", action = "WITHDRAW_PAID", targetType = "WITHDRAW_RECORD")
    @Transactional(rollbackFor = Exception.class)
    public void markPaid(Long id) {
        WithdrawRecord record = requireRecord(id);
        WithdrawStateMachine.check(WithdrawStatus.of(record.getStatus()), WithdrawStatus.PAID);
        long amount = nz(record.getAmountCents());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = withdrawRecordMapper.casTransit(id, WithdrawStatus.APPROVED.getCode(),
                WithdrawStatus.PAID.getCode(), null, null, now, null, null, now);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID);
        }
        LawyerBalance balance = lawyerBalanceLocker.lock(record.getLawyerId());
        long frozen = nz(balance.getFrozenCents());
        if (frozen < amount) {
            throw new BusinessException(ErrorCode.COMMON_INTERNAL, "冻结额不足，无法出款");
        }
        long newFrozen = frozen - amount;
        long newAvailable = nz(balance.getAvailableCents());
        lawyerBalanceLocker.apply(balance, newAvailable, newFrozen, null);
        // 总余额真减少：available + frozen 已扣掉 amount
        writeFlow(record.getLawyerId(), BIZ_WITHDRAW, id, -amount, newAvailable + newFrozen,
                "提现出款", now);
        AuditContext.mark("trade", "WITHDRAW_PAID", "WITHDRAW_RECORD", String.valueOf(id),
                detailJson(Map.of(
                        "before", Map.of("status", WithdrawStatus.APPROVED.getCode()),
                        "after", Map.of("status", WithdrawStatus.PAID.getCode()),
                        "withdrawNo", String.valueOf(record.getWithdrawNo()),
                        "amountCents", amount)));
        log.info("提现已出款 withdrawId={} no={} amount={}", id, record.getWithdrawNo(), amount);
    }

    /**
     * 上传出款凭证（审核通过前预上传，或通过后补传）。
     *
     * <p>为什么允许 `applying` 状态上传：F2 的流程是「通过时上传凭证」——财务先在弹窗里选文件，
     * 前端先把它传上来拿到 `fileRecordId`，再带着这个 id 调 `approve`。
     * 如果只允许 `approved` 后补传，第一次通过就没有凭证可传，等于把主路径堵死
     * （2026-11-08 实测踩到：`applying` 上传被 405025 拒）。</p>
     *
     * @param id 提现单主键
     * @param bo 文件入参
     * @return 新登记的 `file_record.id`
     * @throws BusinessException 405006 / 405025 已驳回的单不允许再传凭证
     */
    @OperationLog(module = "trade", action = "WITHDRAW_VOUCHER_UPLOAD", targetType = "WITHDRAW_RECORD")
    @Transactional(rollbackFor = Exception.class)
    public Long uploadVoucher(Long id, WithdrawVoucherBO bo) {
        WithdrawRecord record = requireRecord(id);
        WithdrawStatus status = WithdrawStatus.of(record.getStatus());
        if (status == WithdrawStatus.REJECTED) {
            // 已驳回的单不会再出款，传凭证没有意义（也避免被当成「已出款」的证据）
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID,
                    "已驳回的提现单不能再上传凭证");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(bo.getContentBase64());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "凭证内容不是合法的 base64");
        }
        Long fileId = fileRecordService.storeWithdrawVoucher(id, bo.getFileName(),
                bo.getContentType(), content);
        withdrawRecordMapper.update(null, Wrappers.<WithdrawRecord>lambdaUpdate()
                .set(WithdrawRecord::getVoucherFileId, fileId)
                .eq(WithdrawRecord::getId, id));
        AuditContext.mark("trade", "WITHDRAW_VOUCHER_UPLOAD", "WITHDRAW_RECORD", String.valueOf(id),
                detailJson(Map.of("voucherFileId", fileId == null ? 0L : fileId)));
        return fileId;
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    /**
     * PC 提现列表（A-10，rows/total 分页）。
     *
     * @param query 查询入参
     * @return 分页结果
     */
    public TableDataInfo<AdminWithdrawRowVO> adminList(AdminWithdrawQueryBO query) {
        var wrapper = Wrappers.<WithdrawRecord>lambdaQuery()
                .isNull(WithdrawRecord::getDeletedAt)
                .orderByDesc(WithdrawRecord::getCreatedAt)
                .orderByDesc(WithdrawRecord::getId);
        if (StringUtils.hasText(query.getStatus())) {
            // 状态值非法在这里就拒（不静默返回空列表：前端拼错参数要能发现）
            wrapper.eq(WithdrawRecord::getStatus, WithdrawStatus.of(query.getStatus()).getCode());
        }
        if (StringUtils.hasText(query.getLawyerKw())) {
            List<Long> ids = searchLawyerIds(query.getLawyerKw());
            if (ids.isEmpty()) {
                return TableDataInfo.of(List.of(), 0L);
            }
            wrapper.in(WithdrawRecord::getLawyerId, ids);
        }
        if (StringUtils.hasText(query.getBeginDate())) {
            wrapper.ge(WithdrawRecord::getCreatedAt, parseDayBeginUtc(query.getBeginDate()));
        }
        if (StringUtils.hasText(query.getEndDate())) {
            LocalDate end = parseDay(query.getEndDate());
            wrapper.lt(WithdrawRecord::getCreatedAt, end.plusDays(1)
                    .atStartOfDay(BIZ_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
        IPage<WithdrawRecord> page = withdrawRecordMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return TableDataInfo.of(toAdminRows(page.getRecords()), page.getTotal());
    }

    /**
     * PC 提现页四张统计卡。
     *
     * @return 统计卡数据
     */
    public AdminWithdrawSummaryVO summary() {
        AdminWithdrawSummaryVO vo = new AdminWithdrawSummaryVO();
        vo.setPendingCount(Math.toIntExact(countByStatus(WithdrawStatus.APPLYING.getCode())));
        vo.setPendingAmountCents(sumByStatus(WithdrawStatus.APPLYING.getCode(), "amount_cents", null, null));
        LocalDateTime dayBegin = LocalDate.now(BIZ_ZONE).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime dayEnd = LocalDate.now(BIZ_ZONE).plusDays(1).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        vo.setTodayAuditedCount(Math.toIntExact(countAudited(dayBegin, dayEnd)));
        LocalDateTime monthBegin = YearMonth.now(BIZ_ZONE).atDay(1).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime monthEnd = YearMonth.now(BIZ_ZONE).plusMonths(1).atDay(1).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        vo.setMonthPaidCents(sumByStatus(WithdrawStatus.PAID.getCode(), "actual_cents", monthBegin, monthEnd));
        return vo;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 写一条资金流水。
     *
     * @param lawyerId     律师 id
     * @param bizType      业务类型
     * @param bizId        业务主键
     * @param deltaCents   变动（分）
     * @param balanceAfter 变动后总余额（分）
     * @param remark       备注
     * @param now          发生时间（UTC）
     */
    private void writeFlow(Long lawyerId, String bizType, Long bizId, long deltaCents,
                           long balanceAfter, String remark, LocalDateTime now) {
        BalanceFlow flow = new BalanceFlow();
        flow.setOwnerType(OWNER_LAWYER);
        flow.setOwnerId(lawyerId);
        flow.setBizType(bizType);
        flow.setBizId(bizId);
        flow.setDeltaCents(deltaCents);
        flow.setBalanceAfter(balanceAfter);
        flow.setFrozenUntil(null);
        flow.setRemark(remark);
        flow.setCreatedAt(now);
        balanceFlowMapper.insert(flow);
    }

    /**
     * 计算手续费（`floor(金额 × 费率)`，向下取整到分）。
     *
     * @param amountCents 申请金额（分）
     * @return 手续费（分）；10 分时返回 0
     */
    private long calcFee(long amountCents) {
        String raw = sysConfigQueryService.getString(CFG_FEE_RATE, DEFAULT_FEE_RATE);
        BigDecimal rate;
        try {
            rate = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("手续费率配置非法，回退默认值 raw={}", raw);
            rate = new BigDecimal(DEFAULT_FEE_RATE);
        }
        return BigDecimal.valueOf(amountCents)
                .multiply(rate)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
    }

    /**
     * 校验单日提现申请笔数上限。
     *
     * <p>只统计**非驳回**的单：被驳回后律师要能重新申请，把驳回也算进去会把人卡死。</p>
     *
     * @param lawyerId 律师 id
     * @throws BusinessException 405027 单日过于频繁
     */
    private void assertDailyApplyLimit(Long lawyerId) {
        LocalDateTime dayBegin = LocalDate.now(BIZ_ZONE).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        Long count = withdrawRecordMapper.selectCount(Wrappers.<WithdrawRecord>lambdaQuery()
                .eq(WithdrawRecord::getLawyerId, lawyerId)
                .isNull(WithdrawRecord::getDeletedAt)
                .ne(WithdrawRecord::getStatus, WithdrawStatus.REJECTED.getCode())
                .ge(WithdrawRecord::getCreatedAt, dayBegin));
        if (count != null && count >= MAX_DAILY_APPLY) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_TOO_FREQUENT);
        }
    }

    /**
     * 取律师身份（从 token 的 userId 反查，不从请求参数取）。
     *
     * @param userId 当前登录用户
     * @return 律师实体
     * @throws BusinessException 401008 非律师
     */
    private Lawyer requireLawyer(Long userId) {
        return lawyerQueryService.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_FORBIDDEN, "当前用户不是律师"));
    }

    /**
     * 取提现单（不存在即 405006）。
     *
     * @param id 提现单主键
     * @return 单据
     * @throws BusinessException 405006 不存在或已删
     */
    private WithdrawRecord requireRecord(Long id) {
        WithdrawRecord record = id == null ? null : withdrawRecordMapper.selectById(id);
        if (record == null || record.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID, "提现单不存在");
        }
        return record;
    }

    /**
     * 按律师关键词搜 id（姓名/律所/执业证号，或手机号片段）。
     *
     * @param keyword 关键词
     * @return 律师 id 列表（可能为空）
     */
    private List<Long> searchLawyerIds(String keyword) {
        List<Long> byName = lawyerQueryService.searchIdsByKeyword(keyword);
        if (!byName.isEmpty()) {
            return byName;
        }
        return lawyerQueryService.searchIdsByPhone(keyword);
    }

    /**
     * 解析 `yyyy-MM-dd`（东八区）当天 0 点对应的 UTC。
     *
     * @param date 日期串
     * @return UTC 时间
     * @throws BusinessException 400001 格式非法
     */
    private LocalDateTime parseDayBeginUtc(String date) {
        return parseDay(date).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 解析 `yyyy-MM-dd`。
     *
     * @param date 日期串
     * @return 日期
     * @throws BusinessException 400001 格式非法
     */
    private LocalDate parseDay(String date) {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "日期格式必须是 yyyy-MM-dd");
        }
    }

    /**
     * 按状态计数。
     *
     * @param status 状态
     * @return 条数
     */
    private long countByStatus(String status) {
        Long count = withdrawRecordMapper.selectCount(Wrappers.<WithdrawRecord>lambdaQuery()
                .eq(WithdrawRecord::getStatus, status)
                .isNull(WithdrawRecord::getDeletedAt));
        return count == null ? 0L : count;
    }

    /**
     * 按状态汇总某金额列。
     *
     * @param status  状态
     * @param column  金额列（常量：amount_cents / actual_cents）
     * @param beginUtc 时间起（可空）
     * @param endUtc   时间止（可空）
     * @return 合计（分）
     */
    private long sumByStatus(String status, String column, LocalDateTime beginUtc, LocalDateTime endUtc) {
        // column 只接受本类内写死的常量（amount_cents / actual_cents），不是用户输入，
        // 所以这里拼接安全；这也是 D 组已登记过的「聚合用 Wrapper 而非 XML」的同一处理。
        QueryWrapper<WithdrawRecord> wrapper = new QueryWrapper<WithdrawRecord>()
                .select("COALESCE(SUM(" + column + "), 0) AS total")
                .eq("status", status)
                .isNull("deleted_at");
        if (beginUtc != null && endUtc != null) {
            wrapper.ge("paid_at", beginUtc).lt("paid_at", endUtc);
        }
        List<Object> values = withdrawRecordMapper.selectObjs(wrapper);
        if (values.isEmpty() || values.get(0) == null) {
            return 0L;
        }
        Object raw = values.get(0);
        return raw instanceof Number number ? number.longValue() : Long.parseLong(raw.toString());
    }

    /**
     * 统计今日已审笔数（`audited_at` 落在东八区今日）。
     *
     * @param beginUtc 今日 0 点（UTC）
     * @param endUtc   明日 0 点（UTC）
     * @return 条数
     */
    private long countAudited(LocalDateTime beginUtc, LocalDateTime endUtc) {
        Long count = withdrawRecordMapper.selectCount(Wrappers.<WithdrawRecord>lambdaQuery()
                .isNotNull(WithdrawRecord::getAuditedAt)
                .ge(WithdrawRecord::getAuditedAt, beginUtc)
                .lt(WithdrawRecord::getAuditedAt, endUtc)
                .isNull(WithdrawRecord::getDeletedAt));
        return count == null ? 0L : count;
    }

    /**
     * 实体 → App 出参。
     *
     * @param record 提现单
     * @return App 行
     */
    private WithdrawVO toVO(WithdrawRecord record) {
        WithdrawVO vo = new WithdrawVO();
        vo.setId(record.getId());
        vo.setWithdrawNo(record.getWithdrawNo());
        vo.setAmountCents(record.getAmountCents());
        vo.setFeeCents(record.getFeeCents());
        vo.setActualCents(record.getActualCents());
        vo.setStatus(record.getStatus());
        vo.setBankName(record.getBankName());
        vo.setBankAccountTail(record.getBankAccountTail());
        vo.setRejectReason(record.getRejectReason());
        vo.setCreatedAt(record.getCreatedAt());
        vo.setPaidAt(record.getPaidAt());
        return vo;
    }

    /**
     * 实体列表 → PC 行（批量取律师与手机号，避免 N+1）。
     *
     * @param records 提现单列表
     * @return PC 行列表
     */
    private List<AdminWithdrawRowVO> toAdminRows(List<WithdrawRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> lawyerIds = records.stream().map(WithdrawRecord::getLawyerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Lawyer> lawyers = new HashMap<>(lawyerQueryService.mapByIds(lawyerIds));
        List<Long> userIds = lawyers.values().stream().map(Lawyer::getUserId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, com.faze.modules.account.vo.UserBriefVO> users =
                accountQueryService.mapBriefs(userIds);
        List<AdminWithdrawRowVO> rows = new ArrayList<>(records.size());
        for (WithdrawRecord record : records) {
            AdminWithdrawRowVO vo = new AdminWithdrawRowVO();
            vo.setId(record.getId());
            vo.setWithdrawNo(record.getWithdrawNo());
            vo.setLawyerId(record.getLawyerId());
            vo.setAmountCents(record.getAmountCents());
            vo.setFeeCents(record.getFeeCents());
            vo.setActualCents(record.getActualCents());
            vo.setBankName(record.getBankName());
            vo.setBankAccountTail(record.getBankAccountTail());
            vo.setStatus(record.getStatus());
            vo.setRejectReason(record.getRejectReason());
            vo.setVoucherFileId(record.getVoucherFileId());
            vo.setTransactionId(record.getTransactionId());
            vo.setCreatedAt(record.getCreatedAt());
            vo.setAuditedAt(record.getAuditedAt());
            vo.setPaidAt(record.getPaidAt());
            Lawyer lawyer = lawyers.get(record.getLawyerId());
            if (lawyer != null) {
                vo.setLawyerName(lawyer.getRealName());
                vo.setLawyerAvatar(lawyer.getAvatarUrl());
                vo.setLicenseNumber(lawyer.getLicenseNumber());
                var brief = users.get(lawyer.getUserId());
                vo.setLawyerPhoneMasked(brief == null ? null : brief.phoneMasked());
            }
            rows.add(vo);
        }
        return rows;
    }

    /**
     * 取当前管理端操作人 id（审计上下文里由过滤器写入）。
     *
     * @return 操作人 id；非管理端调用时为 null
     */
    private Long currentOperatorId() {
        AuditContext.AuditInfo info = AuditContext.current();
        return info == null ? null : info.getOperatorId();
    }

    /**
     * 序列化审计 detail（Jackson，不拼字符串）。
     *
     * @param payload 结构化明细
     * @return JSON；失败返回 null
     */
    private String detailJson(Map<String, Object> payload) {
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("审计 detail 序列化失败", e);
            return null;
        }
    }

    /**
     * null 安全取 long。
     *
     * @param value 包装类型
     * @return 原值；null 视为 0
     */
    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
