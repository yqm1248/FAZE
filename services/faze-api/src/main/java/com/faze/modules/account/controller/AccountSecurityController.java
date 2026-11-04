/*
 * 职责：账号安全相关的 HTTP 入口——修改密码、换绑手机（都需登录）。
 * 调用方：App「账号与安全」页、修改密码页、换绑手机页。
 * 数据来源：请求体 + SecurityContext 里的 LoginUser；业务全在 AccountSecurityService。
 * 注意：
 *   1. 路径都在 `/api/v1/app/account/**`，由 SecurityConfig 的 `authenticated()` 保护。
 *   2. **不在这里做密码强度、验证码、唯一性校验**——那些都在 Service，
 *      Controller 只负责收参、取当前用户、包 R。
 *   3. 取当前用户用 `@AuthenticationPrincipal`；未认证时注入 null，这里兜底抛 402003，
 *      不依赖「反正前面已经拦了」。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.bo.ChangePasswordBO;
import com.faze.modules.account.bo.ChangePhoneBO;
import com.faze.modules.account.service.AccountSecurityService;
import com.faze.modules.account.vo.TokenVO;
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
 * 账号安全接口。
 */
@RestController
@RequestMapping("/api/v1/app/account")
@RequiredArgsConstructor
public class AccountSecurityController {

    private final AccountSecurityService accountSecurityService;

    /**
     * 修改密码。
     *
     * <p>副作用：其他设备的 token 全部失效，当前客户端换发新令牌。</p>
     *
     * @param request   修改密码请求
     * @param loginUser 当前认证主体
     * @return 新令牌对
     */
    @PutMapping("/password")
    public R<TokenVO> changePassword(@Valid @RequestBody ChangePasswordBO request,
                                     @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(accountSecurityService.changePassword(
                loginUser.getUserId(), request.getOldPassword(), request.getNewPassword()));
    }

    /**
     * 换绑手机号。
     *
     * <p>副作用：手机号变更、所有旧 token 失效、写操作审计。</p>
     *
     * @param request   换绑请求
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping("/change-phone")
    public R<Void> changePhone(@Valid @RequestBody ChangePhoneBO request,
                               @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        accountSecurityService.changePhone(loginUser.getUserId(), request);
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
