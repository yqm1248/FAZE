/*
 * 职责：A-09 对账报表接口（汇总 / 差异列表 / 执行对账）。
 * 调用方：PC 后台「对账报表」页（src/views/biz/reconciliation/index.vue）。
 * 数据来源：AdminReconciliationService → balance_flow / user_balance / lawyer_balance。
 * 注意：
 *   1. `GET summary` 与 `GET diffs` 是纯查询，不标审计；
 *      `POST run` 是「执行一次巡检」的写语义动作（只写 operation_log），由 Service 标注解。
 *   2. 三个接口**都现算**：同一时刻调用 summary 与 diffs 得到的是两次独立计算，
 *      页面要给「本次检查时间」用 checkedAt（summary 里有），不要假设两次完全同刻。
 *   3. 不返回、不接受任何处置动作（段 5 的差异工单）。
 */
package com.faze.modules.trade.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.PageResult;
import com.faze.modules.trade.admin.bo.ChannelDiffHandleBO;
import com.faze.modules.trade.admin.bo.ChannelDiffQueryBO;
import com.faze.modules.trade.admin.service.AdminReconciliationService;
import com.faze.modules.trade.admin.vo.ChannelDiffRowVO;
import com.faze.modules.trade.admin.vo.ReconciliationDiffVO;
import com.faze.modules.trade.admin.vo.ReconciliationSummaryVO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端对账报表接口。
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@RequiredArgsConstructor
public class AdminReconciliationController {

    private final AdminReconciliationService reconciliationService;

    /**
     * 对账汇总（只读现算）。
     *
     * @return 汇总
     */
    @GetMapping("/summary")
    public R<ReconciliationSummaryVO> summary() {
        return R.ok(reconciliationService.summary());
    }

    /**
     * 差异列表（只读现算，内存态）。
     *
     * @return 差异行
     */
    @GetMapping("/diffs")
    public R<List<ReconciliationDiffVO>> diffs() {
        return R.ok(reconciliationService.diffs());
    }

    /**
     * 执行一次对账并留痕（不改余额）。
     *
     * @return 与 summary 同结构的汇总
     */
    @PostMapping("/run")
    public R<ReconciliationSummaryVO> run() {
        return R.ok(reconciliationService.run());
    }

    /**
     * 渠道对账差异列表（`reconciliation_diff` 落库记录，第 6 段 B4）。
     *
     * <p>**与 `GET /diffs` 不是同一份数据**：那个是 Tab1 恒等式巡检的内存态现算结果，
     * 这个是 Tab2 的差异工单。两条路由分开是刻意的——合在一起会让 Tab1 的已验收契约变化。</p>
     *
     * @param query 账单日期区间 / 渠道 / 差异类型 / 状态 / 页码
     * @return 分页结果
     */
    @GetMapping("/channel-diffs")
    public R<PageResult<ChannelDiffRowVO>> channelDiffs(ChannelDiffQueryBO query) {
        return R.ok(reconciliationService.pageChannelDiffs(query));
    }

    /**
     * 导出渠道差异 CSV（第 6 段 D4 第 4 条）。
     *
     * <p>**返回的不是 `R<T>`**：文件下载是 `R<T>` 契约的唯一例外（与 RuoYi 的下载接口一致），
     * 浏览器拿到的是 `text/csv` 流。筛选条件与 `GET /channel-diffs` 完全相同，
     * 同一组参数下导出的行就是列表里的行（只是不分页）。</p>
     *
     * <p>带 UTF-8 BOM，Excel 直接双击打开中文不乱码。</p>
     *
     * @param query 筛选条件（账单日期区间 / 渠道 / 差异类型 / 状态）
     * @return CSV 文件流
     */
    @GetMapping("/channel-diffs/export")
    public ResponseEntity<byte[]> exportChannelDiffs(ChannelDiffQueryBO query) {
        byte[] body = reconciliationService.exportChannelDiffs(query);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("reconcile-diffs.csv", StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /**
     * 标记一条渠道差异已处理（写审计，第 6 段 B4）。
     *
     * @param id  差异工单主键
     * @param bo  处理备注
     * @return 空
     */
    @PutMapping("/channel-diffs/{id}")
    public R<Void> handleChannelDiff(@PathVariable Long id, @RequestBody ChannelDiffHandleBO bo) {
        reconciliationService.handleChannelDiff(id, bo.getHandleNote());
        return R.ok(null);
    }
}
