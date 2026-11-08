/*
 * 职责：`GET /api/v1/app/ai/quota`——今日已用轮次 / 上限 / 重置时间（F5 在输入框上方显示「今日还剩 N 次」）。
 * 调用方：App AI 页（F5）。此接口**技术路线没有，是第 3 段新增**，已登记进《接口规范》§7.1.1。
 * 数据来源：SecurityContext 的 LoginUser → AiQuotaService.current(userId) → Redis + sys_config。
 * 注意：
 *   1. 路径与字段名（used / limit / resetAt）**已登记契约，不许改**。
 *   2. 未认证时 `@AuthenticationPrincipal` 为 null：兜底抛 402003（与 ProfileController 同一写法），
 *      不依赖「SecurityConfig 一定拦在前面」——授权规则被改时这里必须仍是 401 而不是 NPE。
 *   3. 这是**只读**接口：不 INCR、不创建 key。查询本身不能消耗配额。
 */
package com.faze.modules.ai.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ai.dto.QuotaVO;
import com.faze.modules.ai.service.AiQuotaService;
import com.faze.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 配额查询接口。
 */
@RestController
@RequestMapping("/api/v1/app/ai")
@RequiredArgsConstructor
public class AiQuotaController {

    private final AiQuotaService aiQuotaService;

    /**
     * 查询今日配额。
     *
     * @param loginUser 当前认证主体（未认证时为 null）
     * @return 已用 / 上限 / 重置时间
     * @throws BusinessException 402003 未认证
     */
    @GetMapping("/quota")
    public R<QuotaVO> quota(@AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return R.ok(aiQuotaService.current(loginUser.getUserId()));
    }
}
