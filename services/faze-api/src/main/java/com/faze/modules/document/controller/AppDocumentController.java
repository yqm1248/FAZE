/*
 * 职责：App 端文书的 HTTP 入口（段 4 C1 + D2/E4/F1，共 9 个接口：
 *       模板列表 / 保存草稿 / 我的文书 / 详情 / 软删 / 投递生成 / 预览 / 导出 / 叠章）。
 * 调用方：App 文书中心六页（E1~E6）。
 * 数据来源：AppDocumentService（草稿与列表）、DocDeliveryService（预览/导出/叠章）；
 *          当前用户从 SecurityContext 取，**不读请求体里的 userId**。
 * 注意：
 *   1. 所有 `/api/v1/app/**` 都由 SecurityConfig 保护；`@AuthenticationPrincipal` 为空说明
 *      过滤器链没生效（不是业务分支），所以这里只做兜底断言。
 *   2. 本类不注入 Mapper、不写业务、不开事务。
 *   3. `generate` 返回 `R<Void>` 而不是 R<documentId>：它是「已受理」语义，
 *      真正的进度由客户端轮询 `GET /app/documents/{id}` 的 status 得知。
 *   4. 列表是 C 端 `list` 分页（PageResult：list/total/pageNum/pageSize/hasMore），
 *      不是 PC 的 rows/total（接口规范 §5.2 分场景）。
 *   5. `preview` / `export` 返回的都是**60 秒现签地址**，客户端拿到就用，不要缓存。
 *   6. `export` 的 `format` 只支持 `pdf`（不出 Word，工作流 §零.3 第 12 项），
 *      传其它值抛 401001。
 */
package com.faze.modules.document.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.document.bo.DocumentSaveBO;
import com.faze.modules.document.bo.SignBO;
import com.faze.modules.document.service.AppDocumentService;
import com.faze.modules.document.service.DocDeliveryService;
import com.faze.modules.document.vo.DocExportVO;
import com.faze.modules.document.vo.DocPreviewPageVO;
import com.faze.modules.document.vo.DocTemplateBriefVO;
import com.faze.modules.document.vo.DocumentDetailVO;
import com.faze.modules.document.vo.DocumentRowVO;
import com.faze.modules.document.vo.SignResultVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 文书接口。
 */
@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
@Validated
public class AppDocumentController {

    private final AppDocumentService appDocumentService;
    private final DocDeliveryService docDeliveryService;

    /**
     * 可用的文书模板列表（只返回 active）。
     *
     * @param loginUser 当前用户
     * @return 模板列表（含字段定义）
     */
    @GetMapping("/doc-templates")
    public R<List<DocTemplateBriefVO>> templates(@AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(appDocumentService.listActiveTemplates());
    }

    /**
     * 保存草稿（新建或更新同一份）。
     *
     * @param bo        保存入参
     * @param loginUser 当前用户
     * @return 文书主键（字符串化）
     */
    @PostMapping("/documents")
    public R<Long> save(@Valid @RequestBody DocumentSaveBO bo,
                        @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(appDocumentService.save(bo, loginUser.getUserId()));
    }

    /**
     * 我的文书列表。
     *
     * @param status    状态筛选（draft/generated/signed/failed）
     * @param docType   类型筛选
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param loginUser 当前用户
     * @return C 端分页结果
     */
    @GetMapping("/documents")
    public R<PageResult<DocumentRowVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String docType,
            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) Integer pageSize,
            @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(appDocumentService.listMine(loginUser.getUserId(), status, docType,
                pageNum, pageSize));
    }

    /**
     * 文书详情。
     *
     * @param id        文书主键
     * @param loginUser 当前用户
     * @return 详情
     */
    @GetMapping("/documents/{id}")
    public R<DocumentDetailVO> detail(@PathVariable @Positive Long id,
                                      @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(appDocumentService.detail(id, loginUser.getUserId()));
    }

    /**
     * 软删文书。
     *
     * @param id        文书主键
     * @param loginUser 当前用户
     * @return 空响应
     */
    @DeleteMapping("/documents/{id}")
    public R<Void> delete(@PathVariable @Positive Long id,
                          @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        appDocumentService.softDelete(id, loginUser.getUserId());
        return R.ok();
    }

    /**
     * 投递生成任务（立即返回，异步生成）。
     *
     * @param id        文书主键
     * @param loginUser 当前用户
     * @return 空响应（进度靠轮询详情）
     */
    @PostMapping("/documents/{id}/generate")
    public R<Void> generate(@PathVariable @Positive Long id,
                            @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        appDocumentService.generate(id, loginUser.getUserId());
        return R.ok();
    }

    /**
     * 预览图列表（服务端把 PDF 每页渲染成 PNG，前 5 页，带缓存）。
     *
     * @param id        文书主键
     * @param loginUser 当前用户
     * @return 每页一张图（`imageUrl` 是 60 秒现签地址）；还没生成时返回空数组
     */
    @GetMapping("/documents/{id}/preview")
    public R<List<DocPreviewPageVO>> preview(@PathVariable @Positive Long id,
                                            @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(docDeliveryService.preview(id, loginUser.getUserId()));
    }

    /**
     * 导出 PDF 的下载地址（只支持 pdf）。
     *
     * @param id        文书主键
     * @param format    导出格式，只接受 `pdf`
     * @param loginUser 当前用户
     * @return 60 秒下载地址 + 建议文件名
     */
    @GetMapping("/documents/{id}/export")
    public R<DocExportVO> export(@PathVariable @Positive Long id,
                                 @RequestParam(defaultValue = "pdf") String format,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(docDeliveryService.export(id, loginUser.getUserId(), format));
    }

    /**
     * 提交签名坐标并叠章（产物落到 `signed_pdf_oss_key`，不改原 PDF）。
     *
     * @param id        文书主键
     * @param bo        页码 + 坐标 + 尺寸 + 基准页面尺寸
     * @param loginUser 当前用户
     * @return 叠签后地址 + 盖签前哈希
     */
    @PostMapping("/documents/{id}/sign")
    public R<SignResultVO> sign(@PathVariable @Positive Long id,
                                @Valid @RequestBody SignBO bo,
                                @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(docDeliveryService.sign(id, loginUser.getUserId(), bo));
    }

    /**
     * 未认证兜底。
     *
     * @param loginUser 当前认证主体
     * @throws BusinessException 402003
     */
    private void requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
    }
}
