/*
 * 职责：律师侧订单 HTTP 入口——订单列表、接单、拒单、结束服务（G3.3 / G3.4）。
 * 调用方：APP「律师订单管理」页（64-lawyer-orders.html）、「律师工作台」（62-workbench.html）。
 * 数据来源：LawyerOrderService（内部按 token 的 userId 反查 lawyer.id）。
 * 注意：
 *   1. **URL 契约**：接单/拒单/完成按工作流五.2 的 `POST /app/orders/{id}/accept|reject|complete`；
 *      律师侧列表工作流没有给端点（是计划缺口），本类补 `GET /app/lawyer/orders`。
 *      之所以不把列表做成 `GET /app/orders/lawyer`：`/app/orders/{id}` 的 `{id}` 是 Long，
 *      同前缀放一个 `lawyer` 字面量虽能靠精确匹配优先命中，但反射出的 OpenAPI 会出现
 *      「id 可以是 lawyer」的歧义描述，换成独立前缀更干净。
 *   2. **律师身份来自 token**：不接收任何 lawyerId 参数（接收即越权）。
 *   3. 本类不注入 Mapper、不写业务、不开事务（后端规范 §2.1）。
 */
package com.faze.modules.trade.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.trade.service.LawyerOrderService;
import com.faze.modules.trade.vo.LawyerOrderVO;
import com.faze.security.LoginUser;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 律师侧订单接口。
 */
@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
public class LawyerOrderController {

    private final LawyerOrderService lawyerOrderService;

    /**
     * 律师侧订单列表。
     *
     * @param status    状态筛选（逗号分隔，可空）
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param loginUser 当前认证主体
     * @return 分页结果
     */
    @GetMapping("/lawyer/orders")
    public R<PageResult<LawyerOrderVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(lawyerOrderService.list(requireLogin(loginUser).getUserId(),
                status, pageNum, pageSize));
    }

    /**
     * 接单。
     *
     * @param id        订单主键
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping("/orders/{id}/accept")
    public R<Void> accept(@PathVariable Long id,
                          @AuthenticationPrincipal LoginUser loginUser) {
        lawyerOrderService.accept(requireLogin(loginUser).getUserId(), id);
        return R.ok();
    }

    /**
     * 拒单（会触发自动全额退款）。
     *
     * @param id        订单主键
     * @param request   拒单原因（可空）
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping("/orders/{id}/reject")
    public R<Void> reject(@PathVariable Long id,
                          @RequestBody(required = false) RejectRequest request,
                          @AuthenticationPrincipal LoginUser loginUser) {
        String reason = request == null ? null : request.getReason();
        lawyerOrderService.reject(requireLogin(loginUser).getUserId(), id, reason);
        return R.ok();
    }

    /**
     * 结束服务。
     *
     * @param id        订单主键
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping("/orders/{id}/complete")
    public R<Void> complete(@PathVariable Long id,
                            @AuthenticationPrincipal LoginUser loginUser) {
        lawyerOrderService.complete(requireLogin(loginUser).getUserId(), id);
        return R.ok();
    }

    /**
     * 断言已登录。
     *
     * @param loginUser 认证主体
     * @return 非空认证主体
     * @throws BusinessException 402003 凭证无效
     */
    private LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return loginUser;
    }

    /**
     * 拒单入参。
     */
    @Data
    public static class RejectRequest {

        /** 拒单原因，可空；最长 200 字（与 order_status_log.reason 一致）。 */
        @Size(max = 200, message = "reason 最长 200 字")
        private String reason;
    }
}
