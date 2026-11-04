/*
 * 职责：资料接口——GET /app/account/profile（0-A2 验 401 的载体）、PUT 修改资料。
 * 调用方：App「我的」页 / 个人资料编辑页。
 * 数据来源：SecurityContext 里的 LoginUser（由 JwtAuthenticationFilter 从 JWT 填充）
 *           → ProfileService 查库组装（基础资料 + 律师状态 + 余额）。
 * 注意：
 *   1. **GET 是 0-A2 的唯一载体**，路径与响应结构不许改（App 端靠它判 401）。
 *   2. `@AuthenticationPrincipal` 在未认证时注入 null，这里兜底抛 402003——
 *      理论上 SecurityConfig 的 `authenticated()` 已拦在前面，但**不许依赖「一定进不来」**：
 *      只要有人改了授权规则，这里就会变成 NPE 而不是 401。
 *   3. 资料一律从库里重新查，**不要直接信 JWT 里的 claim**：token 有效期内用户可能已被封禁/注销。
 *   4. PUT 只改昵称 / 头像 / 性别，**手机号走 B3 换绑、实名走 C 组**，都不从资料接口进来。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.bo.UpdateProfileBO;
import com.faze.modules.account.service.AuthService;
import com.faze.modules.account.service.ProfileService;
import com.faze.modules.account.vo.ProfileVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户资料接口。
 */
@RestController
@RequestMapping("/api/v1/app/account")
@RequiredArgsConstructor
public class ProfileController {

    private final AuthService authService;
    private final ProfileService profileService;

    /**
     * 取当前登录用户资料。
     *
     * @param loginUser SecurityContext 中的认证主体（未认证时为 null）
     * @return 资料（含律师状态与余额占位）
     * @throws BusinessException 402003 未认证或用户已不存在
     */
    @GetMapping("/profile")
    public R<ProfileVO> profile(@AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(profileService.profile(loginUser.getUserId()));
    }

    /**
     * 修改资料（昵称 / 头像 / 性别）。
     *
     * <p>副作用：更新 app_user；手机号与实名不在此接口。</p>
     *
     * @param request   修改请求
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PutMapping("/profile")
    public R<Void> updateProfile(@Valid @RequestBody UpdateProfileBO request,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        authService.updateProfile(loginUser.getUserId(), request);
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
