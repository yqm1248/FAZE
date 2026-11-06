/*
 * 职责：OrderService 的实现（用户侧下单 / 列表 / 详情）。
 * 调用方：Spring 依赖注入（modules/trade/controller/OrderController）。
 * 数据来源：orders（读写）、lawyer（取价与展示，经只读查询接口）、sys_config（费率与开关）。
 * 注意：
 *   1. **本类不加 @Transactional**：幂等占位要写在事务外面——若包进事务，事务回滚后
 *      占位 key 仍是 `creating`（Redis 不参与数据库回滚），用户会被卡住 10 分钟。
 *      真正的写入在 `OrderCreateExecutor`（自带事务）。
 *   2. 归属校验**一律进 SQL**（`user_id = ?`），不做「先查出来再比对 user_id」：
 *      后者写成 if 判断，忘写一次就是越权。查不到统一回 405002「订单不存在」，
 *      不区分「不存在」与「不是你的」——避免成为订单号探测器。
 *   3. 列表的律师信息**批量查**（`LawyerQueryService#mapByIds`），不逐行查（N+1）。
 *   4. 幂等键没传时照常下单，只是失去防连点能力；这不是错误。
 */
package com.faze.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.trade.bo.OrderCreateBO;
import com.faze.modules.trade.bo.OrderQueryBO;
import com.faze.modules.trade.bo.PayBO;
import com.faze.modules.trade.converter.OrderConverter;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.rule.OrderActionResolver;
import com.faze.modules.trade.service.OrderCreateExecutor;
import com.faze.modules.trade.service.OrderIdempotencyGuard;
import com.faze.modules.trade.service.OrderService;
import com.faze.modules.trade.service.OrderStateService;
import com.faze.modules.trade.service.BalanceQueryService;
import com.faze.modules.trade.service.OrderPayExecutor;
import com.faze.modules.trade.service.TaskQueueService;
import com.faze.modules.trade.vo.OrderDetailVO;
import com.faze.modules.trade.vo.OrderVO;
import com.faze.modules.trade.vo.PayResultVO;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户侧订单服务实现。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrdersMapper ordersMapper;
    private final OrderCreateExecutor orderCreateExecutor;
    private final OrderIdempotencyGuard idempotencyGuard;
    private final OrderConverter orderConverter;
    private final LawyerQueryService lawyerQueryService;
    private final OrderStateService orderStateService;
    private final TaskQueueService taskQueueService;
    private final OrderPayExecutor orderPayExecutor;
    private final BalanceQueryService balanceQueryService;

    /**
     * 下单。
     *
     * <p>副作用：插入 orders + order_status_log；Redis 写幂等键。</p>
     *
     * @param userId         下单用户
     * @param request        下单请求
     * @param idempotencyKey 客户端幂等键，可空
     * @return 订单详情
     */
    @Override
    public OrderDetailVO create(Long userId, OrderCreateBO request, String idempotencyKey) {
        // 任务 1：幂等预查。命中直接返回**已存在的订单**（2-A8 ②）
        Optional<Long> existingOrderId = idempotencyGuard.tryStart(userId, idempotencyKey);
        if (existingOrderId.isPresent()) {
            return detail(userId, existingOrderId.get());
        }

        // 任务 2：真正创建。失败必须释放占位，否则用户 10 分钟内无法再提交
        Orders created;
        try {
            created = orderCreateExecutor.create(userId, request);
        } catch (RuntimeException e) {
            idempotencyGuard.release(userId, idempotencyKey);
            throw e;
        }

        // 任务 3：事务已提交，再把幂等键绑定到订单 id
        idempotencyGuard.bind(userId, idempotencyKey, created.getId());
        return render(created);
    }

    /**
     * 分页查询当前用户的订单。
     *
     * @param userId 当前用户
     * @param query  分页与状态筛选
     * @return 分页结果
     */
    @Override
    public PageResult<OrderVO> list(Long userId, OrderQueryBO query) {
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null ? 20 : Math.min(Math.max(query.getPageSize(), 1), 100);
        List<String> statusCodes = parseStatusCodes(query.getStatus());

        IPage<Orders> page = ordersMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<Orders>lambdaQuery()
                        .eq(Orders::getUserId, userId)
                        .isNull(Orders::getDeletedAt)
                        .in(!statusCodes.isEmpty(), Orders::getStatus, statusCodes)
                        .orderByDesc(Orders::getCreatedAt)
                        .orderByDesc(Orders::getId));

        List<Orders> rows = page.getRecords();
        Map<Long, Lawyer> lawyerMap = lawyerQueryService.mapByIds(rows.stream()
                .map(Orders::getLawyerId)
                .filter(id -> id != null)
                .collect(Collectors.toSet()));
        List<OrderVO> list = rows.stream()
                .map(order -> orderConverter.toVO(order, lawyerMap.get(order.getLawyerId())))
                .collect(Collectors.toList());
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 查询当前用户的订单详情。
     *
     * @param userId  当前用户
     * @param orderId 订单主键
     * @return 订单详情
     * @throws BusinessException 405002 订单不存在（含不属于当前用户）
     */
    @Override
    public OrderDetailVO detail(Long userId, Long orderId) {
        Orders order = findOwned(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND));
        return render(order);
    }

    /**
     * 取消待支付订单。
     *
     * <p>副作用：CAS 把状态改成 CANCELLED、写状态流水、取消那条 15 分钟关单任务。</p>
     *
     * @param userId  当前用户
     * @param orderId 订单主键
     * @throws BusinessException 405002 订单不存在 / 405003 当前状态不允许取消
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long orderId) {
        Orders order = findOwned(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND));
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status == OrderStatus.CANCELLED) {
            // 幂等：重复点取消不报错（用户看到的结果一致）
            return;
        }
        boolean ok = orderStateService.transit(orderId, status, OrderStatus.CANCELLED,
                OperatorRole.USER, userId, "用户取消订单");
        if (!ok) {
            // CAS 未命中：并发下状态刚被别人改过（例如刚好支付成功）
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }
        // 关单任务已无意义：取消未执行的那一条（已领取的会自己看状态跳过）
        taskQueueService.cancelPending(TaskQueueService.TYPE_ORDER_CLOSE, order.getOrderNo());
    }

    /**
     * 用账户余额支付订单。
     *
     * <p>已支付的订单重复调用返回 `duplicated=true` 的成功结果（2-A8 ②「连点只扣一次」）：
     * 第二次进来时状态已是 PAID，**不再走扣款**。</p>
     *
     * @param userId  当前用户
     * @param orderId 订单主键
     * @param request 支付密码
     * @return 支付结果
     * @throws BusinessException 405002 订单不存在 / 405003 状态不允许支付
     */
    @Override
    public PayResultVO pay(Long userId, Long orderId, PayBO request) {
        Orders order = findOwned(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND));
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status == OrderStatus.PAID) {
            return duplicatedPayResult(order);
        }
        if (status != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }
        return orderPayExecutor.pay(order, request.getPayPassword());
    }

    /**
     * 构造「已支付」的幂等结果（不扣款）。
     *
     * @param order 已支付的订单
     * @return 支付结果（duplicated=true）
     */
    private PayResultVO duplicatedPayResult(Orders order) {
        PayResultVO vo = new PayResultVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setAmountCents(order.getAmountCents());
        vo.setPaidAt(order.getPaidAt());
        vo.setBalanceAfterCents(balanceQueryService.getAvailableCents(order.getUserId()));
        vo.setDuplicated(true);
        return vo;
    }

    /**
     * 按 id + 归属查订单（已排除软删）。
     *
     * @param orderId 订单主键
     * @param userId  当前用户
     * @return 订单；不存在或不属于该用户时返回空
     */
    private Optional<Orders> findOwned(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ordersMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getId, orderId)
                .eq(Orders::getUserId, userId)
                .isNull(Orders::getDeletedAt)));
    }

    /**
     * 把订单实体渲染成详情 VO（查一次律师 + 推导动作）。
     *
     * @param order 订单实体
     * @return 详情 VO
     */
    private OrderDetailVO render(Orders order) {
        Lawyer lawyer = order.getLawyerId() == null
                ? null
                : lawyerQueryService.findById(order.getLawyerId()).orElse(null);
        OrderStatus status = OrderStatus.of(order.getStatus());
        // 能走到这里的都是「用户查自己的单」，所以 ownerIsViewer = true
        List<String> actions = OrderActionResolver.resolve(status, true);
        return orderConverter.toDetailVO(order, lawyer, actions);
    }

    /**
     * 解析状态筛选参数（逗号分隔，未知值忽略）。
     *
     * @param raw 原始参数，可空（如 {@code paid,in_service}）
     * @return 合法状态 code 列表；无有效值时返回空列表（= 不筛）
     */
    private List<String> parseStatusCodes(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String part : raw.split(",")) {
            String code = part.trim();
            if (code.isEmpty()) {
                continue;
            }
            if (OrderStatus.ofOrNull(code) != null) {
                codes.add(code);
            } else {
                unknown.add(code);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("订单列表 status 参数含未知状态，已忽略 values={}", unknown);
        }
        return List.copyOf(codes);
    }
}
