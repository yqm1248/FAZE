/*
 * 职责：PC A-18 文书模板管理的 HTTP 入口（段 4 B1，5 个接口）。
 * 调用方：`pc/admin-web/src/views/biz/document/template/index.vue` 与 `edit.vue`
 *         （前端经 vite `/biz-api` 代理打到 faze-api 8081）。
 * 数据来源：DocTemplateAdminService。
 * 注意：
 *   1. **列表返回 RuoYi 口径**（顶层 rows / total，`TableDataInfo`），其余返回 `R<T>`——
 *      与 modules/ai/admin、modules/account/admin 的既有写法一致（接口规范 §5.2 分场景）。
 *   2. 写接口（新建/保存）的 `@OperationLog` **标在 Service 方法上**，不标这里：
 *      Controller 只是转发，标这里会在参数校验失败时也记一条「已执行」的假审计。
 *   3. **preview 不标 @OperationLog**：它的语义是「看一眼」，虽然会顺带把预览图缓存下来，
 *      但不是业务写操作。标了会让每次点预览都落一条审计，把真正的写操作淹掉。
 *   4. 路径变量加 `@Positive`，配合类上的 `@Validated` 生效。
 *   5. 本类不注入 Mapper、不写业务、不开事务；也不写 `@PreAuthorize`——faze-api 没有
 *      RuoYi 的 `@ss` bean，管理端只靠 SecurityConfig 的 `hasRole('ADMIN')`（接口规范 §6.2）。
 */
package com.faze.modules.document.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.document.admin.bo.DocTemplateQueryBO;
import com.faze.modules.document.admin.bo.DocTemplateSaveBO;
import com.faze.modules.document.admin.service.DocTemplateAdminService;
import com.faze.modules.document.admin.vo.DocTemplateDetailVO;
import com.faze.modules.document.admin.vo.DocTemplatePreviewVO;
import com.faze.modules.document.admin.vo.DocTemplateRowVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 文书模板管理接口（A-18）。
 */
@RestController
@RequestMapping("/api/v1/admin/doc-templates")
@RequiredArgsConstructor
@Validated
public class AdminDocTemplateController {

    private final DocTemplateAdminService docTemplateAdminService;

    /**
     * 模板列表（分页 + 分类/状态/语言/关键字筛选）。
     *
     * @param query 查询条件
     * @return RuoYi 分页响应
     */
    @GetMapping
    public TableDataInfo<DocTemplateRowVO> list(DocTemplateQueryBO query) {
        return docTemplateAdminService.list(query);
    }

    /**
     * 模板详情（含 template_html 与 field_schema，供编辑器回填）。
     *
     * @param id 模板主键
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<DocTemplateDetailVO> detail(@PathVariable @Positive Long id) {
        return R.ok(docTemplateAdminService.detail(id));
    }

    /**
     * 新建模板（version=1，默认 draft）。
     *
     * @param bo 保存入参
     * @return 新模板主键（字符串化）
     */
    @PostMapping
    public R<Long> create(@Valid @RequestBody DocTemplateSaveBO bo) {
        return R.ok(docTemplateAdminService.create(bo));
    }

    /**
     * 保存模板 → 生成新版本行（旧行置 inactive）。
     *
     * @param id 模板主键
     * @param bo 保存入参
     * @return 新版本行主键（字符串化）
     */
    @PutMapping("/{id}")
    public R<Long> update(@PathVariable @Positive Long id,
                          @Valid @RequestBody DocTemplateSaveBO bo) {
        return R.ok(docTemplateAdminService.update(id, bo));
    }

    /**
     * 渲染预览图（真 PDF 转 PNG，返回 60 秒短时地址）。
     *
     * @param id 模板主键
     * @return 预览地址与有效期
     */
    @PostMapping("/{id}/preview")
    public R<DocTemplatePreviewVO> preview(@PathVariable @Positive Long id) {
        return R.ok(docTemplateAdminService.preview(id));
    }
}
