/*
 * 职责：送达的 HTTP 入口（当前只有选择送达方式；C 组会补列表/发起/签收）。
 * 调用方：App 端（22-service 送达页）。
 * 数据来源：@AuthenticationPrincipal 取当前用户；业务全在 DeliveryService。
 * 注意：
 *   1. 当前用户只从 SecurityContext 取；送达记录归属由 Service 的 SQL 条件保证。
 *   2. 路径在 `/api/v1/app/**` 下，未登录由 SecurityConfig 返回 401。
 */
package com.faze.modules.caseflow.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.caseflow.bo.DeliveryMethodBO;
import com.faze.modules.caseflow.service.DeliveryService;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 送达接口。
 */
@RestController
@RequestMapping("/api/v1/app/case-deliveries")
public class AppDeliveryController {

    private final DeliveryService deliveryService;

    /**
     * 构造控制器。
     *
     * @param deliveryService 送达业务
     */
    public AppDeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * 选择送达方式。
     *
     * @param loginUser 当前登录用户
     * @param id        送达记录 id
     * @param bo        方式入参
     * @return 空响应
     */
    @PutMapping("/{id}/method")
    public R<Void> chooseMethod(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Positive Long id,
            @Valid @RequestBody DeliveryMethodBO bo) {
        deliveryService.chooseMethod(requireLogin(loginUser).getUserId(), id, bo.getMethod());
        return R.ok();
    }

    /**
     * 断言已登录。
     *
     * @param loginUser Security 注入的当前用户
     * @return 非空的当前用户
     * @throws BusinessException 未登录时抛 402003
     */
    private static LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null || loginUser.getUserId() == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "未登录访问送达接口");
        }
        return loginUser;
    }
}
