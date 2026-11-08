/*
 * 职责：PC A-14 / A-15 文章管理的 HTTP 入口（G9.3 / G9.4）。
 * 调用方：`pc/admin-web/src/views/biz/content/article/index.vue` 与 `edit.vue`。
 * 数据来源：AdminArticleService。
 * 注意：
 *   1. **列表返回 RuoYi 口径**（顶层 rows / total）；详情/写接口返回 `R<T>`。
 *   2. 写接口（新建/保存/上架/下架）的 `@OperationLog` **标在 Service 方法上**，不标这里
 *      （Controller 只在转发，标这里会在参数校验失败时也记一条假审计）。
 *   3. 上架/下架用 POST 动作路径（`/publish`、`/offline`），符合接口规范 §3.3 的动作接口约定
 *      （非 CRUD 的状态变更不套 PUT）。
 *   4. 本类不注入 Mapper、不写业务（《后端代码规范》§2.1）。
 */
package com.faze.modules.content.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.content.admin.bo.AdminArticleQueryBO;
import com.faze.modules.content.admin.bo.AdminArticleSaveBO;
import com.faze.modules.content.admin.service.AdminArticleService;
import com.faze.modules.content.admin.vo.AdminArticleDetailVO;
import com.faze.modules.content.admin.vo.AdminArticleRowVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 文章管理接口。
 */
@RestController
@RequestMapping("/api/v1/admin/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    private final AdminArticleService adminArticleService;

    /**
     * 文章列表（A-14，含四语言完成度）。
     *
     * @param query 分类 / 状态 / 语言 / 关键词 / 分页
     * @return RuoYi 分页响应
     */
    @GetMapping
    public TableDataInfo<AdminArticleRowVO> list(AdminArticleQueryBO query) {
        return adminArticleService.list(query);
    }

    /**
     * 文章详情（A-15）。
     *
     * @param id 文章主键
     * @return 详情（源内容 + 已存在的译文）
     */
    @GetMapping("/{id}")
    public R<AdminArticleDetailVO> detail(@PathVariable Long id) {
        return R.ok(adminArticleService.detail(id));
    }

    /**
     * 新建文章（A-15，保存为草稿）。
     *
     * @param request 文章内容
     * @return 新文章主键
     */
    @PostMapping
    public R<Long> create(@Valid @RequestBody AdminArticleSaveBO request) {
        return R.ok(adminArticleService.create(request));
    }

    /**
     * 保存文章（A-15）。
     *
     * @param id      文章主键
     * @param request 文章内容
     * @return 空响应
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AdminArticleSaveBO request) {
        adminArticleService.update(id, request);
        return R.ok();
    }

    /**
     * 上架（A-15）：四语言不齐全会被拒。
     *
     * @param id 文章主键
     * @return 空响应
     */
    @PostMapping("/{id}/publish")
    public R<Void> publish(@PathVariable Long id) {
        adminArticleService.publish(id);
        return R.ok();
    }

    /**
     * 下架（A-14）。
     *
     * @param id 文章主键
     * @return 空响应
     */
    @PostMapping("/{id}/offline")
    public R<Void> offline(@PathVariable Long id) {
        adminArticleService.offline(id);
        return R.ok();
    }
}
