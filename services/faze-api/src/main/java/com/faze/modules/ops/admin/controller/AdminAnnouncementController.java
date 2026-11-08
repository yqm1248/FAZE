/*
 * 职责：A-23 公告接口（列表 / 新建 / 详情 / 预估触达 / 发送）。
 * 调用方：PC 后台「公告与推送」页（src/views/biz/ops/Announcement.vue）。
 * 数据来源：AdminAnnouncementService → faze.announcement。
 * 注意：
 *   1. 发送在 Service 上标 `ANNOUNCEMENT_PUSH` 审计；新建标 `ANNOUNCEMENT_CREATE`。
 *   2. `GET /estimate` 是**字面量路径**，与 `GET /{id}` 不冲突（Spring 具体路径优先）。
 *   3. 已发送的公告再点发送返回 410013（不可撤回，只能补发更正公告）。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.audit.AuditContext;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.AnnouncementSaveBO;
import com.faze.modules.ops.admin.service.AdminAnnouncementService;
import com.faze.modules.ops.admin.vo.AnnouncementDetailVO;
import com.faze.modules.ops.admin.vo.AnnouncementVO;
import jakarta.validation.Valid;
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
 * 管理端公告接口。
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
@RequiredArgsConstructor
@Validated
public class AdminAnnouncementController {

    /** 公告服务。 */
    private final AdminAnnouncementService announcementService;

    /**
     * 分页查询公告。
     *
     * @param status   状态
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<AnnouncementVO>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return R.ok(announcementService.page(status, pageNum, pageSize));
    }

    /**
     * 新建公告（草稿或定时）。
     *
     * @param bo 入参
     * @return 公告 id
     */
    @PostMapping
    public R<Long> create(@Valid @RequestBody AnnouncementSaveBO bo) {
        return R.ok(announcementService.create(bo, currentAdminId()));
    }

    /**
     * 预估触达人数。
     *
     * @param targetType  目标类型
     * @param targetValue 目标参数 JSON
     * @return 预估人数
     */
    @GetMapping("/estimate")
    public R<Long> estimate(@RequestParam String targetType,
                            @RequestParam(required = false) String targetValue) {
        return R.ok(announcementService.estimate(targetType, targetValue));
    }

    /**
     * 公告详情。
     *
     * @param id 公告 id
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<AnnouncementDetailVO> detail(@PathVariable Long id) {
        return R.ok(announcementService.detail(id));
    }

    /**
     * 发送公告（立即发送 / 重试补发）。
     *
     * @param id 公告 id
     * @return 空
     */
    @PostMapping("/{id}/push")
    public R<Void> push(@PathVariable Long id) {
        announcementService.push(id, currentAdminId());
        return R.ok(null);
    }

    /**
     * 当前管理员 id。
     *
     * @return 管理员 id
     */
    private Long currentAdminId() {
        AuditContext.AuditInfo info = AuditContext.current();
        return info == null ? null : info.getOperatorId();
    }
}
