/*
 * 职责：账户域的 HTTP 入口——登录、刷新令牌两个接口。
 * 调用方：App 端（0-A2 验真接口与 401）。
 * 数据来源：请求体 + 请求头（X-Device-Id）+ 客户端 IP；业务全在 AuthService。
 * 注意：
 *   1. **Controller 不注入 Mapper、不写业务、不开事务**（后端规范 §4 分层禁令）。
 *      这里只做三件事：收参数、调 Service、包 R。
 *   2. `POST /api/v1/app/auth/refresh` 虽是 `app` 前缀，但**必须在 SecurityConfig 里放行**：
 *      调用它的前提是 access token 已过期，落进 authenticated() 就永远刷不了。
 *   3. 不给这两个接口加 `@Profile`：登录不是探针，生产必须可用。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.util.RequestUtils;
import com.faze.modules.account.dto.LoginRequest;
import com.faze.modules.account.dto.LoginResponse;
import com.faze.modules.account.dto.RefreshRequest;
import com.faze.modules.account.dto.TokenResponse;
import com.faze.modules.account.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录与令牌接口。
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    /**
     * 构造控制器。
     *
     * @param authService 账户服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 手机号 + 密码登录。
     *
     * <p>副作用：方法内部会更新最后登录时间并签发令牌（无 HTTP 副作用以外的写操作）。</p>
     *
     * @param request 登录请求体（@Valid 触发 Bean Validation）
     * @param http    当前请求，用于取 X-Device-Id 与客户端 IP
     * @return 令牌 + 用户资料
     */
    @PostMapping("/pub/auth/login/password")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return R.ok(authService.login(request, RequestUtils.deviceId(http), RequestUtils.clientIp(http)));
    }

    /**
     * 用 refresh token 换新令牌（单飞、轮换）。
     *
     * <p>副作用：旧 refresh token 失效，新令牌落库到进程内存储。</p>
     *
     * @param request 刷新请求体
     * @return 新令牌对
     */
    @PostMapping("/app/auth/refresh")
    public R<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return R.ok(authService.refresh(request.getRefreshToken()));
    }
}
