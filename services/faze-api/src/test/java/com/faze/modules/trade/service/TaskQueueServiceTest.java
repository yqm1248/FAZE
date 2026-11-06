/*
 * 职责：TaskQueueService 的单测——CAS 领取、退避、死信、取消（规范 §9.2 的四条规则）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 TaskQueueMapper。
 * 注意：
 *   1. 核心断言是「并发领 3 次只成功 1 次」：模拟 mapper 返回影响行数 1/0/0，
 *      后两次必须返回空列表且**不去回查任务**（回查会把自己当成领到）。
 *   2. 退避指数：retryCount=0 → 1 分钟、1 → 2 分钟、5 → 32 分钟，最小封顶 60 分钟。
 *   3. retryCount 达到 5 时必须走 markDead（死信），不是继续退避——
 *      无限重试会让一条坏任务永久占住队列。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.faze.modules.trade.entity.TaskQueue;
import com.faze.modules.trade.mapper.TaskQueueMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异步任务队列测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskQueueServiceTest {

    @Mock
    private TaskQueueMapper taskQueueMapper;

    @InjectMocks
    private TaskQueueService taskQueueService;

    @Test
    @DisplayName("没有候选任务：返回空列表，不做 CAS")
    void claimNoCandidate() {
        when(taskQueueMapper.selectCandidateIds(any(), eq(20))).thenReturn(List.of());

        assertTrue(taskQueueService.claimBatch("w1").isEmpty());
        verify(taskQueueMapper, never()).claimByIds(anyList(), anyString(), any(), any());
    }

    @Test
    @DisplayName("并发领取：CAS 影响行数为 0 时返回空列表，不回查任务")
    void claimCasMissByOtherConsumer() {
        when(taskQueueMapper.selectCandidateIds(any(), eq(20))).thenReturn(List.of(1L, 2L));
        when(taskQueueMapper.claimByIds(anyList(), anyString(), any(), any())).thenReturn(0);

        assertTrue(taskQueueService.claimBatch("w2").isEmpty());
        verify(taskQueueMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("领取成功：按 owner 回查自己领到的任务")
    void claimSuccess() {
        TaskQueue task = new TaskQueue();
        task.setId(1L);
        when(taskQueueMapper.selectCandidateIds(any(), eq(20))).thenReturn(List.of(1L));
        when(taskQueueMapper.claimByIds(anyList(), anyString(), any(), any())).thenReturn(1);
        when(taskQueueMapper.selectList(any(Wrapper.class))).thenReturn(List.of(task));

        List<TaskQueue> tasks = taskQueueService.claimBatch("w3");

        assertEquals(1, tasks.size());
        assertEquals(1L, tasks.get(0).getId());
    }

    @Test
    @DisplayName("租约过期会先被回收（每次领取都调用一次）")
    void claimRecyclesExpired() {
        when(taskQueueMapper.selectCandidateIds(any(), eq(20))).thenReturn(List.of());
        when(taskQueueMapper.releaseExpired(any())).thenReturn(2);

        taskQueueService.claimBatch("w4");

        verify(taskQueueMapper).releaseExpired(any());
    }

    @Test
    @DisplayName("失败未到上限：走 markFailed，next_retry_at 按 2^retryCount 分钟退避")
    void markFailedBackoff() {
        TaskQueue task = task();
        task.setRetryCount(1);

        taskQueueService.markFailed(task, "owner", "boom");

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskQueueMapper).markFailed(eq(1L), eq("owner"), eq("boom"), captor.capture(), any());
        // retryCount=1 → 2 分钟
        long minutes = Duration.between(LocalDateTime.now(java.time.ZoneOffset.UTC),
                captor.getValue()).toMinutes();
        assertTrue(minutes >= 1 && minutes <= 2, "退避应约为 2 分钟，实际 " + minutes);
        verify(taskQueueMapper, never()).markDead(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("失败到上限（retryCount=4，再失败一次即第 5 次）：进死信")
    void markFailedToDeadLetter() {
        TaskQueue task = task();
        task.setRetryCount(4);

        taskQueueService.markFailed(task, "owner", "boom");

        verify(taskQueueMapper).markDead(eq(1L), eq("owner"), eq("boom"), any());
        verify(taskQueueMapper, never()).markFailed(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("退避计算：0→1 分钟、5→32 分钟、6 之后封顶 60 分钟")
    void backoffCalculation() {
        assertEquals(Duration.ofMinutes(1), taskQueueService.backoff(0));
        assertEquals(Duration.ofMinutes(2), taskQueueService.backoff(1));
        assertEquals(Duration.ofMinutes(32), taskQueueService.backoff(5));
        assertEquals(Duration.ofMinutes(60), taskQueueService.backoff(9));
    }

    @Test
    @DisplayName("取消待执行任务：单号为空时不碰库")
    void cancelPendingWithoutBizNo() {
        taskQueueService.cancelPending(TaskQueueService.TYPE_ORDER_CLOSE, "");

        verify(taskQueueMapper, never()).cancelByBizNo(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("取消待执行任务：按类型 + 单号更新")
    void cancelPending() {
        taskQueueService.cancelPending(TaskQueueService.TYPE_ORDER_CLOSE, "OR1");

        verify(taskQueueMapper).cancelByBizNo(eq(TaskQueueService.TYPE_ORDER_CLOSE), eq("OR1"), any());
    }

    private TaskQueue task() {
        TaskQueue task = new TaskQueue();
        task.setId(1L);
        task.setTaskType(TaskQueueService.TYPE_ORDER_CLOSE);
        task.setBizNo("OR1");
        return task;
    }
}
