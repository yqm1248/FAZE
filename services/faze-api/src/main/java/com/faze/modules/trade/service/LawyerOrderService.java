/*
 * 职责：律师侧订单业务——列表、接单、拒单、结束服务（G3.3 / G3.4）。
 * 调用方：modules/trade/controller/LawyerOrderController。
 * 数据来源：orders（读写）、lawyer（定位当前律师、订单数递增）、app_user（下单人昵称）、
 *          OrderStateService（状态迁移）、OrderRefundService（拒单自动退款）。
 * 注意：
 *   1. **归属校验进 SQL**：所有操作都按 `id + lawyer_id + deleted_at IS NULL` 查，
 *      查不到统一 405002「订单不存在」。先查出来再 `if (order.getLawyerId() != ...)` 比对，
 *      忘写一次就是越权（《后端代码规范》§11.2）。
 *   2. 律师身份**从 token 的 userId 反查 lawyer.id**，不从请求参数取：
 *      否则任何律师都能拿别人的 lawyerId 操作订单。
 *   3. 拒单 = `PAID → REFUNDING`（LAWYER）+ 自动全额退款：钱已经收了，拒单必须退钱，
 *      不能只改状态。退款后的 `REFUNDING → REFUNDED` 由系统（SYSTEM）触发。
 *   4. 接单要 `lawyer.order_count + 1`，用**原子自增 SQL**（`order_count = order_count + 1`）
 *      而不是「读出来 +1 写回去」：两个并发接单会互相覆盖，订单数长期偏小。
 *   5. 结束服务只能律师做（状态机 `IN_SERVICE → COMPLETED` 只允许 LAWYER 角色）。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.rule.OrderActionResolver;
import com.faze.modules.trade.vo.LawyerOrderVO;
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
 * 律师侧订单服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LawyerOrderService {

    /** 每页默认条数。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页最大条数。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final LawyerQueryService lawyerQueryService;
    private final OrdersMapper ordersMapper;
    private final LawyerMapper lawyerMapper;
    private final OrderStateService orderStateService;
    private final OrderRefundService orderRefundService;
    private final AccountMapper accountMapper;

    /**
     * 分页查询当前律师的订单。
     *
     * @param userId 当前登录用户（律师）
     * @param status 状态筛选（逗号分隔，可空）
     * @param pageNum 页码，可空
     * @param pageSize 每页条数，可空
     * @return 分页结果
     * @throws BusinessException 401008 当前用户不是律师
     */
    public PageResult<LawyerOrderVO> list(Long userId, String status, Integer pageNum, Integer pageSize) {
        Lawyer lawyer = requireLawyer(userId);
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        List<String> statusCodes = parseStatusCodes(status);

        IPage<Orders> page = ordersMapper.selectPage(new Page<>(num, size),
                Wrappers.<Orders>lambdaQuery()
                        .eq(Orders::getLawyerId, lawyer.getId())
                        .isNull(Orders::getDeletedAt)
                        .in(!statusCodes.isEmpty(), Orders::getStatus, statusCodes)
                        .orderByDesc(Orders::getCreatedAt)
                        .orderByDesc(Orders::getId));
        List<Orders> rows = page.getRecords();
        Map<Long, String> nicknames = loadNicknames(rows);
        List<LawyerOrderVO> list = rows.stream()
                .map(order -> toVO(order, nicknames.get(order.getUserId())))
                .collect(Collectors.toList());
        return PageResult.of(list, page.getTotal(), num, size);
    }

    /**
     * 接单：PAID → IN_SERVICE，并把律师接单数 +1。
     *
     * @param userId  当前登录用户
     * @param orderId 订单主键
     * @throws BusinessException 401008 不是律师 / 405002 订单不存在或不属于本人 / 405003 状态不允许
     */
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long userId, Long orderId) {
        Lawyer lawyer = requireLawyer(userId);
        findOrderForLawyer(orderId, lawyer.getId());
        boolean ok = orderStateService.transit(orderId, OrderStatus.PAID, OrderStatus.IN_SERVICE,
                OperatorRole.LAWYER, userId, "律师接单");
        if (!ok) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }
        lawyerMapper.update(null, Wrappers.<Lawyer>lambdaUpdate()
                .setSql("order_count = order_count + 1")
                .eq(Lawyer::getId, lawyer.getId()));
        log.info("律师接单 lawyerId={} orderId={}", lawyer.getId(), orderId);
    }

    /**
     * 拒单：PAID → REFUNDING 并**自动全额退款**（钱已收，拒单必须退）。
     *
     * @param userId  当前登录用户
     * @param orderId 订单主键
     * @param reason  拒单原因（可空，写入状态流水）
     * @throws BusinessException 401008 / 405002 / 405003
     */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long userId, Long orderId, String reason) {
        Lawyer lawyer = requireLawyer(userId);
        Orders order = findOrderForLawyer(orderId, lawyer.getId());
        String flowReason = StringUtils.hasText(reason) ? "律师拒单：" + reason : "律师拒单";
        boolean ok = orderStateService.transit(orderId, OrderStatus.PAID, OrderStatus.REFUNDING,
                OperatorRole.LAWYER, userId, flowReason);
        if (!ok) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }
        // 拒单必须退钱：订单已在 REFUNDING，直接走入账结算（幂等键 = orderId）
        orderRefundService.settleRefund(order, flowReason, "律师拒单，系统自动全额退款");
        log.info("律师拒单并已退款 lawyerId={} orderId={} reason={}", lawyer.getId(), orderId, reason);
    }

    /**
     * 结束服务：IN_SERVICE → COMPLETED。
     *
     * @param userId  当前登录用户
     * @param orderId 订单主键
     * @throws BusinessException 401008 / 405002 / 405003
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long userId, Long orderId) {
        Lawyer lawyer = requireLawyer(userId);
        findOrderForLawyer(orderId, lawyer.getId());
        boolean ok = orderStateService.transit(orderId, OrderStatus.IN_SERVICE, OrderStatus.COMPLETED,
                OperatorRole.LAWYER, userId, "律师结束服务");
        if (!ok) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }
        log.info("律师结束服务 lawyerId={} orderId={}", lawyer.getId(), orderId);
    }

    /**
     * 取当前用户的律师身份。
     *
     * @param userId 当前登录用户
     * @return 律师实体
     * @throws BusinessException 401008 当前用户不是律师（或已清退）
     */
    public Lawyer requireLawyer(Long userId) {
        Optional<Lawyer> lawyer = lawyerQueryService.findByUserId(userId);
        if (lawyer.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN, "当前账号不是律师");
        }
        return lawyer.get();
    }

    /**
     * 查「本律师名下」的订单（归属进 SQL）。
     *
     * @param orderId  订单主键
     * @param lawyerId 律师 id
     * @return 订单实体
     * @throws BusinessException 405002 不存在或不属于本律师
     */
    private Orders findOrderForLawyer(Long orderId, Long lawyerId) {
        Orders order = orderId == null ? null : ordersMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getId, orderId)
                .eq(Orders::getLawyerId, lawyerId)
                .isNull(Orders::getDeletedAt));
        if (order == null) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 批量取下单人昵称。
     *
     * @param rows 订单行
     * @return 用户主键 → 昵称
     */
    private Map<Long, String> loadNicknames(List<Orders> rows) {
        Set<Long> userIds = rows.stream()
                .map(Orders::getUserId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return accountMapper.selectBatchIds(userIds).stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(AppUser::getId, AppUser::getNickname, (left, right) -> left));
    }

    /**
     * 订单行 → 律师侧 VO。
     *
     * @param order        订单
     * @param userNickname 下单人昵称
     * @return VO
     */
    private LawyerOrderVO toVO(Orders order, String userNickname) {
        LawyerOrderVO vo = new LawyerOrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setServiceType(order.getServiceType());
        vo.setStatus(order.getStatus());
        vo.setAmountCents(order.getAmountCents());
        vo.setLawyerIncomeCents(order.getLawyerIncomeCents());
        vo.setRemark(order.getRemark());
        vo.setUserNickname(userNickname);
        vo.setCreatedAt(order.getCreatedAt());
        vo.setPaidAt(order.getPaidAt());
        vo.setAcceptedAt(order.getAcceptedAt());
        OrderStatus status = OrderStatus.ofOrNull(order.getStatus());
        vo.setActions(OrderActionResolver.resolveForLawyer(status));
        return vo;
    }

    /**
     * 解析状态筛选参数（未知值忽略）。
     *
     * @param raw 逗号分隔状态
     * @return 合法状态 code 列表
     */
    private List<String> parseStatusCodes(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .filter(code -> !code.isEmpty() && OrderStatus.ofOrNull(code) != null)
                .distinct()
                .collect(Collectors.toList());
    }
}
