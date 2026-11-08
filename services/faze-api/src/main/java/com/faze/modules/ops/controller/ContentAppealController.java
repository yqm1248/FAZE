/*
 * 职责：C 端「内容被拦截后的误判申诉」入口。
 * 调用方：APP 端——申诉入口挂在**内容被拦截时的错误提示**里（「如认为误判，点此申诉」），
 *         不新增独立页面（工作流 B2 卡住怎么办）。
 * 数据来源：ContentAppealService → faze.content_review / faze.sensitive_word_appeal。
 * 注意：
 *   1. 这是**用户端**接口，路径 `/api/v1/app/**`，由 SecurityConfig 的 `authenticated()` 保护。
 *   2. 当前用户从 `@AuthenticationPrincipal LoginUser` 取，**不从请求体取 userId**
 *      （后端规范 §11.2 第 2 条：禁止从请求体/查询参数取 userId）。
 *   3. 归属校验在 Service 里：不是自己的命中记录返回 401008，不是 404。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.bo.AppealSubmitBO;
import com.faze.modules.ops.service.ContentAppealService;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端内容申诉接口。
 */
@RestController
@RequestMapping("/api/v1/app/content-appeals")
@RequiredArgsConstructor
@Validated
public class ContentAppealController {

    /** 申诉提交服务。 */
    private final ContentAppealService contentAppealService;

    /**
     * 提交误判申诉。
     *
     * @param loginUser 当前登录用户
     * @param bo        入参
     * @return 申诉 id
     */
    @PostMapping
    public R<Long> submit(@AuthenticationPrincipal LoginUser loginUser,
                          @Valid @RequestBody AppealSubmitBO bo) {
        return R.ok(contentAppealService.submit(requireLogin(loginUser).getUserId(), bo));
    }

    /**
     * 断言已登录。
     *
     * @param loginUser 当前登录用户
     * @return 登录用户
     */
    private LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return loginUser;
    }
}
