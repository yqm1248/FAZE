/*
 * 职责：PC A-17 多语言翻译管理的 HTTP 入口（译文队列 + 术语表）。
 * 调用方：`pc/admin-web/src/views/biz/i18n/index.vue`。
 * 数据来源：AdminTranslationService。
 * 注意：
 *   1. **列表返回 RuoYi 口径**（顶层 rows / total）；详情/写接口返回 `R<T>`。
 *   2. 写接口的 `@OperationLog` 标在 Service 方法上，不标这里（Controller 只转发，
 *      标这里会在参数校验失败时也记一条假审计）。
 *   3. **不写 `@PreAuthorize`**：faze-api 没有 RuoYi 的 `@ss` bean，管理端只靠 ROLE_ADMIN
 *      （与 AdminArticleController / AdminLawItemController 同一约定）。
 *      更细的「仅超管可见」由 RuoYi 侧菜单权限 `biz:translation:edit` 控制，
 *      前端按钮用 `v-hasPermi`，本类不重复实现一套权限。
 *   4. 本类不注入 Mapper、不写业务、不开事务（《后端代码规范》§2.1）。
 */
package com.faze.modules.content.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.content.admin.bo.AdminGlossaryQueryBO;
import com.faze.modules.content.admin.bo.AdminGlossarySaveBO;
import com.faze.modules.content.admin.bo.AdminTranslationQueryBO;
import com.faze.modules.content.admin.bo.AdminTranslationSaveBO;
import com.faze.modules.content.admin.service.AdminTranslationService;
import com.faze.modules.content.admin.vo.AdminGlossaryRowVO;
import com.faze.modules.content.admin.vo.AdminTranslationDetailVO;
import com.faze.modules.content.admin.vo.AdminTranslationRowVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 多语言翻译管理接口。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminTranslationController {

    private final AdminTranslationService adminTranslationService;

    /**
     * 译文队列（A-17 左栏）。
     *
     * @param query 语言 / 状态 / 关键词 / 分页
     * @return RuoYi 分页响应
     */
    @GetMapping("/translations")
    public TableDataInfo<AdminTranslationRowVO> translations(AdminTranslationQueryBO query) {
        return adminTranslationService.listTranslations(query);
    }

    /**
     * 译文详情（含正文）。
     *
     * @param id 译文主键
     * @return 详情
     */
    @GetMapping("/translations/{id}")
    public R<AdminTranslationDetailVO> translationDetail(@PathVariable Long id) {
        return R.ok(adminTranslationService.detail(id));
    }

    /**
     * 保存译文（A-17 编辑）。
     *
     * @param id      译文主键
     * @param request 保存内容
     * @return 空响应
     */
    @PutMapping("/translations/{id}")
    public R<Void> updateTranslation(@PathVariable Long id,
                                     @Valid @RequestBody AdminTranslationSaveBO request) {
        adminTranslationService.updateTranslation(id, request);
        return R.ok();
    }

    /**
     * 术语表（A-17 右栏）。
     *
     * @param query 语言 / 关键词 / 只看待填 / 分页
     * @return RuoYi 分页响应
     */
    @GetMapping("/i18n-glossary")
    public TableDataInfo<AdminGlossaryRowVO> glossary(AdminGlossaryQueryBO query) {
        return adminTranslationService.listGlossary(query);
    }

    /**
     * 保存术语译文（A-17 右栏编辑）。
     *
     * @param id      术语主键
     * @param request 保存内容
     * @return 空响应
     */
    @PutMapping("/i18n-glossary/{id}")
    public R<Void> updateGlossary(@PathVariable Long id,
                                  @Valid @RequestBody AdminGlossarySaveBO request) {
        adminTranslationService.updateGlossary(id, request);
        return R.ok();
    }
}
