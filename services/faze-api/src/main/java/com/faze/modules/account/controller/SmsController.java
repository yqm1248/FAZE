/*
 * 职责：短信验证码的 HTTP 入口（第 1 段 B1）。
 * 调用方：App 端注册 / 短信登录 / 重置密码页。
 * 数据来源：请求体 + 客户端 IP；业务全在 SmsCodeService。
 * 注意：
 *   1. Controller 只做「收参 → 调 Service → 包 R」，不写限流分支（后端规范 §3.2）。
 *   2. 出参是 R<Void>：**不回传验证码**，连 dev 也不回传（B1 第 4 条硬约束）。
 *   3. 路径在 /api/v1/pub/ 下，SecurityConfig 已放行；这是必须的——注册前还没有 token。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.util.RequestUtils;
import com.faze.modules.account.bo.SmsCodeBO;
import com.faze.modules.account.service.SmsCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短信验证码接口。
 */
@RestController
@RequestMapping("/api/v1/pub/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsCodeService smsCodeService;

    /**
     * 发送验证码。
     *
     * <p>副作用：写 Redis 验证码与三层限流计数；不返回验证码本身。</p>
     *
     * @param request 发码请求
     * @param http    当前请求，用于取客户端 IP
     * @return 空响应（成功即 code=200）
     */
    @PostMapping("/code")
    public R<Void> send(@Valid @RequestBody SmsCodeBO request, HttpServletRequest http) {
        smsCodeService.send(request.getPhone(), request.getScene(), RequestUtils.clientIp(http));
        return R.ok();
    }
}
