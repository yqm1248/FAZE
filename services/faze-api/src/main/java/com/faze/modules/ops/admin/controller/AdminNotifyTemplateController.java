/*
 * 职责：A-24 通知模板接口（列表 / 新建 / 修改 / 软停用 / 测试渲染）。
 * 调用方：PC 后台「通知模板」页（src/views/biz/ops/NotifyTemplate.vue）。
 * 数据来源：AdminNotifyTemplateService。
 * 注意：
 *   1. 写操作在 Service 上标审计（NOTIFY_TEMPLATE_CREATE/UPDATE/DELETE）。
 *   2. `DELETE` 是**软停用**（status=disabled），不是物理删——历史通知要能追溯模板。
 *   3. 测试发送只返回渲染预览文本，不写 notification（管理员不在 app_user 里，见 Service 注释）。
 */
package com.faze.modules.ops.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.admin.bo.NotifyTemplateSaveBO;
import com.faze.modules.ops.admin.service.AdminNotifyTemplateService;
import com.faze.modules.ops.admin.vo.NotifyTemplateVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端通知模板接口。
 */
@RestController
@RequestMapping("/api/v1/admin/notify-templates")
@RequiredArgsConstructor
@Validated
public class AdminNotifyTemplateController {

    /** 模板服务。 */
    private final AdminNotifyTemplateService templateService;

    /**
     * 分页查询模板。
     *
     * @param channel  渠道
     * @param status   状态
     * @param kw       关键词
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<NotifyTemplateVO>> page(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kw,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return R.ok(templateService.page(channel, status, kw, pageNum, pageSize));
    }

    /**
     * 新建模板。
     *
     * @param bo 入参
     * @return 新模板 id
     */
    @PostMapping
    public R<Long> create(@Valid @RequestBody NotifyTemplateSaveBO bo) {
        return R.ok(templateService.create(bo));
    }

    /**
     * 修改模板。
     *
     * @param id 模板 id
     * @param bo 入参
     * @return 空
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody NotifyTemplateSaveBO bo) {
        templateService.update(id, bo);
        return R.ok(null);
    }

    /**
     * 软停用模板。
     *
     * @param id 模板 id
     * @return 空
     */
    @DeleteMapping("/{id}")
    public R<Void> disable(@PathVariable Long id) {
        templateService.disable(id);
        return R.ok(null);
    }

    /**
     * 测试渲染（返回预览文本，不写站内信）。
     *
     * @param id 模板 id
     * @return 预览文本
     */
    @PostMapping("/{id}/test")
    public R<String> test(@PathVariable Long id) {
        return R.ok(templateService.renderPreview(id));
    }
}
