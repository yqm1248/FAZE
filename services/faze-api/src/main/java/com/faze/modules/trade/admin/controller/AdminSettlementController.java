/*
 * 职责：PC 律师结算（A-13）与收入回补（E2）的 HTTP 入口。
 * 调用方：pc/admin-web 的 biz/trade 页面（LawyerSettle.vue）。
 * 数据来源：SettlementService。
 * 注意：
 *   1. 路径在 `/api/v1/admin/**` 下，由 SecurityConfig 要求管理员身份（RuoYi 签发的 token）。
 *   2. 写接口的审计由 Service 上的 `@OperationLog` 切面采集，**Controller 不写审计**
 *      （《审计与日志规范》：操作日志必须服务端 AOP 采集，不许前端埋点）。
 *   3. 列表返回 `TableDataInfo`（rows/total），不包 R（接口规范 §5.2 裁决）；
 *      单对象与动作接口返回 `R<T>`。
 *   4. **确认结算不碰钱**：本 Controller 的 confirm 最终只改 `lawyer_income_settlement.status`，
 *      不写 balance_flow、不改 lawyer_balance。这是「结算与提现重复打款」的唯一防线。
 */
package com.faze.modules.trade.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.trade.admin.bo.SettlementGenerateBO;
import com.faze.modules.trade.admin.bo.SettlementQueryBO;
import com.faze.modules.trade.admin.vo.BackfillResultVO;
import com.faze.modules.trade.admin.vo.SettlementDetailVO;
import com.faze.modules.trade.admin.vo.SettlementGenerateResultVO;
import com.faze.modules.trade.admin.vo.SettlementRowVO;
import com.faze.modules.trade.admin.vo.SettlementSummaryVO;
import com.faze.modules.trade.service.SettlementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 律师结算与收入回补接口。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminSettlementController {

    private final SettlementService settlementService;

    /**
     * 构造控制器。
     *
     * @param settlementService 结算业务
     */
    public AdminSettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /**
     * 结算批次列表（分页，rows/total）。
     *
     * @param query 查询入参
     * @return 分页响应
     */
    @GetMapping("/lawyer-settlements")
    public TableDataInfo<SettlementRowVO> list(@Valid SettlementQueryBO query) {
        return settlementService.list(query);
    }

    /**
     * 结算页三张统计卡。
     *
     * @return 统计卡数据
     */
    @GetMapping("/lawyer-settlements/summary")
    public R<SettlementSummaryVO> summary() {
        return R.ok(settlementService.summary());
    }

    /**
     * 生成结算批次（可批量：不传 lawyerId 表示该账期全部律师）。
     *
     * @param bo 账期 + 可选律师 id
     * @return 生成结果（成功 / 跳过 / 候选数）
     */
    @PostMapping("/lawyer-settlements/generate")
    public R<SettlementGenerateResultVO> generate(@Valid @RequestBody SettlementGenerateBO bo) {
        return R.ok(settlementService.generate(bo.getPeriod(), bo.getLawyerId()));
    }

    /**
     * 结算单详情（批次头 + 逐单明细）。
     *
     * @param id 结算批次主键
     * @return 详情
     */
    @GetMapping("/lawyer-settlements/{id}")
    public R<SettlementDetailVO> detail(@PathVariable @Positive Long id) {
        return R.ok(settlementService.detail(id));
    }

    /**
     * 确认结算（`pending → settled`，**不动任何资金**）。
     *
     * @param id 结算批次主键
     * @return 空响应
     */
    @PostMapping("/lawyer-settlements/{id}/confirm")
    public R<Void> confirm(@PathVariable @Positive Long id) {
        settlementService.confirm(id);
        return R.ok();
    }

    /**
     * 收入回补的只读核对：还有多少笔订单没入账、合计多少净收入。
     *
     * <p>先看这个数，人工确认后再点回补（工作流 E2 第 5 条：不许自动执行）。</p>
     *
     * @return 待补订单数与净收入合计
     */
    @GetMapping("/settlements/backfill/pending")
    public R<BackfillResultVO> pendingBackfill() {
        return R.ok(settlementService.pendingBackfill());
    }

    /**
     * 执行收入回补（管理员人工触发，写接口）。
     *
     * @param limit 单次最多回补笔数；不传用默认批量
     * @return 回补结果
     */
    @PostMapping("/settlements/backfill")
    public R<BackfillResultVO> backfill(@RequestParam(required = false) Integer limit) {
        return R.ok(settlementService.backfillIncome(limit == null ? 0 : limit));
    }
}
