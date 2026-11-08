/*
 * 职责：对账任务的业务入口（第 6 段 D3 下载 / D4 比对）。
 * 调用方：modules/trade/task/ReconcileTaskScanner（@Scheduled 扫描器）。
 *         modules/trade/task/ReconcileDailyScheduler（每日 02:00 投递下载任务）。
 * 数据来源：ReconcileBillService（D3）、ReconcileCompareService（D4）、task_queue（投递 D4）。
 * 注意：
 *   1. **D3 投递 D4 在 handler 末尾做**（工作流 D4 第 1 条允许的两种方式之一）：
 *      这样「账单下好了但比对没跑」会以一条 pending 任务留在表里，可观测、可重试；
 *      写在下载任务的业务代码里则会变成隐式调用，失败后没有任何痕迹。
 *   2. **同一 `bizNo` 已有活跃比对任务时不重复投递**（`hasActiveTask`）：手工触发下载两次
 *      不该产生两条比对任务。已 done 的允许再投——重跑比对是安全的（D4 幂等）。
 *   3. 本类**不吞异常**：失败交给调用方 `markFailed` 走 2^n 退避、5 次后死信。
 *      对账是每日任务，静默失败等于「账不对也没人知道」。
 *   4. 任务类型常量定义在 `ReconcileBillService`（与 D1/D2 放 `RechargeTaskService` 同理，
 *      不改段 2 已验收的 `TaskQueueService`）。
 */
package com.faze.modules.trade.service;

import com.faze.infra.mq.TaskQueueService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对账任务业务逻辑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconcileTaskService {

    private final ReconcileBillService reconcileBillService;
    private final ReconcileCompareService reconcileCompareService;
    private final TaskQueueService taskQueueService;

    /**
     * 处理一条「对账单下载」任务（D3）。
     *
     * <p>副作用：写 `file_record`、落账单文件、投递 D4 比对任务。</p>
     *
     * @param bizNo 账单日期 `yyyyMMdd`
     */
    public void handleDownload(String bizNo) {
        LocalDate billDate = toBillDate(bizNo);
        List<String> channels = reconcileBillService.download(billDate);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (String channel : channels) {
            String compareNo = ReconcileBillService.compareBizNo(channel, billDate);
            if (taskQueueService.hasActiveTask(ReconcileBillService.TASK_COMPARE, compareNo)) {
                log.info("比对任务已在队列，跳过重复投递 bizNo={}", compareNo);
                continue;
            }
            taskQueueService.enqueue(ReconcileBillService.TASK_COMPARE, compareNo, now, null);
        }
        log.info("对账下载任务完成 billDate={} producedChannels={}", billDate, channels);
    }

    /**
     * 处理一条「逐笔比对」任务（D4）。
     *
     * @param bizNo `{channel}:{yyyyMMdd}`
     * @return 比对结果
     */
    public ReconcileCompareService.CompareResult handleCompare(String bizNo) {
        String channel = ReconcileBillService.channelOfBizNo(bizNo);
        LocalDate billDate = ReconcileBillService.billDateOfBizNo(bizNo);
        return reconcileCompareService.compare(channel, billDate);
    }

    /**
     * 把任务 `bizNo` 解析成账单日期。
     *
     * @param bizNo `yyyyMMdd`
     * @return 账单日期
     * @throws com.faze.common.exception.BusinessException 405028 格式非法
     */
    private LocalDate toBillDate(String bizNo) {
        try {
            return LocalDate.parse(bizNo, ReconcileBillService.BILL_DATE_FMT);
        } catch (RuntimeException e) {
            throw new com.faze.common.exception.BusinessException(
                    com.faze.common.exception.ErrorCode.TRADE_RECONCILE_BILL_INVALID,
                    "对账下载任务 bizNo 应为 yyyyMMdd，实际 " + bizNo);
        }
    }
}
