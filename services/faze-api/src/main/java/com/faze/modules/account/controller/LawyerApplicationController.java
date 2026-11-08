/*
 * 职责：律师入驻 HTTP 入口——分步保存/提交、查询当前申请。
 * 调用方：App 律师入驻页（四步）与入驻审核状态页。
 * 数据来源：请求体 + SecurityContext 里的 LoginUser；业务在 LawyerApplicationService。
 * 注意：
 *   1. 两个接口都需登录。
 *   2. 提交接口同时承担「保存草稿」与「最终提交」：`submit` 字段决定，
 *      不拆两个 URL——拆了之后客户端要记住「我现在该调哪个」。
 *   3. 审核动作在 PC A-05（F 组），本段只做 App 侧提交与状态查询。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.bo.LawyerApplicationBO;
import com.faze.modules.account.service.LawyerApplicationService;
import com.faze.modules.account.vo.LawyerApplicationVO;
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
 * 律师入驻接口。
 */
@RestController
@RequestMapping("/api/v1/app/lawyer-applications")
@RequiredArgsConstructor
public class LawyerApplicationController {

    private final LawyerApplicationService lawyerApplicationService;

    /**
     * 保存草稿或最终提交。
     *
     * @param request   申请内容
     * @param loginUser 当前认证主体
     * @return 保存后的当前申请
     */
    @PostMapping
    public R<LawyerApplicationVO> submit(@Valid @RequestBody LawyerApplicationBO request,
                                         @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(lawyerApplicationService.submit(loginUser.getUserId(), request));
    }

    /**
     * 查询当前申请。
     *
     * @param loginUser 当前认证主体
     * @return 当前申请（从未提交时 status=NONE）
     */
    @GetMapping("/current")
    public R<LawyerApplicationVO> current(@AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(lawyerApplicationService.current(loginUser.getUserId()));
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
