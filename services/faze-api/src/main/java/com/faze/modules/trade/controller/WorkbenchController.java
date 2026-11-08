/*
 * 职责：律师工作台 HTTP 入口（G3.4）——看板、在线状态、服务设置。
 * 调用方：APP「律师工作台」（62-workbench.html）。
 * 数据来源：LawyerWorkbenchService（内部按 token 的 userId 反查 lawyer.id）。
 * 注意：
 *   1. 三个接口都需要律师身份；**非律师账号调用返回 403（401008）**，
 *      不是 404——「你不是律师」是权限问题，不是资源不存在。
 *   2. 看板里的收入字段是**预计收入**（订单记账口径），页面文案必须写清「结算见后续版本」。
 *   3. 本类不注入 Mapper、不写业务、不开事务。
 */
package com.faze.modules.trade.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.bo.OnlineStatusBO;
import com.faze.modules.trade.bo.ServiceSettingsBO;
import com.faze.modules.trade.service.LawyerWorkbenchService;
import com.faze.modules.trade.vo.WorkbenchSummaryVO;
import com.faze.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 律师工作台接口。
 */
@RestController
@RequestMapping("/api/v1/app/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final LawyerWorkbenchService lawyerWorkbenchService;

    /**
     * 工作台看板。
     *
     * @param loginUser 当前认证主体
     * @return 看板数据（今日订单/预计收入/待接单/服务中/评分/在线状态）
     */
    @GetMapping("/summary")
    public R<WorkbenchSummaryVO> summary(@AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(lawyerWorkbenchService.summary(requireLogin(loginUser).getUserId()));
    }

    /**
     * 修改在线状态。
     *
     * @param request   目标状态
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PutMapping("/online-status")
    public R<Void> updateOnlineStatus(@RequestBody OnlineStatusBO request,
                                      @AuthenticationPrincipal LoginUser loginUser) {
        lawyerWorkbenchService.updateOnlineStatus(requireLogin(loginUser).getUserId(),
                request.getOnlineStatus());
        return R.ok();
    }

    /**
     * 修改服务设置（价格与上架开关）。
     *
     * @param request   服务设置
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PutMapping("/service-settings")
    public R<Void> updateServiceSettings(@RequestBody ServiceSettingsBO request,
                                         @AuthenticationPrincipal LoginUser loginUser) {
        lawyerWorkbenchService.updateServiceSettings(requireLogin(loginUser).getUserId(), request);
        return R.ok();
    }

    /**
     * 断言已登录。
     *
     * @param loginUser 认证主体
     * @return 非空认证主体
     * @throws BusinessException 402003 凭证无效
     */
    private LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return loginUser;
    }
}
