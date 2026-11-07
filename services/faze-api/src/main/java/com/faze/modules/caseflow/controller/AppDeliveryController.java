/*
 * 职责：送达的 HTTP 入口——列表、详情/回执、签收、选择送达方式。
 * 调用方：App 端（`22-service` 文书送达页，段 5 C2/C3/C4）。
 * 数据来源：@AuthenticationPrincipal 取当前用户；业务全在 DeliveryService。
 * 注意：
 *   1. 当前用户只从 SecurityContext 取；送达记录归属由 Service 的 SQL 条件保证。
 *   2. 签收设备与 IP **不在请求体里传**：`X-Device-Id` / `X-Client-Platform` 是接口规范 §4.1
 *      已有的框架头，`RequestUtils` 统一取 IP——客户端伪造请求体的「设备信息」没有证据价值。
 *   3. 路径在 `/api/v1/app/**` 下，未登录由 SecurityConfig 返回 401。
 */
package com.faze.modules.caseflow.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.common.util.RequestUtils;
import com.faze.modules.caseflow.bo.DeliveryMethodBO;
import com.faze.modules.caseflow.service.DeliveryService;
import com.faze.modules.caseflow.vo.DeliveryRowVO;
import com.faze.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * 送达接口。
 */
@RestController
@RequestMapping("/api/v1/app/case-deliveries")
public class AppDeliveryController {

    /** 签收设备字段的最大长度（与 case_delivery.sign_device 的 VARCHAR(100) 对齐）。 */
    private static final int SIGN_DEVICE_MAX = 100;

    private final DeliveryService deliveryService;

    /**
     * 构造控制器。
     *
     * @param deliveryService 送达业务
     */
    public AppDeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * 我的送达列表（可按案件与状态筛选）。
     *
     * @param loginUser 当前登录用户
     * @param caseId    案件 id（可空）
     * @param status    pending / signed（可空，空表示全部）
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 分页列表
     */
    @GetMapping
    public R<PageResult<DeliveryRowVO>> list(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) Long caseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return R.ok(deliveryService.listApp(
                requireLogin(loginUser).getUserId(), caseId, status, pageNum, pageSize));
    }

    /**
     * 送达详情 / 回执（C3：签收后展示结构化回执）。
     *
     * @param loginUser 当前登录用户
     * @param id        送达记录 id
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<DeliveryRowVO> detail(@AuthenticationPrincipal LoginUser loginUser,
                                   @PathVariable @Positive Long id) {
        return R.ok(deliveryService.detailApp(requireLogin(loginUser).getUserId(), id));
    }

    /**
     * 签收（CAS，幂等）。
     *
     * @param loginUser 当前登录用户
     * @param id        送达记录 id
     * @param request   当前请求（取设备与 IP）
     * @return 空响应
     */
    @PostMapping("/{id}/confirm")
    public R<Void> confirm(@AuthenticationPrincipal LoginUser loginUser,
                           @PathVariable @Positive Long id,
                           HttpServletRequest request) {
        deliveryService.confirm(requireLogin(loginUser).getUserId(), id,
                signDeviceOf(request), RequestUtils.clientIp(request));
        return R.ok();
    }

    /**
     * 选择 / 修改送达方式（只有未签收时能改）。
     *
     * @param loginUser 当前登录用户
     * @param id        送达记录 id
     * @param bo        方式入参
     * @return 空响应
     */
    @PutMapping("/{id}/method")
    public R<Void> chooseMethod(@AuthenticationPrincipal LoginUser loginUser,
                                @PathVariable @Positive Long id,
                                @Valid @RequestBody DeliveryMethodBO bo) {
        deliveryService.chooseMethod(requireLogin(loginUser).getUserId(), id, bo.getMethod());
        return R.ok();
    }

    /**
     * 组装签收设备描述（平台 + 设备标识）。
     *
     * <p>为什么用框架头而不是请求体：见类注释第 2 条。取不到时给 `unknown`，
     * 保证 `sign_device` 非空（C3 的「设备信息非空」验收条件）。</p>
     *
     * @param request 当前请求
     * @return 不超过 100 字符的设备描述
     */
    private static String signDeviceOf(HttpServletRequest request) {
        String platform = request.getHeader("X-Client-Platform");
        String deviceId = RequestUtils.deviceId(request);
        String platformPart = platform == null || platform.isBlank() ? "unknown" : platform.trim();
        String devicePart = deviceId == null ? "unknown" : deviceId;
        String device = platformPart + " " + devicePart;
        return device.length() > SIGN_DEVICE_MAX ? device.substring(0, SIGN_DEVICE_MAX) : device;
    }

    /**
     * 断言已登录。
     *
     * @param loginUser Security 注入的当前用户
     * @return 非空的当前用户
     * @throws BusinessException 未登录时抛 402003
     */
    private static LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null || loginUser.getUserId() == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "未登录访问送达接口");
        }
        return loginUser;
    }
}
