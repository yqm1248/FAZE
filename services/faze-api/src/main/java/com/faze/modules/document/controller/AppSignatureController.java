/*
 * 职责：App 端签名库的 HTTP 入口（段 4 D1，3 个接口：列表 / 新增 / 删除）。
 * 调用方：App 电子签章管理页（E6，`/doc/seals`）。
 * 数据来源：SignatureService；当前用户从 SecurityContext 取，**不读请求体里的 userId**。
 * 注意：
 *   1. 所有 `/api/v1/app/**` 由 SecurityConfig 保护；`@AuthenticationPrincipal` 为空说明过滤器链
 *      没生效（不是业务分支），这里只做兜底断言。
 *   2. 本类不注入 Mapper、不写业务、不开事务。
 *   3. `POST` 只收对象键，**不收文件本体**：签名图先走段 1 的 `POST /app/upload/token`
 *      （`scene=signature`）直传。
 */
package com.faze.modules.document.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.document.bo.SignatureCreateBO;
import com.faze.modules.document.service.SignatureService;
import com.faze.modules.document.vo.SignatureVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * App 签名库接口。
 */
@RestController
@RequestMapping("/api/v1/app/signatures")
@RequiredArgsConstructor
@Validated
public class AppSignatureController {

    private final SignatureService signatureService;

    /**
     * 签名库列表（只含 `document_id IS NULL` 的签名图）。
     *
     * @param loginUser 当前用户
     * @return 签名列表
     */
    @GetMapping
    public R<List<SignatureVO>> list(@AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(signatureService.listLibrary(loginUser.getUserId()));
    }

    /**
     * 新增签名（同一个对象键重复提交是幂等的）。
     *
     * @param bo        入参（只有对象键）
     * @param loginUser 当前用户
     * @return 签名主键
     */
    @PostMapping
    public R<Long> create(@Valid @RequestBody SignatureCreateBO bo,
                          @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(signatureService.create(loginUser.getUserId(), bo.getImageOssKey()));
    }

    /**
     * 删除签名（已被文书引用的会被拒绝）。
     *
     * @param id        签名主键
     * @param loginUser 当前用户
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable @Positive Long id,
                          @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        signatureService.delete(loginUser.getUserId(), id);
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
