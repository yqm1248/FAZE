/*
 * 职责：律师工作台的 HTTP 入口（文书列表 / 收入概览 / 收入明细 / 资料编辑 / 收款账户）。
 * 调用方：App 端（65-lawyer-docs / 66-income / 68-lawyer-profile / 67-service-settings）。
 * 数据来源：@AuthenticationPrincipal 取当前用户；业务全在 WorkbenchService。
 * 注意：
 *   1. Controller 只做三件事：收参、调 Service、包 R（后端规范 §3.2）。
 *   2. 当前用户**只从 SecurityContext 取**，不读请求体/查询参数里的 userId（§11.2）。
 *   3. 所有路径在 `/api/v1/app/**` 下，由 SecurityConfig 统一要求登录；未登录返回 401 而不是 404。
 */
package com.faze.modules.workbench.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.workbench.bo.WithdrawAccountBO;
import com.faze.modules.workbench.bo.WorkbenchProfileBO;
import com.faze.modules.workbench.service.WorkbenchService;
import com.faze.modules.workbench.vo.ConsultantRowVO;
import com.faze.modules.workbench.vo.IncomeFlowPageVO;
import com.faze.modules.workbench.vo.IncomeOverviewVO;
import com.faze.modules.workbench.vo.ServiceSettingsVO;
import com.faze.modules.workbench.vo.WithdrawAccountVO;
import com.faze.modules.workbench.vo.WorkbenchDocRowVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 律师工作台接口。
 */
@RestController
@RequestMapping("/api/v1/app/workbench")
public class AppWorkbenchController {

    private final WorkbenchService workbenchService;

    /**
     * 构造控制器。
     *
     * @param workbenchService 工作台业务
     */
    public AppWorkbenchController(WorkbenchService workbenchService) {
        this.workbenchService = workbenchService;
    }

    /**
     * 律师文书列表。
     *
     * @param loginUser 当前登录用户（由 Security 注入）
     * @param status    状态筛选，可空
     * @param pageNum   页码，默认 1
     * @param pageSize  每页条数，默认 20
     * @return 分页文书列表
     */
    @GetMapping("/documents")
    public R<PageResult<WorkbenchDocRowVO>> documents(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(workbenchService.listDocuments(requireLogin(loginUser).getUserId(),
                status, pageNum, pageSize));
    }

    /**
     * 客户咨询列表（D1：未读优先，其余按最近消息倒序）。
     *
     * <p><b>为什么不是 `/workbench/conversations`</b>：那个路径段 2 已被
     * `conversation.controller.ConversationController#listForLawyer` 占用，返回 `ConversationVO`
     * 且只按最近消息排序、不含订单标签。段 5 D1 需要「未读优先 + 订单已付款标签」的形状，
     * 换个路径比改段 2 的既有契约安全（同路径重复映射会让 Spring 启动失败）。已登记偏差。</p>
     *
     * @param loginUser 当前登录用户（由 Security 注入）
     * @param pageNum   页码，默认 1
     * @param pageSize  每页条数，默认 20
     * @return 分页会话列表
     */
    @GetMapping("/consultations")
    public R<PageResult<ConsultantRowVO>> conversations(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(workbenchService.listConsultations(requireLogin(loginUser).getUserId(),
                pageNum, pageSize));
    }

    /**
     * 把文书发送给委托人（D2）。
     *
     * @param loginUser  当前登录用户
     * @param documentId 文书 id
     * @return 空响应
     */
    @PostMapping("/documents/{documentId}/send")
    public R<Void> sendDocument(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable @Positive Long documentId) {
        workbenchService.sendDocumentToClient(requireLogin(loginUser).getUserId(), documentId);
        return R.ok();
    }

    /**
     * 查询服务设置（D4）。
     *
     * @param loginUser 当前登录用户
     * @return 服务设置
     */
    @GetMapping("/service-settings")
    public R<ServiceSettingsVO> serviceSettings(@AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(workbenchService.getServiceSettings(requireLogin(loginUser).getUserId()));
    }

    /**
     * 收入概览。
     *
     * @param loginUser 当前登录用户
     * @param range     区间：week / month / total，默认 month
     * @return 收入概览
     */
    @GetMapping("/income")
    public R<IncomeOverviewVO> income(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String range) {
        return R.ok(workbenchService.incomeOverview(requireLogin(loginUser).getUserId(), range));
    }

    /**
     * 收入明细（游标分页，含订单号）。
     *
     * @param loginUser 当前登录用户
     * @param range     区间：week / month / total
     * @param cursor    上一页最后一条 flowId，可空
     * @param limit     每页条数，默认 20
     * @return 游标分页明细
     */
    @GetMapping("/income/flows")
    public R<IncomeFlowPageVO> incomeFlows(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(workbenchService.incomeFlows(requireLogin(loginUser).getUserId(), range, cursor, limit));
    }

    /**
     * 查询律师资料（D5 页面回显，含只读的姓名与执业证号）。
     *
     * @param loginUser 当前登录用户
     * @return 资料
     */
    @GetMapping("/profile")
    public R<com.faze.modules.workbench.vo.WorkbenchProfileVO> profile(
            @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(workbenchService.getProfile(requireLogin(loginUser).getUserId()));
    }

    /**
     * 编辑律师资料（partial update）。
     *
     * @param loginUser 当前登录用户
     * @param bo        资料入参
     * @return 空响应
     */
    @PutMapping("/profile")
    public R<Void> updateProfile(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody WorkbenchProfileBO bo) {
        workbenchService.updateProfile(requireLogin(loginUser).getUserId(), bo);
        return R.ok();
    }

    /**
     * 查询收款账户（脱敏）。
     *
     * @param loginUser 当前登录用户
     * @return 收款账户
     */
    @GetMapping("/withdraw-account")
    public R<WithdrawAccountVO> withdrawAccount(@AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(workbenchService.getWithdrawAccount(requireLogin(loginUser).getUserId()));
    }

    /**
     * 绑定/更换收款账户。
     *
     * @param loginUser 当前登录用户
     * @param bo        账户入参
     * @return 空响应
     */
    @PutMapping("/withdraw-account")
    public R<Void> updateWithdrawAccount(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody WithdrawAccountBO bo) {
        workbenchService.updateWithdrawAccount(requireLogin(loginUser).getUserId(), bo);
        return R.ok();
    }

    /**
     * 断言已登录（SecurityConfig 已拦截，这里兜底，防匿名 principal 为空）。
     *
     * @param loginUser Security 注入的当前用户
     * @return 非空的当前用户
     * @throws BusinessException 未登录时抛 402003（凭证无效）
     */
    private static LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null || loginUser.getUserId() == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "未登录访问工作台接口");
        }
        return loginUser;
    }
}
