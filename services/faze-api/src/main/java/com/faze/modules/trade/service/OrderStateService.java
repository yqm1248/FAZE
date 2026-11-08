/*
 * 职责：订单状态迁移的唯一入口——CAS 更新 orders.status 并写 order_status_log。
 * 调用方：modules/trade 的全部状态变更点（支付 G2.2、接单拒单完成 G3、退款 G4、评价前置 G5、
 *         超时关单 G2.4）。
 * 数据来源：faze.orders（CAS 更新）、faze.order_status_log（追加）。
 * 注意：
 *   1. **迁移合法性只问 `OrderStatusMachine`**：这里不写任何 if 判断。
 *   2. **必须 CAS**（`WHERE id=? AND status=<from>`）：两个并发请求都读到 PENDING 时，
 *      不加条件会让「支付」和「取消」同时成功，钱扣了单却取消了。
 *   3. **返回 false 不是异常**：它表示「当前状态已经不是 from 了」，调用方据此决定是
 *      「幂等重放，返回成功」还是「状态非法，报 405003」。把这个选择留给调用方，
 *      是因为支付重放和抢单冲突的业务含义完全不同。
 *   4. 时间字段按目标状态自动填（PAID→paid_at、IN_SERVICE→accepted_at、
 *      COMPLETED→finished_at、CANCELLED→cancelled_at），调用方不用记这些映射。
 *   5. 本类**不加 @Transactional**：它必须加入调用方事务（状态改了、流水没写 = 钱账不符）。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.rule.OrderStatusMachine;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 订单状态迁移器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStateService {

    private final OrdersMapper ordersMapper;
    private final OrderStatusLogService orderStatusLogService;

    /**
     * 把订单从 from 迁到 to（CAS）。
     *
     * <p>副作用：更新 orders 一行（含对应时间字段）、追加一条 order_status_log。</p>
     *
     * @param orderId    订单主键
     * @param from       迁移前状态
     * @param to         迁移后状态
     * @param role       触发方（服务端判定，不从请求体取）
     * @param operatorId 操作人 ID；SYSTEM 时传 null
     * @param reason     迁移原因，可空
     * @return true 表示本次迁移成功；false 表示当前状态已不是 from（并发或重复执行）
     * @throws com.faze.common.exception.BusinessException 405003：该角色不允许这条迁移
     */
    public boolean transit(Long orderId, OrderStatus from, OrderStatus to,
                           OperatorRole role, Long operatorId, String reason) {
        // 任务 1：先问状态机（非法组合直接抛 405003，不落到 SQL）
        OrderStatusMachine.assertCanTransit(from, to, role);

        // 任务 2：CAS 更新（条件里必须带旧状态）
        // 时间字段用**条件式 set**：只写目标状态对应的那一个，其余列保持原值。
        // 写成 `.set(column, to == X ? now : null)` 会在非 X 迁移时把已记录的时间清空。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = ordersMapper.update(null, Wrappers.<Orders>lambdaUpdate()
                .set(Orders::getStatus, to.getCode())
                .set(Orders::getUpdatedAt, now)
                .set(to == OrderStatus.PAID, Orders::getPaidAt, now)
                .set(to == OrderStatus.IN_SERVICE, Orders::getAcceptedAt, now)
                .set(to == OrderStatus.COMPLETED, Orders::getFinishedAt, now)
                .set(to == OrderStatus.CANCELLED, Orders::getCancelledAt, now)
                .eq(Orders::getId, orderId)
                .eq(Orders::getStatus, from.getCode())
                .isNull(Orders::getDeletedAt));
        if (rows == 0) {
            log.warn("状态迁移 CAS 未命中 orderId={} expectedFrom={} to={}",
                    orderId, from.getCode(), to.getCode());
            return false;
        }

        // 任务 3：写状态流水（与状态更新同事务，失败一起回滚）
        orderStatusLogService.record(orderId, from, to, role, operatorId, reason);
        log.info("状态迁移成功 orderId={} {} -> {} role={}", orderId, from.getCode(), to.getCode(),
                role.getCode());
        return true;
    }

    /**
     * 从数据库重新读订单当前状态（CAS 失败后用于判断是幂等重放还是真冲突）。
     *
     * @param orderId 订单主键
     * @return 当前状态；订单不存在返回 null
     */
    public OrderStatus currentStatus(Long orderId) {
        Orders row = ordersMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .select(Orders::getId, Orders::getStatus)
                .eq(Orders::getId, orderId)
                .isNull(Orders::getDeletedAt));
        return row == null ? null : OrderStatus.of(row.getStatus());
    }
}
