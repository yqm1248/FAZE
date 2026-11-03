/*
 * 职责：提供「需鉴权资源」——GET /api/v1/app/account/profile。它是 0-A2 验 HTTP 401 的**唯一载体**。
 * 调用方：App 端登录页 / 首页（0-A2：token 失效后请求任一需鉴权接口 → 自动清 token 回登录页）。
 * 数据来源：SecurityContext 里的 LoginUser（由 JwtAuthenticationFilter 从 JWT 填充）→ AuthService 查库。
 * 注意：
 *   1. **只做 GET**。PUT（修改资料）属段 1，段 0 实现它就是越界（§七 明确不做）。
 *   2. `@AuthenticationPrincipal` 在未认证时注入 null，这里兜底抛 402003——
 *      理论上 SecurityConfig 的 `authenticated()` 已拦在前面，但**不许依赖「一定进不来」**：
 *      只要有人改了授权规则，这里就会变成 NPE 而不是 401。
 *   3. 资料一律从库里重新查，**不要直接信 JWT 里的 claim**：token 有效期内用户可能已被封禁/注销。
 */
package com.faze.modules.account.controller;

import com.faze.common.BusinessException;
import com.faze.common.ErrorCode;
import com.faze.common.R;
import com.faze.modules.account.dto.UserVO;
import com.faze.modules.account.service.AuthService;
import com.faze.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户资料接口。
 */
@RestController
@RequestMapping("/api/v1/app/account")
public class ProfileController {

    private final AuthService authService;

    /**
     * 构造控制器。
     *
     * @param authService 账户服务
     */
    public ProfileController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 取当前登录用户资料。
     *
     * @param loginUser SecurityContext 中的认证主体（未认证时为 null）
     * @return 用户资料
     * @throws BusinessException 402003 未认证或用户已不存在
     */
    @GetMapping("/profile")
    public R<UserVO> profile(@AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return R.ok(authService.profile(loginUser.getUserId()));
    }
}
