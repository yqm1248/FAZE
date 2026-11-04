/*
 * 职责：支付密码 HTTP 入口——设置 / 修改 / 重置 / 校验（第 1 段 E1 + E2）。
 * 调用方：App「支付密码」页、「账号与安全」页；校验接口同时是 1-A5 的验收载体。
 * 数据来源：请求体 + SecurityContext 里的 LoginUser；业务在 PayPasswordService。
 * 注意：
 *   1. 四个接口都需登录（`/api/v1/app/**` 由 SecurityConfig 保护）。
 *   2. **校验接口（/verify）不在《接口规范》§7.2 的清单里**，属于 E2 需要的补项：
 *      「连错 5 次锁定」这条验收必须有一个能真正跑校验的入口，
 *      而修改密码路径的旧密码校验语义不同（改密失败与支付失败不应混在同一入口的返回里）。
 *      段 2 的支付链路也复用同一个服务方法。
 *   3. 锁定期与密码错误的「剩余秒数 / 剩余次数」走响应头（Retry-After / X-Pay-Pwd-Remaining），
 *      由 GlobalExceptionHandler 统一写，Controller 不碰 header。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.bo.ChangePayPasswordBO;
import com.faze.modules.account.bo.ResetPayPasswordBO;
import com.faze.modules.account.bo.SetPayPasswordBO;
import com.faze.modules.account.bo.VerifyPayPasswordBO;
import com.faze.modules.account.service.PayPasswordService;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付密码接口。
 */
@RestController
@RequestMapping("/api/v1/app/account/pay-password")
@RequiredArgsConstructor
public class PayPasswordController {

    private final PayPasswordService payPasswordService;

    /**
     * 首次设置支付密码。
     *
     * @param request   设置请求
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping
    public R<Void> set(@Valid @RequestBody SetPayPasswordBO request,
                       @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        payPasswordService.set(loginUser.getUserId(), request.getPassword());
        return R.ok();
    }

    /**
     * 修改支付密码（需旧支付密码）。
     *
     * @param request   修改请求
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PutMapping
    public R<Void> change(@Valid @RequestBody ChangePayPasswordBO request,
                          @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        payPasswordService.change(loginUser.getUserId(), request.getOldPassword(), request.getNewPassword());
        return R.ok();
    }

    /**
     * 重置支付密码（短信验证码）。
     *
     * @param request   重置请求
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping("/reset")
    public R<Void> reset(@Valid @RequestBody ResetPayPasswordBO request,
                         @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        payPasswordService.reset(loginUser.getUserId(), request.getSmsCode(), request.getNewPassword());
        return R.ok();
    }

    /**
     * 校验支付密码（1-A5 验收载体；段 2 支付前预校验复用同一服务方法）。
     *
     * @param request   校验请求
     * @param loginUser 当前认证主体
     * @return 空响应（成功即密码正确）
     */
    @PostMapping("/verify")
    public R<Void> verify(@Valid @RequestBody VerifyPayPasswordBO request,
                          @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        payPasswordService.verify(loginUser.getUserId(), request.getPassword());
        return R.ok();
    }

    /**
     * 未认证兜底。
     *
     * @param loginUser 当前认证主体
     * @throws BusinessException 402003 未认证
     */
    private void requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
    }
}
