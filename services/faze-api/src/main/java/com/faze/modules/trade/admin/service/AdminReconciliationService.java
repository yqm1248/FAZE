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
import com.faze.modules.trade.admin.vo.ReconciliationDiffVO;
import com.faze.modules.trade.admin.vo.ReconciliationSummaryVO;
import com.faze.modules.trade.entity.LawyerBalance;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.LawyerBalanceMapper;
import com.faze.modules.trade.mapper.OwnerFlowSum;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.service.BalanceService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    private final BalanceFlowMapper balanceFlowMapper;
    private final UserBalanceMapper userBalanceMapper;
    private final LawyerBalanceMapper lawyerBalanceMapper;
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
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("checkedOwners", result.summary().getCheckedOwners());
        detail.put("balancedOwners", result.summary().getBalancedOwners());
        detail.put("diffOwners", result.summary().getDiffOwners());
        detail.put("totalCheckedCents", result.summary().getTotalCheckedCents());
        AuditContext.mark(MODULE, ACTION, null, null, jsonMapper.writeValueAsString(detail));
        log.info("对账完成 checkedOwners={} diffOwners={}",
                result.summary().getCheckedOwners(), result.summary().getDiffOwners());
        return result.summary();
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
}
