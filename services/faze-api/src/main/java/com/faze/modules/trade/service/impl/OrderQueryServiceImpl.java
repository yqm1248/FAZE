/*
 * 职责：订单只读查询接口的实现。
 * 调用方：modules/ops 的 ComplaintService。
 * 数据来源：faze.orders。
 * 注意：
 *   1. 单表查询用 Wrapper，直接 `selectCount`——不需要把整行读出来再判断。
 *   2. **归属条件进 SQL**（`user_id = ?`），不是先查再比：先查再比会有 TOCTOU 窗口，
 *      而且漏判时返回的是「存在」而不是「不存在」（后端规范 §11.2）。
 *   3. `status='completed'` 是唯一认定口径：退款中/已退款不算「已完成」，
 *      前端在投诉页会提示用户「不涉具体订单的意见请走意见反馈」。
 */
package com.faze.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.service.OrderQueryService;
import org.springframework.stereotype.Service;

/**
 * 订单只读查询实现。
 */
@Service
public class OrderQueryServiceImpl implements OrderQueryService {

    /** 已完成状态值。 */
    private static final String STATUS_COMPLETED = "completed";

    /** 订单 Mapper。 */
    private final OrdersMapper ordersMapper;

    /**
     * 构造实现。
     *
     * @param ordersMapper 订单 Mapper
     */
    public OrderQueryServiceImpl(OrdersMapper ordersMapper) {
        this.ordersMapper = ordersMapper;
    }

    /**
     * 判断某订单是否属于指定用户且已完成。
     *
     * @param orderId 订单主键
     * @param userId  用户主键
     * @return 属于该用户且状态为 completed 时返回 true
     */
    @Override
    public boolean existsCompletedOrderOfUser(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            return false;
        }
        Long count = ordersMapper.selectCount(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getId, orderId)
                .eq(Orders::getUserId, userId)
                .eq(Orders::getStatus, STATUS_COMPLETED));
        return count != null && count > 0;
    }
}
