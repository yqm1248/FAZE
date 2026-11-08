/*
 * 职责：渠道账单与本地充值记录的逐笔比对、差异落库、channel_only 自动补单（第 6 段 D4）。
 * 调用方：modules/trade/service/ReconcileTaskService（任务 handler）。
 * 数据来源：ReconcileBillService（账单字节）、BillParserFactory（解析）、
 *           recharge_record（本地单）、RechargeSettleService（补入账）、reconciliation_diff（落库）。
 * 注意：
 *   1. **匹配主键是「商户单号 = order_no」**，`channel_txn_id` 只做留痕与去重（D4 第 2 条）。
 *   2. **三类差异的口径**（D4 第 2 条表格）：
 *      · `channel_only`：账单有、本地无（或本地状态不是 success）→ 尝试自动补单；
 *      · `local_only`：本地 success、账单无 → **不自动处理**（疑似伪造回调，必须人工核）；
 *      · `amount_mismatch`：两边都有且金额不等 → **不自动处理**。
 *   3. **自动补单不建新单**（与 D4 原文的偏差，记入 §十一）：账单里没有 `user_id`，
 *      「本地完全无单」时**不知道该给谁入账**。所以：
 *      · 本地有单（pending / closed / failed）→ 调 `RechargeSettleService.settleByCallback`，
 *        它会走 **晚到回调补偿单**（建 `原单号-C` 的 success 单 + 入账），这正是 D4 说的
 *        「建新 recharge_record（status='success'）+ 走 C3 入账」，且用户身份已知；
 *      · 本地完全无单 → 只落差异（`pending`），detail 里写明缺 `user_id`，人工核对。
 *   4. **补偿单不参与 local_only 判定**：补偿单号是 `原单号-C`，账单里永远不会有它；
 *      若把补偿单当本地 success 单去比，每次对账都会误报一条 `local_only`。
 *      同理，账单行若发现「原单已补偿」（存在 `order_no + '-C'` 的 success 单），也判无差异。
 *   5. **幂等重跑**：落库前按 `bill_date + channel + channel_txn_id + diff_type` 查重
 *      （`channel_txn_id` 为空时改用 `recharge_id`）；重复跑不产生重复差异行（D4 验收）。
 *      表上有唯一键 `uk_diff_day_channel_txn_type`，但 NULL 不拦重复，所以必须「先查后插」。
 *   6. **不做全局事务**：差异先落库（`pending`），再逐条尝试补单，成功才回写 `auto_fixed`。
 *      这样补单失败不会把已识别的差异一起回滚——差异才是人工处理的入口。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.pay.reconcile.BillCsvSupport;
import com.faze.infra.pay.reconcile.BillParserFactory;
import com.faze.infra.pay.reconcile.BillRow;
import com.faze.infra.pay.reconcile.ReconcileBillParser;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.entity.ReconciliationDiff;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.mapper.ReconciliationDiffMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 逐笔比对服务（D4）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconcileCompareService {

    /** 差异类型：渠道有、本地无。 */
    public static final String DIFF_CHANNEL_ONLY = "channel_only";
    /** 差异类型：本地有、渠道无。 */
    public static final String DIFF_LOCAL_ONLY = "local_only";
    /** 差异类型：金额不一致。 */
    public static final String DIFF_AMOUNT_MISMATCH = "amount_mismatch";

    /** 差异状态：待处理。 */
    private static final String STATUS_PENDING = "pending";
    /** 差异状态：已自动修复（channel_only 补单成功）。 */
    private static final String STATUS_AUTO_FIXED = "auto_fixed";

    /** 补偿单号后缀（与 RechargeSettleService#nextCompensationNo 必须一致）。 */
    private static final String COMPENSATION_SUFFIX = "-C";

    private final ReconcileBillService reconcileBillService;
    private final BillParserFactory billParserFactory;
    private final RechargeRecordMapper rechargeRecordMapper;
    private final ReconciliationDiffMapper reconciliationDiffMapper;
    private final RechargeSettleService rechargeSettleService;
    private final JsonMapper jsonMapper;

    /**
     * 比对结果。
     *
     * @param billRows       账单数据行数
     * @param localRows      本地成功单数
     * @param channelOnly     渠道有本地无的行数
     * @param localOnly       本地有渠道无的行数
     * @param amountMismatch  金额不一致的行数
     * @param written         实际新落库的差异行数（去重后）
     * @param autoFixed       自动补单成功的行数
     */
    public record CompareResult(int billRows, int localRows, int channelOnly, int localOnly,
                                int amountMismatch, int written, int autoFixed) {
    }

    /**
     * 比对某日某渠道的账单与本地记录。
     *
     * <p>副作用：写 `reconciliation_diff`；`channel_only` 且本地有单时**补入账**并回写状态。</p>
     *
     * @param channel  渠道编码
     * @param billDate 账单日期（东八区）
     * @return 比对结果
     * @throws BusinessException 405028 账单不存在或无法解析
     */
    public CompareResult compare(String channel, LocalDate billDate) {
        // ① 读账单并解析
        byte[] bill = reconcileBillService.loadLatestBill(channel, billDate);
        ReconcileBillParser parser = billParserFactory.get(channel);
        List<BillRow> billRows = parser.parse(bill);

        // ② 本地单：按单号建索引，覆盖账单日前后各一天（跨零点支付的单，created_at 与 paid_at 不同日）
        LocalDate from = billDate.minusDays(1);
        LocalDate to = billDate.plusDays(1);
        List<RechargeRecord> localRecords = rechargeRecordMapper.selectList(
                Wrappers.<RechargeRecord>lambdaQuery()
                        .eq(RechargeRecord::getChannel, channel)
                        .ge(RechargeRecord::getCreatedAt, BillCsvSupport.startOfDayUtc(from))
                        .lt(RechargeRecord::getCreatedAt, BillCsvSupport.startOfNextDayUtc(to)));
        Map<String, RechargeRecord> localByOrderNo = new HashMap<>(localRecords.size() * 2);
        // 两轮建索引（顺序不能反）：
        //   第一轮放真实单；第二轮用**补偿单**（`原单号-C`，success）覆盖原单的索引项。
        //   因为「原单已 closed + 补偿单 success」意味着这笔钱已经入过账，账单行命中时
        //   应当拿补偿单去比金额，而不是把原单的 closed 状态判成 channel_only 再补一次。
        for (RechargeRecord record : localRecords) {
            if (!record.getOrderNo().endsWith(COMPENSATION_SUFFIX)) {
                localByOrderNo.put(record.getOrderNo(), record);
            }
        }
        for (RechargeRecord record : localRecords) {
            if (record.getOrderNo().endsWith(COMPENSATION_SUFFIX)
                    && RechargeStatus.SUCCESS.getCode().equals(record.getStatus())) {
                String origin = record.getOrderNo()
                        .substring(0, record.getOrderNo().length() - COMPENSATION_SUFFIX.length());
                localByOrderNo.put(origin, record);
            }
        }

        // ③ 账单侧逐笔比（只比渠道成功的交易；退款/失败行不参与入账比对）
        List<ReconciliationDiff> candidates = new ArrayList<>();
        int channelOnly = 0;
        int amountMismatch = 0;
        for (BillRow row : billRows) {
            if (!row.isSuccess()) {
                continue;
            }
            RechargeRecord local = localByOrderNo.get(row.getOrderNo());
            if (local == null) {
                channelOnly++;
                candidates.add(buildDiff(billDate, channel, DIFF_CHANNEL_ONLY, row, null,
                        row.getAmountCents(),
                        "本地无单（缺 user_id，无法自动补单），需人工核对渠道交易号"));
                continue;
            }
            if (RechargeStatus.SUCCESS.getCode().equals(local.getStatus())) {
                long localAmount = local.getAmountCents() == null ? 0L : local.getAmountCents();
                if (localAmount != row.getAmountCents()) {
                    amountMismatch++;
                    candidates.add(buildDiff(billDate, channel, DIFF_AMOUNT_MISMATCH, row, local,
                            row.getAmountCents() - localAmount,
                            "渠道金额与本地不一致（差额 = 渠道 − 本地）"));
                }
                // 金额一致 → 无差异
                continue;
            }
            // 本地有单但不是 success（pending / closed / failed）：渠道已收款、平台未入账
            channelOnly++;
            candidates.add(buildDiff(billDate, channel, DIFF_CHANNEL_ONLY, row, local,
                    row.getAmountCents(),
                    "本地单状态为 " + local.getStatus() + "，渠道已收款，需补入账"));
        }

        // ④ 本地侧：该日 success 单不在账单里（排除补偿单）
        java.util.Set<String> billOrderNos = new java.util.HashSet<>();
        for (BillRow row : billRows) {
            billOrderNos.add(row.getOrderNo());
        }
        int localOnly = 0;
        for (RechargeRecord record : localRecords) {
            if (!RechargeStatus.SUCCESS.getCode().equals(record.getStatus())) {
                continue;
            }
            if (!inBillDay(record, billDate)) {
                continue;
            }
            if (record.getOrderNo().endsWith(COMPENSATION_SUFFIX)) {
                // 补偿单的账已由原单的渠道交易覆盖，账单里不会有它
                continue;
            }
            if (billOrderNos.contains(record.getOrderNo())) {
                continue;
            }
            localOnly++;
            candidates.add(buildDiff(billDate, channel, DIFF_LOCAL_ONLY, null, record,
                    -(record.getAmountCents() == null ? 0L : record.getAmountCents()),
                    "账单里没有这笔交易（疑似伪造回调或渠道退款未同步），必须人工核"));
        }

        // ⑤ 落库（先查后插，幂等重跑不产生重复行）
        int written = 0;
        List<ReconciliationDiff> inserted = new ArrayList<>();
        for (ReconciliationDiff diff : candidates) {
            if (isDuplicate(diff)) {
                continue;
            }
            try {
                reconciliationDiffMapper.insert(diff);
                written++;
                inserted.add(diff);
            } catch (DuplicateKeyException e) {
                // 唯一键兜底（并发跑两次比对时）
                log.info("差异行已存在（唯一键），跳过 diffType={} txn={}",
                        diff.getDiffType(), diff.getChannelTxnId());
            }
        }

        // ⑥ channel_only 且本地有单 → 尝试补入账；成功则回写 auto_fixed
        int autoFixed = 0;
        for (ReconciliationDiff diff : inserted) {
            if (!DIFF_CHANNEL_ONLY.equals(diff.getDiffType()) || diff.getRechargeId() == null) {
                continue;
            }
            if (tryAutoFix(diff)) {
                autoFixed++;
            }
        }

        CompareResult result = new CompareResult(billRows.size(), localRecords.size(), channelOnly,
                localOnly, amountMismatch, written, autoFixed);
        log.info("对账比对完成 channel={} billDate={} {}", channel, billDate, result);
        return result;
    }

    /**
     * 尝试对一条 `channel_only` 差异补入账。
     *
     * @param diff 差异行（`rechargeId` 非空、`channelTxnId` 来自账单）
     * @return true 表示补单成功并已回写 `auto_fixed`
     */
    private boolean tryAutoFix(ReconciliationDiff diff) {
        // 从 detail 里取账单的支付时间（账单行才有 paidAt）
        LocalDateTime paidAt = extractPaidAt(diff.getDetail());
        try {
            RechargeSettleService.Outcome outcome = rechargeSettleService.settleByCallback(
                    orderNoOf(diff), diff.getChannelTxnId(), diff.getAmountCents(), paidAt, null);
            if (outcome == RechargeSettleService.Outcome.SETTLED
                    || outcome == RechargeSettleService.Outcome.COMPENSATED
                    || outcome == RechargeSettleService.Outcome.ALREADY_SETTLED) {
                markAutoFixed(diff);
                log.info("对账自动补单成功 diffId={} outcome={}", diff.getId(), outcome);
                return true;
            }
            if (outcome == RechargeSettleService.Outcome.AMOUNT_MISMATCH) {
                // 补单时金额对不上：说明比对阶段拿到的本地金额与当前不一致，转人工
                log.warn("对账自动补单金额不一致，转人工 diffId={}", diff.getId());
            }
            return false;
        } catch (RuntimeException e) {
            // 单条补单失败不影响其它差异：差异行保持 pending，人工处理
            log.error("对账自动补单失败，差异保留 pending diffId={}", diff.getId(), e);
            return false;
        }
    }

    /**
     * 回写差异行状态为 `auto_fixed`。
     *
     * @param diff 差异行
     */
    private void markAutoFixed(ReconciliationDiff diff) {
        ReconciliationDiff patch = new ReconciliationDiff();
        patch.setId(diff.getId());
        patch.setStatus(STATUS_AUTO_FIXED);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        reconciliationDiffMapper.updateById(patch);
    }

    /**
     * 判断差异行是否已存在（幂等重跑）。
     *
     * @param diff 待落库的差异行
     * @return true 表示已存在
     */
    private boolean isDuplicate(ReconciliationDiff diff) {
        LambdaQueryWrapper<ReconciliationDiff> wrapper = Wrappers.<ReconciliationDiff>lambdaQuery()
                .eq(ReconciliationDiff::getBillDate, diff.getBillDate())
                .eq(ReconciliationDiff::getChannel, diff.getChannel())
                .eq(ReconciliationDiff::getDiffType, diff.getDiffType());
        if (diff.getChannelTxnId() != null) {
            wrapper.eq(ReconciliationDiff::getChannelTxnId, diff.getChannelTxnId());
        } else {
            // 唯一键里 channel_txn_id 为 NULL 时不拦重复，用 recharge_id 兜底
            wrapper.isNull(ReconciliationDiff::getChannelTxnId)
                    .eq(diff.getRechargeId() != null, ReconciliationDiff::getRechargeId,
                            diff.getRechargeId());
        }
        Long count = reconciliationDiffMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    /**
     * 构造一条差异行（表是段 5 建的，本段只写）。
     *
     * @param billDate   账单日期
     * @param channel    渠道
     * @param diffType   差异类型
     * @param billRow    账单行（local_only 时为 null）
     * @param local      本地单（本地无单时为 null）
     * @param amountCents 差异金额（分，可正可负）
     * @param reason     差异说明（落进 detail）
     * @return 差异行
     */
    private ReconciliationDiff buildDiff(LocalDate billDate, String channel, String diffType,
                                         BillRow billRow, RechargeRecord local,
                                         long amountCents, String reason) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ReconciliationDiff diff = new ReconciliationDiff();
        diff.setBillDate(billDate);
        diff.setChannel(channel);
        diff.setDiffType(diffType);
        diff.setChannelTxnId(billRow == null ? (local == null ? null : local.getChannelTxnId())
                : billRow.getChannelTxnId());
        diff.setRechargeId(local == null ? null : local.getId());
        diff.setAmountCents(amountCents);
        diff.setDetail(buildDetail(billRow, local, reason));
        diff.setStatus(STATUS_PENDING);
        // `found_at` 是「这条差异什么时候被发现的」：段 5 的恒等式巡检也写这个字段，
        // B4 表格第 8 列「发现时间」读的就是它。D4 不写的话这一列在页面与 CSV 里都是空的
        // ——第一版确实漏了，导出实测才发现。
        diff.setFoundAt(now);
        diff.setCreatedAt(now);
        diff.setUpdatedAt(now);
        return diff;
    }

    /**
     * 构造 `detail` JSON：账单行原文（已脱敏，只有单号/金额/时间/状态）+ 本地行快照 + 原因。
     *
     * @param billRow 账单行（可为 null）
     * @param local   本地单（可为 null）
     * @param reason  原因
     * @return JSON 文本
     */
    private String buildDetail(BillRow billRow, RechargeRecord local, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        if (billRow != null) {
            Map<String, Object> bill = new LinkedHashMap<>();
            bill.put("channelTxnId", billRow.getChannelTxnId());
            bill.put("orderNo", billRow.getOrderNo());
            bill.put("amountCents", billRow.getAmountCents());
            bill.put("paidAt", billRow.getPaidAt() == null ? null : billRow.getPaidAt().toString());
            bill.put("status", billRow.getStatus());
            detail.put("bill", bill);
        }
        if (local != null) {
            Map<String, Object> local0 = new LinkedHashMap<>();
            local0.put("rechargeId", local.getId());
            local0.put("orderNo", local.getOrderNo());
            local0.put("amountCents", local.getAmountCents());
            local0.put("status", local.getStatus());
            local0.put("paidAt", local.getPaidAt() == null ? null : local.getPaidAt().toString());
            detail.put("local", local0);
        }
        return jsonMapper.writeValueAsString(detail);
    }

    /**
     * 从 detail JSON 里取账单支付时间（自动补单要写 `paid_at`）。
     *
     * @param detail detail JSON
     * @return 支付时间（UTC）；取不到返回 null（settle 会用当前时间）
     */
    private LocalDateTime extractPaidAt(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            var node = jsonMapper.readTree(detail).path("bill").path("paidAt");
            return node.isMissingNode() || node.isNull() ? null : LocalDateTime.parse(node.asString());
        } catch (RuntimeException e) {
            log.warn("差异 detail 里取 paidAt 失败，自动补单用当前时间");
            return null;
        }
    }

    /**
     * 从差异行取平台单号（`orderNoOf` 需要按 `rechargeId` 反查）。
     *
     * @param diff 差异行
     * @return 平台单号
     */
    private String orderNoOf(ReconciliationDiff diff) {
        RechargeRecord record = rechargeRecordMapper.selectById(diff.getRechargeId());
        if (record == null) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_DIFF_PENDING,
                    "差异行关联的充值单不存在 id=" + diff.getRechargeId());
        }
        return record.getOrderNo();
    }

    /**
     * 本地单是否属于账单日（按 `paid_at` 归日，与账单口径一致）。
     *
     * @param record   本地单
     * @param billDate 账单日期
     * @return true 表示属于该日
     */
    private boolean inBillDay(RechargeRecord record, LocalDate billDate) {
        LocalDateTime paidAt = record.getPaidAt();
        if (paidAt == null) {
            return false;
        }
        return !paidAt.isBefore(BillCsvSupport.startOfDayUtc(billDate))
                && paidAt.isBefore(BillCsvSupport.startOfNextDayUtc(billDate));
    }
}
