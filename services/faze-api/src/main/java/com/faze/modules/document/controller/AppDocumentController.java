/*
 * 职责：App 端文书的 HTTP 入口（段 4 C1，6 个接口：模板列表 / 保存草稿 / 我的文书 / 详情 / 软删 / 投递生成）。
 * 调用方：App 文书中心六页（E1~E6）。
 * 数据来源：AppDocumentService；当前用户从 SecurityContext 取，**不读请求体里的 userId**。
 * 注意：
 *   1. 所有 `/api/v1/app/**` 都由 SecurityConfig 保护；`@AuthenticationPrincipal` 为空说明
 *      过滤器链没生效（不是业务分支），所以这里只做兜底断言。
 *   2. 本类不注入 Mapper、不写业务、不开事务。
 *   3. `generate` 返回 `R<Void>` 而不是 R<documentId>：它是「已受理」语义，
 *      真正的进度由客户端轮询 `GET /app/documents/{id}` 的 status 得知。
 *   4. 列表是 C 端 `list` 分页（PageResult：list/total/pageNum/pageSize/hasMore），
 *      不是 PC 的 rows/total（接口规范 §5.2 分场景）。
 */
package com.faze.modules.document.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.document.bo.DocumentSaveBO;
import com.faze.modules.document.service.AppDocumentService;
import com.faze.modules.document.vo.DocTemplateBriefVO;
import com.faze.modules.document.vo.DocumentDetailVO;
import com.faze.modules.document.vo.DocumentRowVO;
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
