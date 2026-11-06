/*
 * 职责：PC 端退款审批 HTTP 入口（G4.2）——审批通过与驳回、按订单查退款单。
 * 调用方：PC 后台「订单详情」页（11-order-detail.html，页面在 G9.2 交付）的退款审批弹窗。
 * 数据来源：RefundAuditExecutor（写，含 @OperationLog 审计）/ RefundService（读）。
 * 注意：
 *   1. 路径在 `/api/v1/admin/**` 下，由管理端鉴权过滤器保护（段 1 F1 的桥接），
 *      与 C 端 token 完全隔离；本类不写任何鉴权逻辑。
 *   2. 本类不注入 Mapper、不写业务、不开事务（《后端代码规范》§2.1）。
 *   3. 通过 / 驳回走同一个 URL、用 `approved` 分流：这是《接口规范》对审批类接口的统一形态
 *      （实名、入驻审核同形），前端一个弹窗对应一个请求。
 *   4. 纯查询接口不标 `@OperationLog`（审计只针对写操作，《审计与日志规范》§3.2）。
 */
package com.faze.modules.trade.admin.controller;

import com.faze.common.R;
import com.faze.modules.trade.admin.bo.AdminRefundAuditBO;
import com.faze.modules.trade.admin.service.RefundAuditExecutor;
import com.faze.modules.trade.admin.vo.AdminRefundAuditResultVO;
import com.faze.modules.trade.service.RefundService;
import com.faze.modules.trade.vo.RefundVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端退款审批接口。
 */
@RestController
@RequestMapping("/api/v1/admin/refunds")
@RequiredArgsConstructor
@Validated
public class AdminRefundController {

    private final RefundAuditExecutor refundAuditExecutor;
    private final RefundService refundService;

    /**
     * 审批退款（通过 / 驳回）。
     *
     * @param id      退款单主键
     * @param request 是否通过 + 审批意见（驳回时必填）
     * @return 审批结果（退款单状态 + 订单状态）
     */
    @PostMapping("/{id}/audit")
    public R<AdminRefundAuditResultVO> audit(@PathVariable Long id,
                                             @Valid @RequestBody AdminRefundAuditBO request) {
        return R.ok(Boolean.TRUE.equals(request.getApproved())
                ? refundAuditExecutor.approve(id, request.getRemark())
                : refundAuditExecutor.reject(id, request.getRemark()));
    }

    /**
     * 按订单查退款单列表（PC 订单详情页用）。
     *
     * @param orderId 订单主键
     * @return 退款单列表（按申请时间倒序）
     */
    @GetMapping
    public R<List<RefundVO>> listByOrder(@RequestParam Long orderId) {
        return R.ok(refundService.listByOrder(orderId));
    }
}
