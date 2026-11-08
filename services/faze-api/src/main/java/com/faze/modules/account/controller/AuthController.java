/*
 * 职责：账户域的 HTTP 入口——注册、密码登录、短信登录、刷新令牌四个接口。
 * 调用方：App 端（0-A2 / 1-A1 验真接口与 401）。
 * 数据来源：请求体 + 请求头（X-Device-Id / User-Agent）+ 客户端 IP；业务全在 AuthService。
 * 注意：
 *   1. **Controller 不注入 Mapper、不写业务、不开事务**（后端规范 §2.2 分层禁令）。
 *      这里只做三件事：收参、调 Service、包 R。
 *   2. `POST /api/v1/app/auth/refresh` 虽是 `app` 前缀，但**必须在 SecurityConfig 里放行**：
 *      调用它的前提是 access token 已过期，落进 authenticated() 就永远刷不了。
 *   3. 不给这四个接口加 `@Profile`：登录注册不是探针，生产必须可用。
 *   4. 三个 `pub` 接口（register / login/password / login/sms）必须免登录，
 *      第三个在段 0 已放行，前两个随 `/api/v1/pub/**` 一并放行。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.util.RequestUtils;
import com.faze.modules.account.bo.LoginBO;
import com.faze.modules.account.bo.RefreshTokenBO;
import com.faze.modules.account.bo.RegisterBO;
import com.faze.modules.account.bo.ResetPasswordBO;
import com.faze.modules.account.bo.SmsLoginBO;
import com.faze.modules.account.service.AccountSecurityService;
import com.faze.modules.account.service.AuthService;
import com.faze.modules.account.vo.LoginVO;
import com.faze.modules.account.vo.TokenVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册、登录与令牌接口。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AccountSecurityService accountSecurityService;

    /**
     * 手机号 + 验证码注册（成功后直接登录）。
     *
     * <p>副作用：建用户、写 login_log、签发令牌，三者同一事务。</p>
     *
     * @param request 注册请求体
     * @param http    当前请求，用于取客户端 IP 与 User-Agent
     * @return 令牌 + 用户资料
     */
    @PostMapping("/pub/auth/register")
    public R<LoginVO> register(@Valid @RequestBody RegisterBO request, HttpServletRequest http) {
        return R.ok(authService.register(request, RequestUtils.clientIp(http), userAgent(http)));
    }

    /**
     * 手机号 + 密码登录。
     *
     * <p>副作用：更新最后登录时间、写 login_log、签发令牌。</p>
     *
     * @param request 登录请求体（@Valid 触发 Bean Validation）
     * @param http    当前请求，用于取 X-Device-Id、客户端 IP 与 User-Agent
     * @return 令牌 + 用户资料
     */
    @PostMapping("/pub/auth/login/password")
    public R<LoginVO> login(@Valid @RequestBody LoginBO request, HttpServletRequest http) {
        return R.ok(authService.login(request, RequestUtils.deviceId(http),
                RequestUtils.clientIp(http), userAgent(http)));
    }

    /**
     * 手机号 + 验证码登录（必须已注册）。
     *
     * <p>副作用：更新最后登录时间、写 login_log、签发令牌。</p>
     *
     * @param request 短信登录请求体
     * @param http    当前请求，用于取客户端 IP 与 User-Agent
     * @return 令牌 + 用户资料
     */
    @PostMapping("/pub/auth/login/sms")
    public R<LoginVO> loginBySms(@Valid @RequestBody SmsLoginBO request, HttpServletRequest http) {
        return R.ok(authService.loginBySms(request, RequestUtils.clientIp(http), userAgent(http)));
    }

    /**
     * 用 refresh token 换新令牌（单飞、轮换）。
     *
     * <p>副作用：旧 refresh token 失效，新令牌写入存储。</p>
     *
     * @param request 刷新请求体
     * @return 新令牌对
     */
    @PostMapping("/app/auth/refresh")
    public R<TokenVO> refresh(@Valid @RequestBody RefreshTokenBO request) {
        return R.ok(authService.refresh(request.getRefreshToken()));
    }

    /**
     * 忘记密码（重置）。
     *
     * <p>副作用：该用户全部 token 失效、密码更新；**不返回令牌**，用户需重新登录。</p>
     *
     * @param request 重置请求体
     * @return 空响应
     */
    @PostMapping("/pub/auth/reset-password")
    public R<Void> resetPassword(@Valid @RequestBody ResetPasswordBO request) {
        accountSecurityService.resetPassword(request);
        return R.ok();
    }

    /**
     * 取 User-Agent，超长截断到 500（与 login_log.user_agent 列宽一致）。
     *
     * @param http 当前请求
     * @return UA 字符串，可能为 null
     */
    private String userAgent(HttpServletRequest http) {
        String ua = http.getHeader(HttpHeaders.USER_AGENT);
        if (ua == null) {
            return null;
        }
        return ua.length() <= 500 ? ua : ua.substring(0, 500);
    }
}
