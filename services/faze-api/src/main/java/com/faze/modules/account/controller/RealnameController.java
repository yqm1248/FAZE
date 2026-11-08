/*
 * 职责：实名认证 HTTP 入口——提交与查询。
 * 调用方：App 实名认证页（「审核中 / 已通过 / 被驳回」三种状态）。
 * 数据来源：请求体 + SecurityContext 里的 LoginUser；业务在 RealnameService。
 * 注意：
 *   1. 两个接口都需登录（`/api/v1/app/**` 由 SecurityConfig 保护）。
 *   2. 身份证号明文只在请求体里出现一次，Service 里立刻加密；
 *      **本 Controller 不打印请求体**（连 @Valid 失败的日志都不带 body）。
 *   3. 审核动作不在这里（PC A-04 / F2），本段只提供 App 侧提交与状态查询。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.bo.RealnameBO;
import com.faze.modules.account.service.RealnameService;
import com.faze.modules.account.vo.RealnameVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实名认证接口。
 */
@RestController
@RequestMapping("/api/v1/app/account/realname")
@RequiredArgsConstructor
public class RealnameController {

    private final RealnameService realnameService;

    /**
     * 提交实名认证。
     *
     * @param request   提交请求
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping
    public R<Void> submit(@Valid @RequestBody RealnameBO request,
                          @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        realnameService.submit(loginUser.getUserId(), request);
        return R.ok();
    }

    /**
     * 查询实名认证状态。
     *
     * @param loginUser 当前认证主体
     * @return 状态、驳回理由、掩码、提交/审核时间
     */
    @GetMapping
    public R<RealnameVO> get(@AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(realnameService.get(loginUser.getUserId()));
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
