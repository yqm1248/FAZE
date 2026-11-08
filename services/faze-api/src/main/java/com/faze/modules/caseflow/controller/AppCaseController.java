/*
 * 职责：案件中心的 HTTP 入口——立案、我的案件、详情、进度时间轴、律师推进节点。
 * 调用方：App 端（53-my-cases / 54-case-progress / 20-filing）。
 * 数据来源：@AuthenticationPrincipal 取当前用户；业务全在 CaseService。
 * 注意：
 *   1. Controller 只做三件事：收参、调 Service、包 R（后端规范 §3.2）。
 *   2. `POST /cases` 必须带 `Idempotency-Key` 头；缺了由 Service 抛 401002。
 *   3. 路径在 `/api/v1/app/**` 下，未登录由 SecurityConfig 返回 401。
 */
package com.faze.modules.caseflow.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.caseflow.bo.CaseCreateBO;
import com.faze.modules.caseflow.bo.CaseProgressBO;
import com.faze.modules.caseflow.bo.CaseQueryBO;
import com.faze.modules.caseflow.service.CaseService;
import com.faze.modules.caseflow.vo.CaseDetailVO;
import com.faze.modules.caseflow.vo.CaseProgressVO;
import com.faze.modules.caseflow.vo.CaseRowVO;
import com.faze.modules.caseflow.vo.CaseSubmitVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 案件接口。
 */
@RestController
@RequestMapping("/api/v1/app/cases")
@Validated
public class AppCaseController {

    private final CaseService caseService;

    /**
     * 构造控制器。
     *
     * @param caseService 案件业务
     */
    public AppCaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    /**
     * 在线立案（四步表单一次提交）。
     *
     * @param loginUser      当前登录用户
     * @param idempotencyKey 幂等键（必填）
     * @param bo             立案入参
     * @return 提交结果
     */
    @PostMapping
    public R<CaseSubmitVO> create(@AuthenticationPrincipal LoginUser loginUser,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                  @Valid @RequestBody CaseCreateBO bo) {
        return R.ok(caseService.create(requireLogin(loginUser).getUserId(), bo, idempotencyKey));
    }

    /**
     * 我的案件列表。
     *
     * @param loginUser 当前登录用户
     * @param query     筛选与分页
     * @return 分页列表
     */
    @GetMapping
    public R<PageResult<CaseRowVO>> list(@AuthenticationPrincipal LoginUser loginUser,
                                         @Valid CaseQueryBO query) {
        return R.ok(caseService.list(requireLogin(loginUser).getUserId(), query));
    }

    /**
     * 案件详情。
     *
     * @param loginUser 当前登录用户
     * @param id        案件 id
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<CaseDetailVO> detail(@AuthenticationPrincipal LoginUser loginUser,
                                  @PathVariable @Positive Long id) {
        return R.ok(caseService.detail(requireLogin(loginUser).getUserId(), id));
    }

    /**
     * 案件进度时间轴。
     *
     * @param loginUser 当前登录用户
     * @param id        案件 id
     * @return 节点列表（按 step 升序）
     */
    @GetMapping("/{id}/progress")
    public R<List<CaseProgressVO>> progress(@AuthenticationPrincipal LoginUser loginUser,
                                            @PathVariable @Positive Long id) {
        return R.ok(caseService.listProgress(requireLogin(loginUser).getUserId(), id));
    }

    /**
     * 律师推进案件进度节点。
     *
     * @param loginUser 当前登录用户
     * @param id        案件 id
     * @param bo        进度入参
     * @return 新写入的进度节点
     */
    @PostMapping("/{id}/progress")
    public R<CaseProgressVO> addProgress(@AuthenticationPrincipal LoginUser loginUser,
                                         @PathVariable @Positive Long id,
                                         @Valid @RequestBody CaseProgressBO bo) {
        return R.ok(caseService.addProgress(requireLogin(loginUser).getUserId(), id, bo));
    }

    /**
     * 断言已登录。
     *
     * @param loginUser Security 注入的当前用户
     * @return 非空的当前用户
     * @throws BusinessException 未登录时抛 402003
     */
    private static LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null || loginUser.getUserId() == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "未登录访问案件接口");
        }
        return loginUser;
    }
}
