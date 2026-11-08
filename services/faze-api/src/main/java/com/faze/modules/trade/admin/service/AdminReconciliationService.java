/*
 * 职责：A-09 对账报表（恒等式巡检）——summary / diffs / run，三者共用同一套现算逻辑。
 * 调用方：modules/trade/admin/controller/AdminReconciliationController。
 * 数据来源：faze.balance_flow（权威）、faze.user_balance、faze.lawyer_balance（缓存）。
 * 注意：
 *   1. **不建 `reconciliation_diff` 表**（段 5 才建）：差异是内存态现算，
 *      接口每次调用都重新比对，不存在「上次对账结果」。
 *   2. **口径必须与 `deploy/verify-balance.ps1` 一致**（锚点 1-A7：页面与脚本同一结果）：
 *      从缓存表出发 LEFT JOIN 流水汇总，比「可用 + 冻结」；
 *      有流水但没有缓存行的 owner 不在本报告内（脚本同样不报）。
 *   3. 只读计算不加锁、不开事务；`run` 只多写一条 operation_log（action=RECONCILE_RUN）。
 *   4. 差异处置（改缓存 / 补流水）**不在本接口**：那是段 5 的差异工单；
 *      这里只报数，不静默纠正——静默纠正会掩盖问题（与 BalanceService 注释一致）。
 */
package com.faze.modules.trade.admin.service;

import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.modules.trade.admin.bo.ChannelDiffQueryBO;
import com.faze.modules.trade.admin.vo.ChannelDiffRowVO;
import com.faze.modules.trade.admin.vo.ReconciliationDiffVO;
import com.faze.modules.trade.admin.vo.ReconciliationSummaryVO;
import com.faze.modules.trade.entity.LawyerBalance;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.entity.ReconciliationDiff;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.LawyerBalanceMapper;
import com.faze.modules.trade.mapper.OwnerFlowSum;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.mapper.ReconciliationDiffMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.service.BalanceService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 对账服务（只读巡检）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminReconciliationService {

    private static final String MODULE = "trade";
    private static final String ACTION = "RECONCILE_RUN";

    /** 差异类型：账务恒等式不一致（段 5 H1 写入，段 6 的渠道差异用别的值）。 */
    private static final String DIFF_IDENTITY = "identity_mismatch";

    /** 巡检类型：缓存 vs 流水汇总。 */
    private static final String CHECK_IDENTITY = "identity_balance";

    /** 差异状态：待处理（V1 只写这一个值）。 */
    private static final String STATUS_OPEN = "open";

    /** 差异状态：待处理（段 6 的渠道差异用这个值，与 open 同义，见 pageChannelDiffs 注释）。 */
    private static final String STATUS_PENDING = "pending";

    /** 差异状态：已处理。 */
    private static final String STATUS_HANDLED = "handled";

    /** 处理备注长度上限（B4 第 6 条）。 */
    private static final int HANDLE_NOTE_MAX_LENGTH = 500;

    /** 业务时区：页面上输入的账单日期按东八区解释（`found_at` 存 UTC）。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    /** CSV 导出的时间格式（东八区）。 */
    private static final DateTimeFormatter FOUND_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BalanceFlowMapper balanceFlowMapper;
    private final UserBalanceMapper userBalanceMapper;
    private final LawyerBalanceMapper lawyerBalanceMapper;
    private final ReconciliationDiffMapper reconciliationDiffMapper;
    private final RechargeRecordMapper rechargeRecordMapper;
    private final JsonMapper jsonMapper;

    /**
     * 对账汇总（只读，不写审计）。
     *
     * @return 汇总（checkedOwners / balancedOwners / diffOwners / totalCheckedCents / checkedAt）
     */
    public ReconciliationSummaryVO summary() {
        return compute().summary();
    }

    /**
     * 差异列表（只读，不写审计）。
     *
     * @return 差异行；无差异返回空列表
     */
    public List<ReconciliationDiffVO> diffs() {
        return compute().diffs();
    }

    /**
     * 执行一次对账并留痕。
     *
     * <p>副作用：写一条 operation_log（action=RECONCILE_RUN，detail 含计数）。
     * **不修改任何余额数据**：对账失败只报差异，处置属段 5。</p>
     *
     * @return 本次对账汇总
     */
    @OperationLog(module = MODULE, action = ACTION)
    public ReconciliationSummaryVO run() {
        Result result = compute();
        // 段 5 H1：把差异落表（只写不处置）。先查后插，避免同一条差异每次巡检都堆一行。
        int written = persistDiffs(result.diffs());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("checkedOwners", result.summary().getCheckedOwners());
        detail.put("balancedOwners", result.summary().getBalancedOwners());
        detail.put("diffOwners", result.summary().getDiffOwners());
        detail.put("totalCheckedCents", result.summary().getTotalCheckedCents());
        detail.put("diffsWritten", written);
        AuditContext.mark(MODULE, ACTION, null, null, jsonMapper.writeValueAsString(detail));
        log.info("对账完成 checkedOwners={} diffOwners={} diffsWritten={}",
                result.summary().getCheckedOwners(), result.summary().getDiffOwners(), written);
        return result.summary();
    }

    /**
     * 把本次巡检发现的差异写进 `reconciliation_diff`（段 5 H1）。
     *
     * <p><b>只记录不处置</b>：不写 `handlerId` / `handledAt`，`status` 保持 `open`，
     * 也没有任何改状态的业务入口（H1 第 4 条）。差异的处置留给段 7 的治理工单。</p>
     *
     * <p>去重：同一主体同一天已有一条 `open` 的恒等式差异就跳过，否则每次跑巡检都会
     * 追加一行重复记录。<b>不改 A-09 页面</b>：页面仍读现算结果，本表只供排查留档。</p>
     *
     * @param diffs 本次差异行
     * @return 实际写入的行数
     */
    private int persistDiffs(List<ReconciliationDiffVO> diffs) {
        if (diffs.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int written = 0;
        for (ReconciliationDiffVO vo : diffs) {
            Long existing = reconciliationDiffMapper.selectCount(
                    Wrappers.<ReconciliationDiff>lambdaQuery()
                            .eq(ReconciliationDiff::getCheckType, CHECK_IDENTITY)
                            .eq(ReconciliationDiff::getBizType, vo.getOwnerType())
                            .eq(ReconciliationDiff::getBizId, vo.getOwnerId())
                            .eq(ReconciliationDiff::getStatus, STATUS_OPEN)
                            .ge(ReconciliationDiff::getFoundAt, now.toLocalDate().atStartOfDay()));
            if (existing != null && existing > 0) {
                continue;
            }
            ReconciliationDiff row = new ReconciliationDiff();
            row.setDiffType(DIFF_IDENTITY);
            row.setCheckType(CHECK_IDENTITY);
            row.setBizType(vo.getOwnerType());
            row.setBizId(vo.getOwnerId());
            row.setExpectedCents(vo.getFlowCents());
            row.setActualCents(vo.getCacheCents());
            row.setDiffCents(vo.getDiffCents());
            row.setAmountCents(vo.getDiffCents());
            row.setFoundAt(now);
            row.setStatus(STATUS_OPEN);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            reconciliationDiffMapper.insert(row);
            written++;
        }
        return written;
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 现算：缓存表逐行 vs 流水汇总。
     *
     * <p>顺序：先一次 GROUP BY 拿全部流水汇总（1 条 SQL），再分别扫两张缓存表；
     * 避免「每个 owner 查一次 SUM」的 N+1。</p>
     *
     * @return 汇总 + 差异行
     */
    private Result compute() {
        Map<String, Long> flowByOwner = loadFlowSums();
        List<ReconciliationDiffVO> diffs = new ArrayList<>();
        int checked = 0;
        long totalCache = 0L;
        // 任务 1：user_balance
        for (UserBalance row : userBalanceMapper.selectList(null)) {
            long cache = nonNull(row.getBalanceCents()) + nonNull(row.getFrozenCents());
            checked++;
            totalCache += cache;
            addIfDiff(diffs, BalanceService.OWNER_USER, row.getUserId(), cache, flowByOwner);
        }
        // 任务 2：lawyer_balance（口径不同：可用 + 冻结）
        for (LawyerBalance row : lawyerBalanceMapper.selectList(null)) {
            long cache = nonNull(row.getAvailableCents()) + nonNull(row.getFrozenCents());
            checked++;
            totalCache += cache;
            addIfDiff(diffs, BalanceService.OWNER_LAWYER, row.getLawyerId(), cache, flowByOwner);
        }
        return new Result(buildSummary(checked, diffs.size(), totalCache), List.copyOf(diffs));
    }

    /**
     * 一次查询取全部归属方的流水净额。
     *
     * @return key = ownerType:ownerId，value = 净额（分）
     */
    private Map<String, Long> loadFlowSums() {
        Map<String, Long> map = new HashMap<>();
        for (OwnerFlowSum row : balanceFlowMapper.selectOwnerSums()) {
            map.put(key(row.getOwnerType(), row.getOwnerId()), nonNull(row.getSumCents()));
        }
        return map;
    }

    /**
     * 比对一行缓存，有差异就加入结果。
     *
     * @param diffs       差异收集器
     * @param ownerType   user / lawyer
     * @param ownerId     归属主键
     * @param cacheCents  缓存值（可用 + 冻结）
     * @param flowByOwner 流水汇总
     */
    private void addIfDiff(List<ReconciliationDiffVO> diffs, String ownerType, Long ownerId,
                           long cacheCents, Map<String, Long> flowByOwner) {
        if (ownerId == null) {
            return;
        }
        long flow = flowByOwner.getOrDefault(key(ownerType, ownerId), 0L);
        long diff = cacheCents - flow;
        if (diff == 0L) {
            return;
        }
        ReconciliationDiffVO vo = new ReconciliationDiffVO();
        vo.setOwnerType(ownerType);
        vo.setOwnerId(ownerId);
        vo.setCacheCents(cacheCents);
        vo.setFlowCents(flow);
        vo.setDiffCents(diff);
        diffs.add(vo);
    }

    /**
     * 组装汇总。
     *
     * @param checked    检查行数
     * @param diffCount  差异行数
     * @param totalCache 缓存总额
     * @return 汇总
     */
    private ReconciliationSummaryVO buildSummary(int checked, int diffCount, long totalCache) {
        ReconciliationSummaryVO vo = new ReconciliationSummaryVO();
        vo.setCheckedOwners(checked);
        vo.setBalancedOwners(checked - diffCount);
        vo.setDiffOwners(diffCount);
        vo.setTotalCheckedCents(totalCache);
        vo.setCheckedAt(LocalDateTime.now(ZoneOffset.UTC));
        return vo;
    }

    /**
     * 归属方复合键。
     *
     * @param ownerType 类型
     * @param ownerId   主键
     * @return 形如 user:3
     */
    private String key(String ownerType, Long ownerId) {
        return ownerType + ":" + ownerId;
    }

    /**
     * Long → long 安全取值。
     *
     * @param value 包装值
     * @return 0 或原值
     */
    private long nonNull(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 对账现算结果。
     *
     * @param summary 汇总
     * @param diffs   差异行
     */
    private record Result(ReconciliationSummaryVO summary, List<ReconciliationDiffVO> diffs) {
    }

    // ── 第 6 段 B4：reconciliation_diff 差异工单（渠道对账 Tab） ─────────────

    /**
     * 分页查询差异工单（只读，不写审计）。
     *
     * <p>与上面的 `diffs()` **不是一回事**：`diffs()` 是恒等式巡检的内存态现算结果（Tab1），
     * 本方法是 `reconciliation_diff` 表的落库记录（Tab2，段 5 建表、段 6 起有数据）。</p>
     *
     * @param query 筛选条件（账单日期区间 / 渠道 / 差异类型 / 状态 / 页码）
     * @return 分页结果
     */
    public PageResult<ChannelDiffRowVO> pageChannelDiffs(ChannelDiffQueryBO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null ? 20 : Math.min(Math.max(query.getPageSize(), 1), 100);
        LambdaQueryWrapper<ReconciliationDiff> wrapper = buildChannelDiffWrapper(query)
                .orderByDesc(ReconciliationDiff::getId);
        Page<ReconciliationDiff> page = new Page<>(pageNum, pageSize);
        Page<ReconciliationDiff> result = reconciliationDiffMapper.selectPage(page, wrapper);

        List<ReconciliationDiff> rows = result.getRecords();
        Map<Long, String> orderNoById = loadOrderNos(rows);
        List<ChannelDiffRowVO> list = new ArrayList<>(rows.size());
        for (ReconciliationDiff row : rows) {
            ChannelDiffRowVO vo = new ChannelDiffRowVO();
            vo.setId(row.getId());
            vo.setBillDate(row.getBillDate());
            vo.setChannel(row.getChannel());
            vo.setDiffType(row.getDiffType());
            vo.setChannelTxnId(row.getChannelTxnId());
            vo.setRechargeId(row.getRechargeId());
            vo.setOrderNo(orderNoById.get(row.getRechargeId()));
            vo.setAmountCents(nonNull(row.getAmountCents()));
            vo.setDiffCents(nonNull(row.getDiffCents()));
            vo.setStatus(row.getStatus());
            vo.setFoundAt(row.getFoundAt());
            vo.setHandledAt(row.getHandledAt());
            list.add(vo);
        }
        PageResult<ChannelDiffRowVO> pageResult = new PageResult<>();
        pageResult.setList(list);
        pageResult.setTotal(result.getTotal());
        pageResult.setPageNum(pageNum);
        pageResult.setPageSize(pageSize);
        pageResult.setHasMore((long) pageNum * pageSize < result.getTotal());
        return pageResult;
    }

    /**
     * 东八区某日 00:00 对应的 UTC wall clock（`found_at` 按 UTC 存）。
     *
     * <p>例：东八区 2026-11-08 00:00 = UTC 2026-11-07 16:00。</p>
     *
     * @param day 东八区日期
     * @return UTC 的 LocalDateTime
     */
    private static LocalDateTime startOfDayUtc(LocalDate day) {
        return day.atStartOfDay(BIZ_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 构造渠道差异查询条件（列表与 CSV 导出共用，避免两处筛选口径漂移）。
     *
     * <p>日期筛选要**分两类口径**：
     * ① 渠道差异（`channel_only` / `local_only` / `amount_mismatch`）有账期，按 `bill_date`（DATE，无时区）；
     * ② 恒等式差异（`identity_mismatch`）是「余额 vs 流水」的账户级巡检，**没有账期概念**
     * （V510 把 `bill_date` 放宽为 NULL，注释写明「恒等式差异为 NULL」），只能按 `found_at`。
     * 只按 `bill_date` 筛会把 ② 全部筛掉——B4 §3 默认「昨日」、§5 又要求展示 4 类，两者互斥。
     * 这里写成 `bill_date 区间 OR (identity_mismatch AND found_at 区间)`。</p>
     *
     * @param query 筛选条件
     * @return 查询条件（不含排序与分页）
     */
    private LambdaQueryWrapper<ReconciliationDiff> buildChannelDiffWrapper(ChannelDiffQueryBO query) {
        LambdaQueryWrapper<ReconciliationDiff> wrapper = Wrappers.<ReconciliationDiff>lambdaQuery()
                .eq(StringUtils.hasText(query.getChannel()), ReconciliationDiff::getChannel, query.getChannel())
                .eq(StringUtils.hasText(query.getDiffType()), ReconciliationDiff::getDiffType, query.getDiffType());
        final LocalDate beginDate = query.getBeginDate();
        final LocalDate endDate = query.getEndDate();
        if (beginDate != null || endDate != null) {
            final LocalDateTime beginUtc = beginDate == null ? null : startOfDayUtc(beginDate);
            // 用 `< 次日 00:00` 而不是 `<= 当日 23:59:59`：DATETIME 无小数位时 MySQL 会把
            // 23:59:59.999999999 四舍五入到次日，`<=` 反而漏掉边界内的行。
            final LocalDateTime endExclusiveUtc = endDate == null ? null : startOfDayUtc(endDate.plusDays(1));
            wrapper.and(w -> w
                    .nested(x -> {
                        if (beginDate != null) {
                            x.ge(ReconciliationDiff::getBillDate, beginDate);
                        }
                        if (endDate != null) {
                            x.le(ReconciliationDiff::getBillDate, endDate);
                        }
                    })
                    .or(y -> {
                        y.eq(ReconciliationDiff::getDiffType, DIFF_IDENTITY);
                        if (beginUtc != null) {
                            y.ge(ReconciliationDiff::getFoundAt, beginUtc);
                        }
                        if (endExclusiveUtc != null) {
                            y.lt(ReconciliationDiff::getFoundAt, endExclusiveUtc);
                        }
                    }));
        }
        // 「待处理」在库里有两个值：段 5 的巡检写 open，段 6 的渠道差异写 pending。
        // 页面上是一个「待处理」选项，所以这里要 IN，而不是 eq——漏了 open 就永远查不到段 5 的差异。
        if (STATUS_PENDING.equals(query.getStatus())) {
            wrapper.in(ReconciliationDiff::getStatus, STATUS_OPEN, STATUS_PENDING);
        } else if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(ReconciliationDiff::getStatus, query.getStatus());
        }
        return wrapper;
    }

    /**
     * 导出渠道差异为 CSV（D4 第 4 条「差异报表能导出，导出的数据与接口一致」）。
     *
     * <p>与列表**共用同一份筛选条件**（`buildChannelDiffWrapper`），所以同一组筛选参数下
     * 导出的行与接口返回的行是同一批；差异只在「不分页」与「中文文案」。导出不加页码上限
     * 是因为对账差异是人工核对物料，分页导出会让第一次核对的人漏行。</p>
     *
     * <p>副作用：无（只读）。导出动作不写审计——它是只读查询的另一种输出，
     * 与「导出即敏感操作」的场景（如导出用户手机号）不同，这里导出的是对账差异。</p>
     *
     * @param query 筛选条件（页码字段被忽略）
     * @return CSV 文本（带 UTF-8 BOM，Excel 直接打开不乱码）
     */
    public byte[] exportChannelDiffs(ChannelDiffQueryBO query) {
        List<ReconciliationDiff> rows = reconciliationDiffMapper.selectList(
                buildChannelDiffWrapper(query).orderByDesc(ReconciliationDiff::getId));
        Map<Long, String> orderNoById = loadOrderNos(rows);
        List<String> headers = List.of("账单日期", "渠道", "差异类型", "渠道交易号",
                "本地单号", "差异金额(元)", "状态", "发现时间");
        List<List<Object>> data = new ArrayList<>(rows.size());
        for (ReconciliationDiff row : rows) {
            List<Object> line = new ArrayList<>(headers.size());
            line.add(row.getBillDate() == null ? null : row.getBillDate().toString());
            line.add(channelLabel(row.getChannel()));
            line.add(diffTypeLabel(row.getDiffType()));
            line.add(row.getChannelTxnId());
            line.add(orderNoById.get(row.getRechargeId()));
            line.add(centsToYuan(nonNull(row.getAmountCents())));
            line.add(statusLabel(row.getStatus()));
            line.add(formatFoundAt(row.getFoundAt()));
            data.add(line);
        }
        log.info("导出渠道差异 CSV rows={}", rows.size());
        // 统一走 common/export/CsvExporter（UTF-8 BOM + RFC 4180 + 防注入），不在这里拼 CSV
        return com.faze.common.export.CsvExporter.toBytes(headers, data);
    }

    /**
     * 渠道编码转中文（页面与导出共用）。
     *
     * @param channel 渠道编码
     * @return 中文；未知或空返回原值
     */
    private static String channelLabel(String channel) {
        if (channel == null) {
            return "";
        }
        return switch (channel) {
            case "wechat" -> "微信";
            case "alipay" -> "支付宝";
            case "mock" -> "Mock 通道";
            default -> channel;
        };
    }

    /**
     * 差异类型转中文（与 B4 §5 的表格一致）。
     *
     * @param diffType 差异类型
     * @return 中文；未知返回原值
     */
    private static String diffTypeLabel(String diffType) {
        if (diffType == null) {
            return "";
        }
        return switch (diffType) {
            case "channel_only" -> "渠道有、本地无";
            case "local_only" -> "本地有、渠道无";
            case "amount_mismatch" -> "金额不一致";
            case DIFF_IDENTITY -> "恒等式不平";
            default -> diffType;
        };
    }

    /**
     * 差异状态转中文。
     *
     * @param status 状态
     * @return 中文；未知返回原值
     */
    private static String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_OPEN, STATUS_PENDING -> "待处理";
            case "auto_fixed" -> "已自动修复";
            case STATUS_HANDLED -> "已处理";
            case "ignored" -> "已忽略";
            default -> status;
        };
    }

    /**
     * 分转元文本（两位小数，禁浮点）。
     *
     * @param cents 分
     * @return 如 `-5.00`
     */
    private static String centsToYuan(long cents) {
        return java.math.BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }

    /**
     * `found_at`（UTC）转东八区文本。
     *
     * @param utc UTC 时间；空返回空串
     * @return `yyyy-MM-dd HH:mm:ss`
     */
    private static String formatFoundAt(LocalDateTime utc) {
        if (utc == null) {
            return "";
        }
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(BIZ_ZONE).toLocalDateTime()
                .format(FOUND_AT_FORMAT);
    }

    /**
     * 标记差异已处理（写审计）。
     *
     * <p>副作用：把 `status` 改为 `handled`，写 `handler_id` / `handle_note` / `handled_at`，
     * 并落一条 operation_log（action=RECONCILE_HANDLE）。</p>
     *
     * @param id         差异工单主键
     * @param handleNote 处理备注（必填，≤500 字）
     * @throws BusinessException 405018 差异不存在或已处理 / 401002 备注缺失 / 401003 备注超长
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "RECONCILE_HANDLE", targetType = "RECONCILIATION_DIFF")
    public void handleChannelDiff(Long id, String handleNote) {
        if (!StringUtils.hasText(handleNote)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "处理备注必填");
        }
        String note = handleNote.trim();
        if (note.length() > HANDLE_NOTE_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_FORMAT, "处理备注超过 500 字");
        }
        ReconciliationDiff diff = reconciliationDiffMapper.selectById(id);
        if (diff == null) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_DIFF_PENDING, "差异工单不存在");
        }
        if (STATUS_HANDLED.equals(diff.getStatus())) {
            // 幂等：已经处理过的不再改，避免覆盖前一个运营写的备注
            log.info("差异工单已处理，忽略重复提交 id={}", id);
            return;
        }
        AuditContext.AuditInfo audit = AuditContext.current();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ReconciliationDiff patch = new ReconciliationDiff();
        patch.setId(id);
        patch.setStatus(STATUS_HANDLED);
        patch.setHandlerId(audit == null ? null : audit.getOperatorId());
        patch.setHandleNote(note);
        patch.setHandledAt(now);
        patch.setUpdatedAt(now);
        reconciliationDiffMapper.updateById(patch);
        AuditContext.mark(MODULE, "RECONCILE_HANDLE", "RECONCILIATION_DIFF", String.valueOf(id),
                "{\"before\":{\"status\":\"" + diff.getStatus() + "\"},"
                        + "\"after\":{\"status\":\"" + STATUS_HANDLED + "\"},"
                        + "\"handleNote\":\"" + escape(note) + "\"}");
        log.info("差异工单已标记处理 id={} 原状态={}", id, diff.getStatus());
    }

    /**
     * 批量取充值单号（避免逐行查库造成 N+1）。
     *
     * @param rows 差异行
     * @return rechargeId → orderNo
     */
    private Map<Long, String> loadOrderNos(List<ReconciliationDiff> rows) {
        java.util.Set<Long> ids = rows.stream()
                .map(ReconciliationDiff::getRechargeId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new HashMap<>();
        for (RechargeRecord record : rechargeRecordMapper.selectBatchIds(ids)) {
            map.put(record.getId(), record.getOrderNo());
        }
        return map;
    }

    /**
     * 转义 JSON 字符串里的引号与反斜杠（detail 是 JSON 列，写坏会插入失败）。
     *
     * @param raw 原文
     * @return 转义结果
     */
    private String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
