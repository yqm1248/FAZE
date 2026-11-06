/*
 * 职责：task_queue 的**集成测试**——验证 CAS 领取在真实 MySQL 上能领到到期任务。
 * 调用方：mvn test（@SpringBootTest，需要真实库）。
 * 数据来源：faze.task_queue（测试自己插入一条到期任务，跑完删掉）。
 * 注意：
 *   1. 这条测试是 2026-11-06 补的：当时定时关单「一次都没执行」，而单测（Mockito）全绿——
 *      因为单测 mock 掉了 Mapper，SQL 与参数绑定根本没被验证过。
 *      **只有打真库才能发现「XML 参数绑定/时间比较」这类问题**。
 *   2. 测试数据自己造自己清（`finally` 里按 biz_no 删），不依赖种子数据。
 *   3. 领取会改任务状态，所以断言只针对「候选 → 领取 → 回查」这条链，不做全局断言。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.TaskQueue;
import com.faze.modules.trade.mapper.TaskQueueMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步任务队列集成测试。
 */
@SpringBootTest
@ActiveProfiles("dev")
class TaskQueueIntegrationTest {

    /** 测试专用单号前缀，跑完按它清理。 */
    private static final String TEST_BIZ_NO_PREFIX = "ITTEST-";

    @Autowired
    private TaskQueueService taskQueueService;

    @Autowired
    private TaskQueueMapper taskQueueMapper;

    @AfterEach
    void cleanUp() {
        taskQueueMapper.delete(Wrappers.<TaskQueue>lambdaQuery()
                .likeRight(TaskQueue::getBizNo, TEST_BIZ_NO_PREFIX));
    }

    @Test
    @DisplayName("到期任务能被 CAS 领取（真实 MySQL，验证 XML 参数绑定与时间比较）")
    void claimDueTask() {
        String bizNo = TEST_BIZ_NO_PREFIX + System.currentTimeMillis();
        // 入队时间设在一分钟前：必然到期
        taskQueueService.enqueue(TaskQueueService.TYPE_ORDER_CLOSE, bizNo,
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), null);

        List<TaskQueue> claimed = taskQueueService.claimBatch("it-case");

        boolean hit = claimed.stream().anyMatch(task -> bizNo.equals(task.getBizNo()));
        assertTrue(hit, "到期任务没有被领取到，领取批次=" + claimed.size());
    }

    @Test
    @DisplayName("未到期任务不会被领取")
    void skipFutureTask() {
        String bizNo = TEST_BIZ_NO_PREFIX + "future-" + System.currentTimeMillis();
        taskQueueService.enqueue(TaskQueueService.TYPE_ORDER_CLOSE, bizNo,
                LocalDateTime.now(ZoneOffset.UTC).plusHours(1), null);

        List<TaskQueue> claimed = taskQueueService.claimBatch("it-case-future");

        boolean hit = claimed.stream().anyMatch(task -> bizNo.equals(task.getBizNo()));
        assertFalse(hit, "未到期任务被提前领取了");
    }
}
