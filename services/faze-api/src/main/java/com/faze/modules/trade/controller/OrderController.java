/*
 * 职责：订单 HTTP 入口——下单、列表、详情（G2.1）。
 * 调用方：APP「我的订单」页（29-orders.html）、「订单详情」页（30-order-detail.html）、
 *         「下单」流程（律师详情页点「立即咨询」）。
 * 数据来源：SecurityContext 里的 LoginUser → OrderService。
 * 注意：
 *   1. 需登录；`userId` 一律来自 token，**不从请求体/参数取**（取参数就是越权）。
 *   2. 本类不注入 Mapper、不写业务、不开事务（《后端代码规范》§2.1 分层禁令）。
 *   3. 下单请求体**没有金额字段**：金额由服务端按律师价格算，客户端传了也会被忽略。
 *   4. 幂等键走 HTTP 头 `Idempotency-Key`（与段 1 充值口径一致），缺失不报错。
 */
package com.faze.modules.trade.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.trade.bo.OrderCreateBO;
import com.faze.modules.trade.bo.OrderQueryBO;
import com.faze.modules.trade.service.OrderService;
import com.faze.modules.trade.vo.OrderDetailVO;
import com.faze.modules.trade.vo.OrderVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口（用户侧）。
 */
@RestController
@RequestMapping("/api/v1/app/orders")
@RequiredArgsConstructor
public class OrderController {

    /** 幂等键请求头名（与《接口规范》的 9 个请求头之一一致）。 */
    private static final String HEADER_IDEMPOTENCY = "Idempotency-Key";

    private final OrderService orderService;

    /**
     * 下单。
     *
     * @param request        下单请求（律师/服务类型/备注，**不含金额**）
     * @param idempotencyKey 幂等键，可空
     * @param loginUser      当前认证主体
     * @return 新建订单的详情（含 PENDING 状态与支付截止时间）
     */
    @PostMapping
    public R<OrderDetailVO> create(@Valid @RequestBody OrderCreateBO request,
                                   @RequestHeader(value = HEADER_IDEMPOTENCY, required = false)
                                   String idempotencyKey,
                                   @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(orderService.create(requireLogin(loginUser).getUserId(), request, idempotencyKey));
    }

    /**
     * 分页查询我的订单。
     *
     * @param query     分页与状态筛选（status 支持逗号分隔多值）
     * @param loginUser 当前认证主体
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<OrderVO>> list(OrderQueryBO query,
                                       @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(orderService.list(requireLogin(loginUser).getUserId(), query));
    }

    /**
     * 查询订单详情。
     *
     * @param id        订单主键
     * @param loginUser 当前认证主体
     * @return 订单详情（含服务端推导的可执行动作）
     */
    @GetMapping("/{id}")
    public R<OrderDetailVO> detail(@PathVariable Long id,
                                   @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(orderService.detail(requireLogin(loginUser).getUserId(), id));
    }

    /**
     * 取消待支付订单。
     *
     * @param id        订单主键
     * @param loginUser 当前认证主体
     * @return 空响应；客户端取消成功后重新拉详情
     */
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id,
                          @AuthenticationPrincipal LoginUser loginUser) {
        orderService.cancel(requireLogin(loginUser).getUserId(), id);
        return R.ok();
    }

    /**
     * 断言已登录（Controller 只做这一件事，业务校验在 Service）。
     *
     * @param loginUser 认证主体
     * @return 非空的认证主体
     * @throws BusinessException 401003 凭证无效
     */
    private LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return loginUser;
    }
}
