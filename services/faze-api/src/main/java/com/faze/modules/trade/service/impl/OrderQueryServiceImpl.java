/*
 * 职责：订单只读查询接口的实现。
 * 调用方：modules/ops 的 ComplaintService、modules/conversation 的 OrderNoticeService /
 *        ConversationService、modules/account 的 AccountSecurityService（注销前置校验）。
 * 数据来源：faze.orders。
 * 注意：
 *   1. 单表查询用 Wrapper，直接 `selectCount`——不需要把整行读出来再判断。
 *   2. **归属条件进 SQL**（`user_id = ?`），不是先查再比：先查再比会有 TOCTOU 窗口，
 *      而且漏判时返回的是「存在」而不是「不存在」（后端规范 §11.2）。
 *   3. `status='completed'` 是「已完成」的唯一认定口径：退款中/已退款不算「已完成」，
 *      前端在投诉页会提示用户「不涉具体订单的意见请走意见反馈」。
 *   4. 「有效订单」的口径单独一个常量（**paid + in_service**），会话准入与订单通知共用，
 *      避免两处各写一份、日后改一处漏一处。
 *   5. **「在途订单」是另一套口径**（非终态，5 个状态）：会话准入与注销前置校验语义不同，
 *      不许为了省一个常量把两者合并，理由见 `IN_FLIGHT_STATUSES` 的注释。
 */
package com.faze.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.service.OrderQueryService;
import com.faze.modules.trade.vo.OrderBriefVO;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 订单只读查询实现。
 */
@Service
public class OrderQueryServiceImpl implements OrderQueryService {

    /** 已完成状态值。 */
    private static final String STATUS_COMPLETED = "completed";

    /**
     * 「有效订单」的状态集合：已支付（等接单）+ 服务中（已接单）。
     *
     * <p>只认这两个：`completed` 已完结、`pending` 未付款、`refunding` / `refunded` 在退款链路、
     * `cancelled` 已取消、`refund_rejected` 不是稳定态（会回退到 in_service / completed）。</p>
     */
    private static final List<String> ACTIVE_STATUSES = List.of(
            OrderStatus.PAID.getCode(), OrderStatus.IN_SERVICE.getCode());

    /**
     * 「在途订单」的状态集合 = **非终态**，从 {@link OrderStatus} 派生，不手写列表。
     *
     * <p>为什么派生而不是写白名单：段 5 会加 `DISPUTED`（争议中）。白名单写法下，
     * 新状态会被默默排除在「在途」之外 → 争议中的用户可以注销；派生写法下它自动落进
     * 「在途」→ 被拦。**两种写法都会在某天出错，但出错方向必须选安全的那一侧**：
     * 多拦一次注销只是让用户多点一步，放行一次注销是不可逆的。</p>
     *
     * <p>代价（写清楚，别当它不存在）：段 5 新增状态时若忘了同步
     * {@link OrderStatus#isTerminal()}，新加的**终态**（如 `SETTLED`）会被当成在途，
     * 导致已结算的用户无法注销。这是可接受的错方向（安全侧），但改状态枚举的人必须回头
     * 看这个常量和 `isTerminal()`。</p>
     *
     * <p>当前值：`pending` / `paid` / `in_service` / `refunding` / `refund_rejected`。
     * 恒非空（8 态里 3 个终态），所以 `IN ()` 这种非法 SQL 不会出现。</p>
     */
    private static final List<String> IN_FLIGHT_STATUSES = Arrays.stream(OrderStatus.values())
            .filter(status -> !status.isTerminal())
            .map(OrderStatus::getCode)
            .toList();

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

    /**
     * 判断某用户对某律师是否存在有效订单。
     *
     * <p>`deleted_at IS NULL` 必须自己带：段 1/2 都没配 MP 逻辑删除拦截器
     * （见 `Orders` 类头第 4 条）。</p>
     *
     * @param userId   用户主键
     * @param lawyerId 律师主键
     * @return 存在「已支付且未完结」订单返回 true
     */
    @Override
    public boolean existsActiveOrderOfUserAndLawyer(Long userId, Long lawyerId) {
        if (userId == null || lawyerId == null) {
            return false;
        }
        Long count = ordersMapper.selectCount(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getUserId, userId)
                .eq(Orders::getLawyerId, lawyerId)
                .in(Orders::getStatus, ACTIVE_STATUSES)
                .isNull(Orders::getDeletedAt));
        return count != null && count > 0;
    }

    /**
     * 取某用户对某律师的全部有效订单（最小投影）。
     *
     * @param userId   用户主键
     * @param lawyerId 律师主键
     * @return 有效订单列表（按主键升序）；没有返回空列表
     */
    @Override
    public List<OrderBriefVO> listActiveOrdersOfUserAndLawyer(Long userId, Long lawyerId) {
        if (userId == null || lawyerId == null) {
            return List.of();
        }
        return ordersMapper.selectList(Wrappers.<Orders>lambdaQuery()
                        .eq(Orders::getUserId, userId)
                        .eq(Orders::getLawyerId, lawyerId)
                        .in(Orders::getStatus, ACTIVE_STATUSES)
                        .isNull(Orders::getDeletedAt)
                        .orderByAsc(Orders::getId))
                .stream()
                .map(order -> new OrderBriefVO(order.getId(), order.getOrderNo(),
                        order.getServiceType(), order.getAmountCents()))
                .toList();
    }

    /**
     * 按主键批量取订单号。
     *
     * <p>一次 IN 查询；`order_no` 为空的行跳过（历史脏数据不该让「目标对象」列变 null）。</p>
     *
     * @param orderIds 订单主键集合
     * @return 主键 → 订单号
     */
    @Override
    public java.util.Map<Long, String> mapOrderNosByIds(java.util.Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> result = new java.util.HashMap<>(orderIds.size());
        for (Orders order : ordersMapper.selectBatchIds(orderIds)) {
            if (order.getId() != null && order.getOrderNo() != null) {
                result.put(order.getId(), order.getOrderNo());
            }
        }
        return result;
    }

    /**
     * 判断某用户作为下单人是否还有在途订单。
     *
     * @param userId 用户主键
     * @return 存在非终态订单返回 true
     */
    @Override
    public boolean existsInFlightOrderOfUser(Long userId) {
        if (userId == null) {
            return false;
        }
        Long count = ordersMapper.selectCount(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getUserId, userId)
                .in(Orders::getStatus, IN_FLIGHT_STATUSES)
                .isNull(Orders::getDeletedAt));
        return count != null && count > 0;
    }

    /**
     * 判断某律师作为服务方是否还有在途订单。
     *
     * @param lawyerId 律师主键（lawyer.id）
     * @return 存在非终态订单返回 true
     */
    @Override
    public boolean existsInFlightOrderOfLawyer(Long lawyerId) {
        if (lawyerId == null) {
            return false;
        }
        Long count = ordersMapper.selectCount(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getLawyerId, lawyerId)
                .in(Orders::getStatus, IN_FLIGHT_STATUSES)
                .isNull(Orders::getDeletedAt));
        return count != null && count > 0;
    }
}
