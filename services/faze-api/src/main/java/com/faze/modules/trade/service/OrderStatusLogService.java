/*
 * 职责：order_status_log 的唯一写入口——每次订单状态变化落一行（只追加）。
 * 调用方：modules/trade 的订单服务与任务消费者（下单、支付、接单、拒单、完成、退款、超时关单）。
 * 数据来源：调用方传入的迁移事实；落库到 faze.order_status_log。
 * 注意：
 *   1. **所有状态变化都必须经过这里**：绕过它直接 update orders.status 会让 `2-A4`
 *      「状态流转时间线」出现断点，事故复盘时查不到是谁改的。
 *   2. 本类**不加 @Transactional**：它必须加入调用方的事务（订单状态改了日志没写 = 证据缺失）；
 *      自己开新事务会出现「主事务回滚但日志留下」的假历史。默认 REQUIRED 传播即可。
 *   3. 只做 insert，不提供 update / delete（B 类表只追加）。
 *   4. `reason` 只写「为什么迁」，不写任何敏感信息（不含手机号、身份证、聊天原文）。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.OrderStatusLog;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrderStatusLogMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 订单状态迁移流水写入器。
 */
@Service
@RequiredArgsConstructor
public class OrderStatusLogService {

    private final OrderStatusLogMapper orderStatusLogMapper;

    /**
     * 记录一次状态迁移。
     *
     * <p>副作用：向 order_status_log 插入一行。调用方负责保证这次迁移是真实发生的
     * （即 orders.status 确实从 from 改成了 to）。</p>
     *
     * @param orderId    订单主键
     * @param from       迁移前状态；订单创建时传 null
     * @param to         迁移后状态
     * @param role       触发方
     * @param operatorId 操作人 ID；SYSTEM 时传 null
     * @param reason     迁移原因（如「超时关闭」），可空
     */
    public void record(Long orderId, OrderStatus from, OrderStatus to,
                       OperatorRole role, Long operatorId, String reason) {
        OrderStatusLog row = new OrderStatusLog();
        row.setOrderId(orderId);
        row.setFromStatus(from == null ? null : from.getCode());
        row.setToStatus(to.getCode());
        row.setOperatorType(role.getCode());
        // SYSTEM 的 operator_id 必须为空（V200 迁移的列注释：system 时为空）
        row.setOperatorId(role == OperatorRole.SYSTEM ? null : operatorId);
        row.setReason(reason);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        orderStatusLogMapper.insert(row);
    }

    /**
     * 按订单查状态流转时间线（升序）。
     *
     * @param orderId 订单主键
     * @return 迁移记录，最早在前；无记录返回空列表
     */
    public List<OrderStatusLog> listByOrder(Long orderId) {
        return orderStatusLogMapper.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, orderId)
                .orderByAsc(OrderStatusLog::getCreatedAt)
                .orderByAsc(OrderStatusLog::getId));
    }
}
