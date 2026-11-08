/*
 * 职责：A-20 内容审核队列接口（列表 / 详情 / 三种处置）。
 * 调用方：PC 后台「内容审核」页（src/views/biz/ops/ReviewQueue.vue）。
 * 数据来源：AdminContentReviewService → faze.content_review。
 * 注意：
 *   1. 处置**按 action 路由到三个 Service 方法**：每个方法各带一个审计动作码
 *      （CONTENT_REVIEW_IGNORE/WARN/BAN）。这里只有字符串到方法的映射，
 *      没有业务判断——业务在 Service 里。
 *   2. 详情返回的上下文是**降级态**（`contextAvailable=false`），不接 conversation 域。
 *   3. 路径在 `/api/v1/admin/**` 下由 SecurityConfig 保护。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.ContentReviewAuditBO;
import com.faze.modules.ops.admin.bo.ContentReviewQueryBO;
import com.faze.modules.ops.admin.service.AdminContentReviewService;
import com.faze.modules.ops.admin.vo.ContentReviewDetailVO;
import com.faze.modules.ops.admin.vo.ContentReviewVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端内容审核接口。
 */
@RestController
@RequestMapping("/api/v1/admin/content-reviews")
@RequiredArgsConstructor
@Validated
public class AdminContentReviewController {

    /** 内容审核服务。 */
    private final AdminContentReviewService contentReviewService;

    /**
     * 分页查询命中记录。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<ContentReviewVO>> page(ContentReviewQueryBO query) {
        return R.ok(contentReviewService.page(query));
    }

    /**
     * 命中记录详情（含降级说明）。
     *
     * @param id 命中记录 id
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<ContentReviewDetailVO> detail(@PathVariable Long id) {
        return R.ok(contentReviewService.detail(id));
    }

    /**
     * 处置命中记录（ignore / warn / ban）。
     *
     * @param id 命中记录 id
     * @param bo 处置入参
     * @return 空
     */
    @PostMapping("/{id}/audit")
    public R<Void> audit(@PathVariable Long id, @Valid @RequestBody ContentReviewAuditBO bo) {
        switch (bo.getAction()) {
            case "ignore" -> contentReviewService.auditIgnore(id, bo);
            case "warn" -> contentReviewService.auditWarn(id, bo);
            default -> contentReviewService.auditBan(id, bo);
        }
        return R.ok(null);
    }
}
