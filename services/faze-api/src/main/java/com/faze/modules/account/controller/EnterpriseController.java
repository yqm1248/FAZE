/*
 * 职责：企业认证 HTTP 入口。
 * 调用方：企业认证页（页面归属段 7，本段只提供接口）。
 * 数据来源：请求体 + SecurityContext 里的 LoginUser；业务在 EnterpriseService。
 * 注意：
 *   1. 需登录；营业执照 key 由上传接口事先拿到。
 *   2. 本段**只做提交**，没有查询接口——查询属企业认证页（段 7），到那时再加。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.bo.EnterpriseBO;
import com.faze.modules.account.service.EnterpriseService;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业认证接口。
 */
@RestController
@RequestMapping("/api/v1/app/account")
@RequiredArgsConstructor
public class EnterpriseController {

    private final EnterpriseService enterpriseService;

    /**
     * 提交企业认证。
     *
     * @param request   提交请求
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping("/enterprise")
    public R<Void> submit(@Valid @RequestBody EnterpriseBO request,
                          @AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        enterpriseService.submit(loginUser.getUserId(), request);
        return R.ok();
    }
}
