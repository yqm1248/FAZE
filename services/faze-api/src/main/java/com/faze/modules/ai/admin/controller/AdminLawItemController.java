/*
 * 职责：PC A-27 法条库的 HTTP 入口（H1，5 个接口：列表 / 详情 / 修正 / 批量导入 / 标记失效）。
 * 调用方：`pc/admin-web/src/views/biz/ai/law-library/index.vue` 与 `import-dialog.vue`
 *         （前端经 vite `/biz-api` 代理打到 faze-api 8081）。
 * 数据来源：LawItemAdminService（读写）、B4 LawRefStatsService（引用次数）。
 * 注意：
 *   1. **列表返回 RuoYi 口径**（顶层 rows / total，`TableDataInfo`），详情/写接口返回 `R<T>`
 *      —— 与 modules/account/admin、modules/content/admin 的既有写法一致（接口规范 §5.2 分场景）。
 *   2. 写接口（修正/导入/标记失效）的 `@OperationLog` **标在 Service 方法上**，不标这里：
 *      Controller 只是转发，标这里会在参数校验失败时也记一条「已执行」的假审计。
 *   3. **不做「新增单条」与「导出法条」**：工作流 H1 第 1 条已裁定不实现（设计稿那两个按钮也不做）。
 *   4. 导入支持两种入参（工作流 H1 第 3 条）：`application/json` 数组 body，或
 *      `multipart/form-data` 的 JSON 文件（字段名 `file`，≤5MB）。两条路径最后都进同一个 service。
 *   5. 本类不注入 Mapper、不写业务、不开事务（《后端代码规范》§2.1）；也**不写 `@PreAuthorize`**
 *      —— faze-api 没有 RuoYi 的 `@ss` bean，管理端只靠 SecurityConfig 的 `hasRole('ADMIN')`
 *      + AdminAuthenticationFilter（接口规范 §6.2：只校验角色，不做数据级权限细分）。
 *   6. 路径变量 `{id}` 加 `@Positive`，配合类上的 `@Validated` 生效。
 */
package com.faze.modules.ai.admin.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.ai.admin.bo.AdminLawItemAmendBO;
import com.faze.modules.ai.admin.bo.AdminLawItemImportItemBO;
import com.faze.modules.ai.admin.bo.AdminLawItemQueryBO;
import com.faze.modules.ai.admin.service.LawItemAdminService;
import com.faze.modules.ai.admin.vo.AdminLawItemDetailVO;
import com.faze.modules.ai.admin.vo.AdminLawItemImportResultVO;
import com.faze.modules.ai.admin.vo.AdminLawItemRowVO;
import com.faze.security.AdminLoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * PC 法条库管理接口（A-27）。
 */
@RestController
@RequestMapping("/api/v1/admin/law-items")
@RequiredArgsConstructor
@Validated
public class AdminLawItemController {

    private final LawItemAdminService lawItemAdminService;

    /**
     * 法条列表（分页 + 筛选 + AI 引用次数）。
     *
     * @param query 查询条件（lawCode / articleNo / kw / isEnabled / pageNum / pageSize）
     * @return RuoYi 分页响应（rows 为列表行，total 为总条数）
     */
    @GetMapping
    public TableDataInfo<AdminLawItemRowVO> list(AdminLawItemQueryBO query) {
        return lawItemAdminService.list(query);
    }

    /**
     * 法条详情（含条文全文；已修正/已失效的旧行也能查）。
     *
     * @param id 法条主键
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<AdminLawItemDetailVO> detail(@PathVariable @Positive Long id) {
        return R.ok(lawItemAdminService.detail(id));
    }

    /**
     * 修正法条（写新版本：旧行 amended+停用，新行 active+启用，同一事务）。
     *
     * @param id        被修正的法条主键
     * @param request   修正内容（content / effectiveDate 必填）
     * @param adminUser 当前管理端账号（写进新行 created_by）
     * @return 新版本行的 id
     */
    @PutMapping("/{id}")
    public R<Long> amend(@PathVariable @Positive Long id,
                         @Valid @RequestBody AdminLawItemAmendBO request,
                         @AuthenticationPrincipal AdminLoginUser adminUser) {
        return R.ok(lawItemAdminService.amend(id, request, operatorId(adminUser)));
    }

    /**
     * 批量导入（JSON 数组 body）。
     *
     * <p><b>5MB 上限只在传输层拦</b>（2026-11-07 lead 实测缺陷修复）：`application.yml` 的
     * `spring.servlet.multipart.*` 只作用于 multipart，**管不到普通 JSON body**——实测 8.6MB 的
     * JSON 数组会被照单接收并写库。工作流 H1 第 3 条的 5MB 意图覆盖两种入参方式，所以这里读
     * `Content-Length` 预检，超限直接回 401001 + 可读文案（不进入反序列化，避免先吃掉几十 MB 内存）。
     * 拿不到 Content-Length（chunked 上传）时无法预检，按正常流程处理（axios/浏览器都会带该头）。</p>
     *
     * @param items     导入条目数组
     * @param adminUser 当前管理端账号
     * @param request   当前请求（取 Content-Length 做大小预检）
     * @return `{total, inserted, skipped, failed:[{index,reason}]}`；超 5MB 时 401001 + 可读 msg
     */
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public R<AdminLawItemImportResultVO> importJson(@RequestBody List<AdminLawItemImportItemBO> items,
                                                    @AuthenticationPrincipal AdminLoginUser adminUser,
                                                    HttpServletRequest request) {
        long contentLength = request.getContentLengthLong();
        if (contentLength > LawItemAdminService.MAX_IMPORT_BYTES) {
            // 这里直接回 R.fail(code, detail) 而不是抛 BusinessException：
            // 按《后端代码规范》§5.3，BusinessException 的 detail 只进日志、不进响应体
            // （GlobalExceptionHandler 只回 ErrorCode 的固定 msg），抛异常的话客户端只会看到
            // 「参数校验失败」，看不出是「太大」。R.fail(e, detail) 本身就是为「参数校验字段级提示」
            // 提供的出口（同 GlobalExceptionHandler 的 Bean Validation 分支用法），不新增错误码。
            return R.fail(ErrorCode.COMMON_PARAM_INVALID, importTooLargeMessage(contentLength));
        }
        return R.ok(lawItemAdminService.importItems(items, operatorId(adminUser)));
    }

    /**
     * 批量导入（multipart 上传 JSON 文件，字段名 `file`，≤5MB）。
     *
     * @param file      上传的 JSON 文件
     * @param adminUser 当前管理端账号
     * @return `{total, inserted, skipped, failed:[{index,reason}]}`
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<AdminLawItemImportResultVO> importFile(@RequestParam("file") MultipartFile file,
                                                    @AuthenticationPrincipal AdminLoginUser adminUser) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "file: 导入文件不能为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            // 读文件失败属客户端上传问题，回稳定文案；异常原文只进日志（此处不打，避免大对象）
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "导入文件读取失败，请重新上传");
        }
        return R.ok(lawItemAdminService.importFile(bytes, operatorId(adminUser)));
    }

    /**
     * 标记失效（`is_enabled=0`，不做物理删除；重复调用幂等成功）。
     *
     * <p>审计的操作人由 AuditContext（AdminAuthenticationFilter 写入）承担，方法本身不需要操作人参数。</p>
     *
     * @param id 法条主键
     * @return 空响应
     */
    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable @Positive Long id) {
        lawItemAdminService.disable(id);
        return R.ok();
    }

    /**
     * 取当前管理端账号 id（审计切面已从 AuditContext 取操作人，这里只为写 created_by）。
     *
     * @param adminUser 认证主体（理论上不会为 null：`/admin/**` 由 SecurityConfig 强制 ADMIN 角色）
     * @return 管理员 id；主体缺失时返回 null（created_by 允许 NULL，不因此拒绝请求）
     */
    private Long operatorId(AdminLoginUser adminUser) {
        return adminUser == null ? null : adminUser.adminId();
    }

    /**
     * 组装「导入内容过大」的可读提示（给运营看的，不含内部细节）。
     *
     * @param contentLength 请求体字节数（来自 Content-Length）
     * @return 形如「导入内容超过 5MB（当前 8.6 MB），请分批导入（单次最多 500 条）」
     */
    private static String importTooLargeMessage(long contentLength) {
        long limitMb = LawItemAdminService.MAX_IMPORT_BYTES / 1024 / 1024;
        String currentMb = String.format(java.util.Locale.ROOT, "%.1f", contentLength / 1024.0 / 1024.0);
        return "导入内容超过 " + limitMb + "MB（当前 " + currentMb + " MB），请分批导入（单次最多 500 条）";
    }
}
