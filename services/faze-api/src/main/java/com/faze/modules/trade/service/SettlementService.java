/*
 * 职责：律师收入结算（第 5 段 E 组 E1~E4）——本组**唯一会动律师余额**的服务。
 * 调用方：LawyerOrderService#complete（E1 完成订单入账）、LawyerUnfreezeTask（E3 到期解冻）、
 *         AdminSettlementController（E2 回补 / E4 生成、列表、明细、确认）。
 * 数据来源：orders / balance_flow / lawyer_balance / lawyer_income_settlement / sys_config。
 * 注意：
 *   1. **资金口径（E0，全组唯一口径，不许另立）**：
 *      律师总余额 = `available_cents + frozen_cents`；
 *      `balance_flow.balance_after` 记的是**总余额**，不是可用余额。
 *      两条恒等式（`5-A4` 验的就是它们）：
 *        ① available + frozen == 该律师最后一条流水的 balance_after
 *        ② available + frozen == SUM(delta_cents)（delta=0 的转移行不影响）
 *   2. **本类只有 `creditIncome` 与 `unfreezeDue` 会改 lawyer_balance**。
 *      结算批次（generate / confirm / detail）**不碰钱**：不写 balance_flow、不改 lawyer_balance。
 *      这是「结算与提现重复打款」的唯一防线（工作流 §零之二 第 1 项）。
 *   3. 并发用「锁行 + 乐观锁」双保险：`SELECT ... FOR UPDATE` 锁住 lawyer_balance 行，
 *      再用带 `@Version` 的 updateById 落库。为什么不能只靠乐观锁重试：`balance_after`
 *      要写「更新后的总余额」，而 REPEATABLE READ 下快照读重试会一直读到旧值。
 *   4. 幂等三处，全部不靠应用层「先查再写」单独兜底：
 *      income → `uk_flow_biz`；unfreeze → `NOT EXISTS` + `uk_flow_biz`；
 *      结算批次 → `uk_settlement_lawyer_period`。
 *   5. 时间：库内一律 UTC；**账期 `yyyy-MM` 是东八区自然月**，由本类负责换算边界。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.infra.seq.BizNoGenerator;
import com.faze.infra.seq.BizNoRetry;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.admin.bo.SettlementQueryBO;
import com.faze.modules.trade.admin.vo.BackfillResultVO;
import com.faze.modules.trade.admin.vo.SettlementDetailVO;
import com.faze.modules.trade.admin.vo.SettlementGenerateResultVO;
import com.faze.modules.trade.admin.vo.SettlementItemVO;
import com.faze.modules.trade.admin.vo.SettlementRowVO;
import com.faze.modules.trade.admin.vo.SettlementSummaryVO;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.LawyerBalance;
import com.faze.modules.trade.entity.LawyerIncomeSettlement;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.SettlementStatus;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.LawyerIncomeSettlementMapper;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.mapper.SettlementAgg;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 律师收入结算服务（收入入账 / 回补 / 解冻 / 结算批次）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementService {

    /** 流水归属类型：律师。 */
    public static final String OWNER_LAWYER = "lawyer";

    /** 流水业务类型：收入入账（+净收入，同时冻结 7 天）。 */
    public static final String BIZ_INCOME = "income";

    /** 流水业务类型：收入解冻（delta=0，冻结转可用）。 */
    public static final String BIZ_INCOME_UNFREEZE = "income_unfreeze";

    /** 配置键：冻结天数（默认 7）。 */
    private static final String CFG_FREEZE_DAYS = "settlement.freeze_days";

    /** 冻结天数默认值（配置缺失/非法时用）。 */
    private static final int DEFAULT_FREEZE_DAYS = 7;

    /** 单笔回补 / 解冻的批大小上限（防止一次扫爆内存）。 */
    private static final int BATCH_LIMIT = 200;

    /** 账期格式：`yyyy-MM`。写死正则，非法格式统一抛 400001。 */
    private static final Pattern PERIOD_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    /** 业务时区：账期按东八区自然月切。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final LawyerBalanceLocker lawyerBalanceLocker;
    private final BalanceFlowMapper balanceFlowMapper;
    private final OrdersMapper ordersMapper;
    private final LawyerIncomeSettlementMapper settlementMapper;
    private final LawyerQueryService lawyerQueryService;
    private final SysConfigQueryService sysConfigQueryService;
    private final BizNoGenerator bizNoGenerator;
    private final JsonMapper jsonMapper;

    // ── E1：收入入账 ────────────────────────────────────────────────────────

    /**
     * 订单完成时把律师收入记进「待结算」，并把 `frozen_until` 设为完成时间 + N 天。
     *
     * <p><b>这是改段 2 的代码</b>：段 2 的 complete 只把状态推到 COMPLETED、只在订单上记数字，
     * 不写流水。本方法在同一事务里补齐入账。</p>
     *
     * <p>金额**只读 `orders.lawyer_income_cents`**（段 2 已按当次费率算好），
     * 禁止用 `amount × 费率` 重算——费率改过就会与订单上的数不一致（工作流 E1 第 6 条）。</p>
     *
     * @param order 已进入 COMPLETED 的订单（调用方负责事务；本方法可独立调用）
     * @return 真的入账了返回 true；幂等跳过 / 金额为 0 / 参数不全返回 false
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean creditIncome(Orders order) {
        if (order == null || order.getId() == null || order.getLawyerId() == null) {
            log.warn("收入入账跳过：订单或承办律师为空 order={}", order == null ? null : order.getId());
            return false;
        }
        long delta = nz(order.getLawyerIncomeCents());
        if (delta <= 0) {
            // R2：段 2 早期订单可能没算 lawyer_income_cents。**绝不用现在的费率重算**，
            // 这类单跳过并在 E2 的核对结果里单独暴露，交负责人人工定。
            log.warn("收入入账跳过：lawyer_income_cents<=0 orderId={} lawyerId={}",
                    order.getId(), order.getLawyerId());
            return false;
        }
        if (hasIncomeFlow(order.getLawyerId(), order.getId())) {
            log.debug("收入已入账，跳过 orderId={}", order.getId());
            return false;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int freezeDays = freezeDays();
        LocalDateTime base = order.getFinishedAt() == null ? now : order.getFinishedAt();
        LocalDateTime frozenUntil = base.plusDays(freezeDays);

        // 任务 1：锁住余额行（不存在则惰性建 0 行）
        LawyerBalance balance = lawyerBalanceLocker.lock(order.getLawyerId());
        // 任务 2：锁内二次幂等——挡住「检查通过后、加锁前」被别人插入的并发窗口
        if (hasIncomeFlow(order.getLawyerId(), order.getId())) {
            log.debug("收入已入账（锁内复查），跳过 orderId={}", order.getId());
            return false;
        }
        long oldAvailable = nz(balance.getAvailableCents());
        long oldFrozen = nz(balance.getFrozenCents());
        long newFrozen = oldFrozen + delta;
        long newTotal = oldAvailable + newFrozen;

        // 任务 3：先写流水（余额的权威来源），balance_after 记**总余额**
        BalanceFlow flow = new BalanceFlow();
        flow.setOwnerType(OWNER_LAWYER);
        flow.setOwnerId(order.getLawyerId());
        flow.setBizType(BIZ_INCOME);
        flow.setBizId(order.getId());
        flow.setDeltaCents(delta);
        flow.setBalanceAfter(newTotal);
        flow.setFrozenUntil(frozenUntil);
        flow.setRemark("订单完成入账");
        flow.setCreatedAt(now);
        try {
            balanceFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            // uk_flow_biz 兜底：并发重复入账时只成功一次（工作流 E1 第 2 条：捕获后忽略，不是报错）
            log.warn("收入入账撞唯一键，忽略重复调用 orderId={} lawyerId={}", order.getId(), order.getLawyerId());
            return false;
        }

        // 任务 4：带 version 落库（已锁行，version 不会变）
        lawyerBalanceLocker.apply(balance, null, newFrozen,
                nz(balance.getTotalIncomeCents()) + delta);
        log.info("律师收入入账 lawyerId={} orderId={} delta={} frozenUntil={}",
                order.getLawyerId(), order.getId(), delta, frozenUntil);
        return true;
    }

    // ── E2：回补 ────────────────────────────────────────────────────────────

    /**
     * 回补段 2 期间已完成但没有 income 流水的订单（管理员人工触发）。
     *
     * <p>为什么是「接口 + 只读核对 SQL」而不是自动执行：回补会真实增加律师总余额，
     * 属于不可逆的资金操作，必须人工确认（工作流 E2 第 5 条）。</p>
     *
     * @param limit 单次最多回补多少笔（≤0 时用默认批量）
     * @return 回补结果（扫描数 / 入账数 / 跳过数 / 入账金额）
     */
    @OperationLog(module = "trade", action = "SETTLEMENT_BACKFILL", targetType = "BALANCE_FLOW")
    @Transactional(rollbackFor = Exception.class)
    public BackfillResultVO backfillIncome(int limit) {
        int batch = limit <= 0 ? BATCH_LIMIT : Math.min(limit, BATCH_LIMIT);
        List<Orders> candidates = ordersMapper.selectCompletedWithoutIncome(batch);
        BackfillResultVO result = new BackfillResultVO();
        result.setScanned(candidates.size());
        result.setCredited(0);
        result.setSkipped(0);
        result.setCreditedCents(0L);
        for (Orders order : candidates) {
            if (creditIncome(order)) {
                result.setCredited(result.getCredited() + 1);
                result.setCreditedCents(result.getCreditedCents() + nz(order.getLawyerIncomeCents()));
            } else {
                result.setSkipped(result.getSkipped() + 1);
            }
        }
        log.info("收入回补完成 scanned={} credited={} skipped={} creditedCents={}",
                result.getScanned(), result.getCredited(), result.getSkipped(), result.getCreditedCents());
        return result;
    }

    /**
     * 回补只读核对：待补订单数与金额合计（不写任何数据）。
     *
     * <p>工作流 E2 要求「先出只读的核对 SQL，人工确认后再跑回补」。本方法是它的服务端版，
     * 与 `scripts/repair-lawyer-income.sql` 的口径一致。</p>
     *
     * @return 待补订单数与金额合计
     */
    public BackfillResultVO pendingBackfill() {
        SettlementAgg agg = ordersMapper.countCompletedWithoutIncome();
        BackfillResultVO result = new BackfillResultVO();
        result.setScanned(agg == null || agg.getOrderCount() == null ? 0 : agg.getOrderCount());
        result.setCredited(0);
        result.setSkipped(0);
        result.setCreditedCents(0L);
        result.setPendingCents(agg == null ? 0L : nz(agg.getIncomeCents()) - nz(agg.getFeeCents()));
        return result;
    }

    // ── E3：解冻 ────────────────────────────────────────────────────────────

    /**
     * 扫一批到期收入流水，把 `frozen_cents` 转成 `available_cents`。
     *
     * <p>由 `LawyerUnfreezeTask`（`task_queue` 的 `lawyer_unfreeze` 消费者）每 10 分钟调用一次。
     * 转移关系：`frozen -= income.delta`、`available += income.delta`，**总余额不变**，
     * 所以解冻流水的 `delta_cents = 0`。</p>
     *
     * @param limit 单批上限（≤0 时用默认批量）
     * @return 本次成功解冻的笔数
     */
    @Transactional(rollbackFor = Exception.class)
    public int unfreezeDue(int limit) {
        int batch = limit <= 0 ? BATCH_LIMIT : Math.min(limit, BATCH_LIMIT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BalanceFlow> due = balanceFlowMapper.selectDueUnfreezeIncomes(now, batch);
        int done = 0;
        for (BalanceFlow income : due) {
            if (unfreezeOne(income, now)) {
                done++;
            }
        }
        if (done > 0) {
            log.info("律师收入解冻完成 count={}", done);
        }
        return done;
    }

    /**
     * 解冻单笔收入流水（内部方法，调用方保证已在事务内）。
     *
     * @param income 到期的 income 流水
     * @param now    当前 UTC 时间
     * @return 真的解冻了返回 true；数据异常或并发重复返回 false
     */
    private boolean unfreezeOne(BalanceFlow income, LocalDateTime now) {
        long delta = nz(income.getDeltaCents());
        if (delta <= 0) {
            return false;
        }
        LawyerBalance balance = lawyerBalanceLocker.lock(income.getOwnerId());
        long oldAvailable = nz(balance.getAvailableCents());
        long oldFrozen = nz(balance.getFrozenCents());
        if (oldFrozen < delta) {
            // 冻结额不够说明账已经不平了：不硬改，留给对账巡检报差异（R10）
            log.error("解冻失败：冻结额不足 lawyerId={} incomeId={} frozen={} need={}",
                    income.getOwnerId(), income.getId(), oldFrozen, delta);
            return false;
        }
        long newFrozen = oldFrozen - delta;
        long newAvailable = oldAvailable + delta;
        BalanceFlow flow = new BalanceFlow();
        flow.setOwnerType(OWNER_LAWYER);
        flow.setOwnerId(income.getOwnerId());
        flow.setBizType(BIZ_INCOME_UNFREEZE);
        flow.setBizId(income.getId());
        flow.setDeltaCents(0L);
        flow.setBalanceAfter(newAvailable + newFrozen);
        flow.setFrozenUntil(null);
        flow.setRemark("解冻");
        flow.setCreatedAt(now);
        try {
            balanceFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            log.debug("该收入已解冻，跳过 incomeId={}", income.getId());
            return false;
        }
        lawyerBalanceLocker.apply(balance, newAvailable, newFrozen, null);
        return true;
    }

    // ── E4：结算批次 ────────────────────────────────────────────────────────

    /**
     * 生成结算批次（一位律师 + 一个账期一条）。
     *
     * <p>聚合口径是**两套来源交叉校验**：订单侧三个数来自 `orders`，
     * 应结金额来自 `balance_flow` 的 income 净额；必须满足
     * `settleCents == incomeCents − feeCents`，不成立就**整批回滚并抛 405026**——
     * 这是账务口径出问题的最早信号（工作流 E4 第 3 条，R1）。</p>
     *
     * @param period   账期 `yyyy-MM`
     * @param lawyerId 只生成这一位律师；null 表示该账期全部律师
     * @return 生成结果（成功 / 跳过 / 候选数；口径不一致时带 mismatchLawyerIds）
     */
    @OperationLog(module = "trade", action = "SETTLEMENT_GENERATE", targetType = "LAWYER_INCOME_SETTLEMENT")
    @Transactional(rollbackFor = Exception.class)
    public SettlementGenerateResultVO generate(String period, Long lawyerId) {
        YearMonth ym = parsePeriod(period);
        LocalDateTime beginUtc = periodBeginUtc(ym);
        LocalDateTime endUtc = periodEndUtc(ym);
        List<Long> targets = lawyerId != null
                ? List.of(lawyerId)
                : ordersMapper.selectSettlementLawyerIds(beginUtc, endUtc);

        SettlementGenerateResultVO result = new SettlementGenerateResultVO();
        result.setPeriod(ym.toString());
        result.setCandidates(targets.size());
        result.setGenerated(0);
        result.setSkipped(0);
        List<Long> mismatched = new ArrayList<>();

        for (Long target : targets) {
            SettlementAgg agg = ordersMapper.selectSettlementAgg(target, beginUtc, endUtc);
            int orderCount = agg == null || agg.getOrderCount() == null ? 0 : agg.getOrderCount();
            if (orderCount <= 0) {
                // 0 元单不生成（E4 第 7 条）
                continue;
            }
            long incomeCents = nz(agg.getIncomeCents());
            long feeCents = nz(agg.getFeeCents());
            long settleCents = nz(balanceFlowMapper.sumIncomeByOwnerAndRange(target, beginUtc, endUtc));
            if (settleCents != incomeCents - feeCents) {
                // 口径不一致：记错误日志并整批回滚，不许用 orders 的数去覆盖流水
                log.error("结算口径不一致，停止生成 period={} lawyerId={} settle={} income={} fee={}",
                        ym, target, settleCents, incomeCents, feeCents);
                mismatched.add(target);
                continue;
            }
            LawyerIncomeSettlement existing = settlementMapper.selectOne(
                    Wrappers.<LawyerIncomeSettlement>lambdaQuery()
                            .eq(LawyerIncomeSettlement::getLawyerId, target)
                            .eq(LawyerIncomeSettlement::getPeriod, ym.toString()));
            if (existing != null) {
                // uk_settlement_lawyer_period 命中：幂等跳过，不报错（E4 第 4 条）
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }
            insertSettlement(target, ym.toString(), orderCount, incomeCents, feeCents, settleCents);
            result.setGenerated(result.getGenerated() + 1);
        }
        if (!mismatched.isEmpty()) {
            result.setMismatchLawyerIds(mismatched);
            throw new SettlementMismatchException(result, mismatched);
        }
        log.info("结算批次生成完成 period={} candidates={} generated={} skipped={}",
                ym, result.getCandidates(), result.getGenerated(), result.getSkipped());
        return result;
    }

    /**
     * 结算批次列表（PC A-13，rows/total 分页）。
     *
     * @param query 查询入参
     * @return 分页结果
     */
    public TableDataInfo<SettlementRowVO> list(SettlementQueryBO query) {
        LambdaQueryWrapper<LawyerIncomeSettlement> wrapper = Wrappers.<LawyerIncomeSettlement>lambdaQuery()
                .eq(StringUtils.hasText(query.getPeriod()), LawyerIncomeSettlement::getPeriod, query.getPeriod())
                .eq(StringUtils.hasText(query.getStatus()), LawyerIncomeSettlement::getStatus, query.getStatus())
                .orderByDesc(LawyerIncomeSettlement::getCreatedAt)
                .orderByDesc(LawyerIncomeSettlement::getId);
        if (StringUtils.hasText(query.getLawyerKw())) {
            List<Long> ids = lawyerQueryService.searchIdsByKeyword(query.getLawyerKw());
            if (ids.isEmpty()) {
                // 关键词无匹配：直接返回空页（若写成 IN() 会查出 0 行但语义相反，这里显式短路）
                return TableDataInfo.of(List.of(), 0L);
            }
            wrapper.in(LawyerIncomeSettlement::getLawyerId, ids);
        }
        IPage<LawyerIncomeSettlement> page = settlementMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        List<SettlementRowVO> rows = toRows(page.getRecords());
        return TableDataInfo.of(rows, page.getTotal());
    }

    /**
     * 结算页三张统计卡。
     *
     * @return 待结算总额 / 本月已结算 / 涉及律师数
     */
    public SettlementSummaryVO summary() {
        SettlementSummaryVO vo = new SettlementSummaryVO();
        vo.setPendingTotalCents(sumSettled(
                SettlementStatus.PENDING.getCode(), null, null));
        LocalDate firstDay = YearMonth.now(BIZ_ZONE).atDay(1);
        LocalDateTime monthBeginUtc = firstDay.atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime monthEndUtc = firstDay.plusMonths(1).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        vo.setSettledThisMonthCents(sumSettled(
                SettlementStatus.SETTLED.getCode(), monthBeginUtc, monthEndUtc));
        QueryWrapper<LawyerIncomeSettlement> wrapper = new QueryWrapper<LawyerIncomeSettlement>()
                .select("COUNT(DISTINCT lawyer_id) AS lawyer_count")
                .eq("status", SettlementStatus.PENDING.getCode());
        List<Object> values = settlementMapper.selectObjs(wrapper);
        vo.setLawyerCount(values.isEmpty() || values.get(0) == null
                ? 0 : Math.toIntExact(((Number) values.get(0)).longValue()));
        return vo;
    }

    /**
     * 结算单详情（批次头 + 逐单明细，明细实时查 orders）。
     *
     * @param id 结算批次主键
     * @return 详情
     * @throws BusinessException 404001 批次不存在
     */
    public SettlementDetailVO detail(Long id) {
        LawyerIncomeSettlement entity = settlementMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "结算批次不存在");
        }
        SettlementRowVO head = toRows(List.of(entity)).get(0);
        YearMonth ym = parsePeriod(entity.getPeriod());
        List<Orders> orders = ordersMapper.selectList(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getLawyerId, entity.getLawyerId())
                .eq(Orders::getStatus, "completed")
                .isNull(Orders::getDeletedAt)
                .ge(Orders::getFinishedAt, periodBeginUtc(ym))
                .lt(Orders::getFinishedAt, periodEndUtc(ym))
                .orderByAsc(Orders::getFinishedAt)
                .orderByAsc(Orders::getId));
        List<SettlementItemVO> items = new ArrayList<>(orders.size());
        long total = 0L;
        for (Orders order : orders) {
            SettlementItemVO item = new SettlementItemVO();
            item.setOrderId(order.getId());
            item.setOrderNo(order.getOrderNo());
            item.setFinishedAt(order.getFinishedAt());
            item.setAmountCents(nz(order.getAmountCents()));
            item.setFeeCents(nz(order.getPlatformFeeCents()));
            item.setIncomeCents(nz(order.getLawyerIncomeCents()));
            items.add(item);
            total += nz(order.getLawyerIncomeCents());
        }
        SettlementDetailVO vo = new SettlementDetailVO();
        vo.setSettlement(head);
        vo.setItems(items);
        vo.setItemsTotalCents(total);
        vo.setConsistent(total == nz(head.getSettleCents()));
        return vo;
    }

    /**
     * 确认结算（`pending → settled`）。
     *
     * <p><b>确认不动钱</b>：只 CAS 改状态 + 写审计。不写 `balance_flow`、不改 `lawyer_balance`。
     * 这是工作流 §一.1 第 4 条出口的反向验证项（`E5-no-money-moved.txt` 就是验这个）。</p>
     *
     * @param id 结算批次主键
     * @throws BusinessException 404001 不存在 / 405025 状态非法（已被确认或驳回）
     */
    @OperationLog(module = "trade", action = "SETTLEMENT_CONFIRM", targetType = "LAWYER_INCOME_SETTLEMENT")
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        LawyerIncomeSettlement entity = settlementMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "结算批次不存在");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long operatorId = currentOperatorId();
        int rows = settlementMapper.update(null, Wrappers.<LawyerIncomeSettlement>lambdaUpdate()
                .set(LawyerIncomeSettlement::getStatus, SettlementStatus.SETTLED.getCode())
                .set(LawyerIncomeSettlement::getSettledBy, operatorId)
                .set(LawyerIncomeSettlement::getSettledAt, now)
                .set(LawyerIncomeSettlement::getUpdatedAt, now)
                .eq(LawyerIncomeSettlement::getId, id)
                .eq(LawyerIncomeSettlement::getStatus, SettlementStatus.PENDING.getCode()));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID, "结算单状态已被其他人修改");
        }
        AuditContext.mark("trade", "SETTLEMENT_CONFIRM", "LAWYER_INCOME_SETTLEMENT",
                String.valueOf(id),
                detailJson(Map.of(
                        "before", Map.of("status", SettlementStatus.PENDING.getCode()),
                        "after", Map.of("status", SettlementStatus.SETTLED.getCode()),
                        "settlementNo", String.valueOf(entity.getSettlementNo()),
                        "settleCents", nz(entity.getSettleCents()))));
        log.info("结算批次已确认（未动任何资金）settlementId={} no={} settle={}",
                id, entity.getSettlementNo(), entity.getSettleCents());
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 插入一条结算批次（调用方已确认无重复）。
     *
     * @param lawyerId    律师 id
     * @param period      账期
     * @param orderCount  订单数
     * @param incomeCents 订单收入合计
     * @param feeCents    服务费合计
     * @param settleCents 应结金额（流水净额）
     */
    private void insertSettlement(Long lawyerId, String period, int orderCount,
                                  long incomeCents, long feeCents, long settleCents) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LawyerIncomeSettlement entity = new LawyerIncomeSettlement();
        entity.setLawyerId(lawyerId);
        entity.setPeriod(period);
        entity.setOrderCount(orderCount);
        entity.setIncomeCents(incomeCents);
        entity.setFeeCents(feeCents);
        entity.setSettleCents(settleCents);
        entity.setStatus(SettlementStatus.PENDING.getCode());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            BizNoRetry.insertWithNo("uk_settlement_no", bizNoGenerator::nextSettlementNo, settlementNo -> {
                entity.setSettlementNo(settlementNo);
                return settlementMapper.insert(entity);
            });
        } catch (DuplicateKeyException e) {
            // 单号冲突已在 BizNoRetry 内换号重试，能走到这里的只剩 uk_settlement_lawyer_period：
            // 并发生成时另一个请求刚插了同律师同账期，按幂等跳过处理
            log.info("结算批次并发重复，跳过 lawyerId={} period={}", lawyerId, period);
        }
    }

    /**
     * 判断某订单是否已有 income 流水。
     *
     * @param lawyerId 律师 id
     * @param orderId  订单 id
     * @return 已入账返回 true
     */
    private boolean hasIncomeFlow(Long lawyerId, Long orderId) {
        Long count = balanceFlowMapper.selectCount(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerType, OWNER_LAWYER)
                .eq(BalanceFlow::getOwnerId, lawyerId)
                .eq(BalanceFlow::getBizType, BIZ_INCOME)
                .eq(BalanceFlow::getBizId, orderId));
        return count != null && count > 0;
    }

    /**
     * 读冻结天数配置。
     *
     * @return 天数；配置非法时用默认 7 天
     */
    private int freezeDays() {
        int days = sysConfigQueryService.getInt(CFG_FREEZE_DAYS, DEFAULT_FREEZE_DAYS);
        return days <= 0 ? DEFAULT_FREEZE_DAYS : days;
    }

    /**
     * 解析账期。
     *
     * @param period `yyyy-MM`
     * @return 年月
     * @throws BusinessException 400001 格式非法
     */
    private YearMonth parsePeriod(String period) {
        if (period == null || !PERIOD_PATTERN.matcher(period).matches()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "账期格式必须是 yyyy-MM");
        }
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "账期格式必须是 yyyy-MM");
        }
    }

    /**
     * 账期起点（东八区 1 日 0 点）换算成 UTC。
     *
     * @param ym 年月
     * @return UTC 时间（含）
     */
    private LocalDateTime periodBeginUtc(YearMonth ym) {
        return ym.atDay(1).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 账期终点（下月 1 日东八区 0 点）换算成 UTC。
     *
     * @param ym 年月
     * @return UTC 时间（不含）
     */
    private LocalDateTime periodEndUtc(YearMonth ym) {
        return ym.plusMonths(1).atDay(1).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 汇总某一状态的settle金额。
     *
     * @param status   状态
     * @param beginUtc 时间起（可空；空表示不限）
     * @param endUtc   时间止（可空）
     * @return 合计（分）
     */
    private long sumSettled(String status, LocalDateTime beginUtc, LocalDateTime endUtc) {
        QueryWrapper<LawyerIncomeSettlement> wrapper = new QueryWrapper<LawyerIncomeSettlement>()
                .select("COALESCE(SUM(settle_cents), 0) AS total")
                .eq("status", status);
        if (beginUtc != null && endUtc != null) {
            wrapper.ge("settled_at", beginUtc).lt("settled_at", endUtc);
        }
        List<Object> values = settlementMapper.selectObjs(wrapper);
        if (values.isEmpty() || values.get(0) == null) {
            return 0L;
        }
        Object raw = values.get(0);
        return raw instanceof Number number ? number.longValue() : Long.parseLong(raw.toString());
    }

    /**
     * 实体转列表行（批量取律师姓名/律所，避免 N+1）。
     *
     * @param rows 结算批次
     * @return 列表行
     */
    private List<SettlementRowVO> toRows(List<LawyerIncomeSettlement> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> lawyerIds = rows.stream()
                .map(LawyerIncomeSettlement::getLawyerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Lawyer> lawyers = new HashMap<>(lawyerQueryService.mapByIds(lawyerIds));
        List<SettlementRowVO> result = new ArrayList<>(rows.size());
        for (LawyerIncomeSettlement row : rows) {
            SettlementRowVO vo = new SettlementRowVO();
            vo.setId(row.getId());
            vo.setSettlementNo(row.getSettlementNo());
            vo.setStatus(row.getStatus());
            vo.setLawyerId(row.getLawyerId());
            vo.setPeriod(row.getPeriod());
            vo.setOrderCount(row.getOrderCount());
            vo.setIncomeCents(row.getIncomeCents());
            vo.setFeeCents(row.getFeeCents());
            vo.setSettleCents(row.getSettleCents());
            vo.setRemark(row.getRemark());
            vo.setSettledAt(row.getSettledAt());
            vo.setCreatedAt(row.getCreatedAt());
            Lawyer lawyer = lawyers.get(row.getLawyerId());
            if (lawyer != null) {
                vo.setLawyerName(lawyer.getRealName());
                vo.setLawFirm(lawyer.getLawFirm());
                vo.setLawyerAvatar(lawyer.getAvatarUrl());
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 取当前管理端操作人 id（审计上下文里由过滤器写入）。
     *
     * @return 操作人 id；非管理端调用时返回 null
     */
    private Long currentOperatorId() {
        AuditContext.AuditInfo info = AuditContext.current();
        return info == null ? null : info.getOperatorId();
    }

    /**
     * 把 Map 序列化成审计 detail JSON（用 Jackson 而不是字符串拼接：
     * 理由里的引号会把拼接出来的 JSON 弄坏，这是 C/D 组实测发现的隐患）。
     *
     * @param payload 结构化明细
     * @return JSON 字符串；序列化失败时返回 null（审计不该因为 detail 挂掉而失败）
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

    /**
     * 结算口径不一致异常：带生成结果，供 Controller 返回「哪些律师对不上」。
     *
     * <p>**必须继承 BusinessException**：只有 BusinessException 才会被全局异常处理器
     * 转成 `R` 响应并触发事务回滚；换成 RuntimeException 客户端只会看到一个 500。</p>
     */
    public static class SettlementMismatchException extends BusinessException {

        /** 序列化版本号（异常可能跨进程，虽然实际不会，但规范要求显式给）。 */
        private static final long serialVersionUID = 1L;

        /** 已生成（未回滚前）的结果，用于日志与错误消息。 */
        private final transient SettlementGenerateResultVO result;

        /**
         * 构造异常。
         *
         * @param result     生成结果
         * @param mismatched 口径不一致的律师 id
         */
        public SettlementMismatchException(SettlementGenerateResultVO result, List<Long> mismatched) {
            super(ErrorCode.TRADE_SETTLEMENT_MISMATCH,
                    "结算口径不一致，已回滚整批：lawyerIds=" + mismatched);
            this.result = result;
        }

        /**
         * 取生成结果。
         *
         * @return 生成结果（含 mismatchLawyerIds）
         */
        public SettlementGenerateResultVO getResult() {
            return result;
        }
    }
}
