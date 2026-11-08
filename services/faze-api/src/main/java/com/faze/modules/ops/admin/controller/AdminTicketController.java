/*
 * 职责：A-21 工单列表 + A-22 工单详情接口（列表 / 详情 / 分配 / 批量分配 / 回复 / 关闭 / 导出）。
 * 调用方：PC 后台「工单管理」两页（src/views/biz/ops/TicketList.vue、TicketDetail.vue）。
 * 数据来源：AdminTicketService。
 * 注意：
 *   1. 写操作全部在 Service 上标 `@OperationLog`（TICKET_ASSIGN / TICKET_REPLY / TICKET_CLOSE）。
 *   2. 当前管理员 id 从审计上下文取（AdminAuthenticationFilter 已写入），不信任任何请求参数。
 *   3. 导出返回 `ResponseEntity<byte[]>`（文件下载例外，不是 `R<T>`）。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.audit.AuditContext;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.TicketCloseBO;
import com.faze.modules.ops.admin.bo.TicketQueryBO;
import com.faze.modules.ops.admin.bo.TicketReplyBO;
import com.faze.modules.ops.admin.service.AdminTicketService;
import com.faze.modules.ops.admin.vo.TicketDetailVO;
import com.faze.modules.ops.admin.vo.TicketVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端工单接口。
 */
@RestController
@RequestMapping("/api/v1/admin/tickets")
@RequiredArgsConstructor
@Validated
public class AdminTicketController {

    /** 工单服务。 */
    private final AdminTicketService adminTicketService;

    /**
     * 分页查询工单。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<TicketVO>> page(TicketQueryBO query) {
        return R.ok(adminTicketService.page(query));
    }

    /**
     * 工单详情。
     *
     * @param id 工单 id
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<TicketDetailVO> detail(@PathVariable Long id) {
        return R.ok(adminTicketService.detail(id));
    }

    /**
     * 发送回复。
     *
     * @param id 工单 id
     * @param bo 入参
     * @return 空
     */
    @PostMapping("/{id}/reply")
    public R<Void> reply(@PathVariable Long id, @Valid @RequestBody TicketReplyBO bo) {
        adminTicketService.reply(id, bo, currentAdminId());
        return R.ok(null);
    }

    /**
     * 关闭工单。
     *
     * @param id 工单 id
     * @param bo 入参
     * @return 空
     */
    @PostMapping("/{id}/close")
    public R<Void> close(@PathVariable Long id, @Valid @RequestBody TicketCloseBO bo) {
        adminTicketService.close(id, bo, currentAdminId());
        return R.ok(null);
    }

    /**
     * 分配工单给当前管理员。
     *
     * @param id 工单 id
     * @return 空
     */
    @PostMapping("/{id}/assign")
    public R<Void> assign(@PathVariable Long id) {
        adminTicketService.assign(id, currentAdminId());
        return R.ok(null);
    }

    /**
     * 批量分配。
     *
     * @param ids 工单 id 列表
     * @return 成功条数
     */
    @PostMapping("/assign-batch")
    public R<Integer> batchAssign(@RequestBody List<Long> ids) {
        return R.ok(adminTicketService.batchAssign(ids, currentAdminId()));
    }

    /**
     * 导出工单 CSV。
     *
     * @param query 查询条件（分页忽略）
     * @return CSV 文件流
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(TicketQueryBO query) {
        return ExportResponses.of(adminTicketService.export(query));
    }

    /**
     * 取当前管理员 id。
     *
     * @return 管理员 id；取不到返回 null（审计里 operator_id 会落 null，由切面兜底跳过）
     */
    private Long currentAdminId() {
        AuditContext.AuditInfo info = AuditContext.current();
        return info == null ? null : info.getOperatorId();
    }
}
