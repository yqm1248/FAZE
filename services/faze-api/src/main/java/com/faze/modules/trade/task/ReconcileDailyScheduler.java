/*
 * 职责：每日 02:00 投递「渠道对账单下载」任务（第 6 段 D3 第 1 条）。
 * 调用方：Spring 的 @Scheduled（ThreadPoolConfig 的 taskScheduler）。
 * 数据来源：无（只投递，不执行业务）；下游是 ReconcileTaskScanner → ReconcileTaskService。
 * 注意：
 *   1. **时间写死在注解里**（02:00 东八区，依据《技术路线》§9.5）：改时间要改代码重新发版。
 *      这是刻意的——对账时间是一个「财务口径」，不该允许在后台页面随手改（改了没人记得）。
 *      真要改，走代码评审。
 *   2. **只投递昨日**：账单是「昨天」的账。`bizNo` 用昨日日期 `yyyyMMdd`，同一天重复启动
 *      也只投一次——`hasActiveTask` 挡住「已投未跑」，已 done 的允许再投（重跑安全）。
 *   3. **不在这里调业务**：投递失败（DB 抖动）只打日志，下一轮 02:00 才有下一次机会。
 *      对账是日任务，错过一天不会丢账（次日下载的是次日的账单），但**必须能从日志看出来**。
 *   4. 本类不处理「补跑历史日期」：需要补跑时直接往 task_queue 插一行
 *      `(type=recharge_reconcile_download, biz_no=yyyyMMdd)`，见验收证据里的 SQL。
 */
package com.faze.modules.trade.task;

import com.faze.infra.mq.TaskQueueService;
import com.faze.modules.trade.service.ReconcileBillService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对账日调度器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconcileDailyScheduler {

    /** 业务时区（与全项目一致）。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final TaskQueueService taskQueueService;

    /**
     * 每日 02:00（东八区）投递昨日的账单下载任务。
     *
     * <p>副作用：向 `task_queue` 插入一行 pending 任务。</p>
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Shanghai")
    public void dispatchYesterdayDownload() {
        LocalDate billDate = LocalDate.now(BIZ_ZONE).minusDays(1);
        String bizNo = billDate.format(ReconcileBillService.BILL_DATE_FMT);
        if (taskQueueService.hasActiveTask(ReconcileBillService.TASK_DOWNLOAD, bizNo)) {
            log.info("对账下载任务已在队列，跳过投递 bizNo={}", bizNo);
            return;
        }
        taskQueueService.enqueue(ReconcileBillService.TASK_DOWNLOAD, bizNo,
                LocalDateTime.now(ZoneOffset.UTC), null);
        log.info("已投递对账下载任务 bizNo={}（账单日期 {}）", bizNo, billDate);
    }
}
