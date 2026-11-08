/*
 * 职责：待支付订单的超时处理——15 分钟未支付关单、30 分钟无人接单自动全额退款。
 * 调用方：modules/trade/task/OrderCloseTask（`@Scheduled` 扫描 task_queue 后逐条调用）。
 * 数据来源：orders（状态与时间）、OrderStateService（状态迁移）、OrderRefundService（自动退款）。
 * 注意：
 *   1. **先查订单当前状态，再决定要不要动**：已支付/已取消的订单，那条关单任务就是过期的，
 *      此时直接返回（调用方会把它标成 done）。「已投递是状态不是删除」（规范 §9.2 规则 1）：
 *      不能因为无事可做就删任务，也不能把它当失败重试。
 *   2. **不判断「是否到点」**：任务入队时 `next_retry_at` 就是 `createdAt/paidAt + 超时`，
 *      扫描 SQL 又限定 `next_retry_at <= now`，所以被领到的任务必然已经到点。
 *      在这里再判一次会出现「差几毫秒 → 跳过 → 标完成 → 永不关单」的漏洞。
 *   3. `PAID` 超时必须走 `autoRefundFull`（退款 + 流水 + 状态三步），
 *      **不许只把状态改成 CANCELLED**：钱已经扣了，只改状态等于吞钱。
 *   4. 订单查不到（被软删）当作无事可做：历史数据清理过的单不该阻塞任务队列。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.Orders;
import com.faze.infra.mq.TaskQueue;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.faze.infra.mq.TaskQueueService;

/**
 * 订单超时处理。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderTimeoutService {

    private final OrdersMapper ordersMapper;
    private final OrderStateService orderStateService;
    private final OrderRefundService orderRefundService;

    /**
     * 执行一条超时任务。
     *
     * <p>副作用：按订单当前状态决定是否关单或退款；无条件时无副作用（幂等）。</p>
     *
     * @param task 已领取的任务（用 `taskType` 与 `bizNo` 定位订单）
     */
    public void handle(TaskQueue task) {
        Orders order = findByOrderNo(task.getBizNo());
        if (order == null) {
            log.warn("超时任务对应订单不存在，按无事可做处理 taskId={} bizNo={}",
                    task.getId(), task.getBizNo());
            return;
        }
        OrderStatus status = OrderStatus.of(order.getStatus());

        if (TaskQueueService.TYPE_ORDER_CLOSE.equals(task.getTaskType())) {
            handleCloseTimeout(order, status);
            return;
        }
        if (TaskQueueService.TYPE_ORDER_ACCEPT_TIMEOUT.equals(task.getTaskType())) {
            handleAcceptTimeout(order, status);
            return;
        }
        // 未知类型：由调用方标失败并退避，最终进死信，暴露给人工（PC A-26）
        throw new IllegalStateException("未知任务类型: " + task.getTaskType());
    }

    /**
     * 15 分钟未支付 → 关单。
     *
     * @param order  订单
     * @param status 当前状态
     */
    private void handleCloseTimeout(Orders order, OrderStatus status) {
        if (status != OrderStatus.PENDING) {
            // 已支付 / 已取消：这条关单任务过期了，不做事
            log.info("关单任务跳过：订单已不是待支付 orderId={} status={}",
                    order.getId(), status.getCode());
            return;
        }
        boolean ok = orderStateService.transit(order.getId(), OrderStatus.PENDING, OrderStatus.CANCELLED,
                OperatorRole.SYSTEM, null, "超时未支付，系统自动关闭");
        if (!ok) {
            // CAS 未命中：刚好有支付在跑，说明这单活了，留给他处理
            log.info("关单 CAS 未命中（订单状态已变） orderId={}", order.getId());
        }
    }

    /**
     * 30 分钟无人接单 → 自动全额退款。
     *
     * @param order  订单
     * @param status 当前状态
     */
    private void handleAcceptTimeout(Orders order, OrderStatus status) {
        if (status != OrderStatus.PAID) {
            // 已被接单 / 已退款 / 用户已申请退款：都不该由本任务再动
            log.info("接单超时任务跳过：订单已不是待接单 orderId={} status={}",
                    order.getId(), status.getCode());
            return;
        }
        orderRefundService.autoRefundFull(order, "超时无人接单，系统自动全额退款");
    }

    /**
     * 按单号查订单（排除软删）。
     *
     * @param orderNo 业务单号
     * @return 订单；不存在返回 null
     */
    private Orders findByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        return ordersMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getOrderNo, orderNo)
                .isNull(Orders::getDeletedAt));
    }
}
