/*
 * 职责：上传凭证 HTTP 入口——签发直传地址与生成下载地址。
 * 调用方：App 实名认证页 / 企业认证页 / 律师入驻页 / 头像上传。
 * 数据来源：请求体 + SecurityContext 里的 LoginUser；业务在 UploadService。
 * 注意：
 *   1. 两个接口都需登录（`/api/v1/app/**` 由 SecurityConfig 保护）。
 *   2. 文件本身**不经过本服务**（云 OSS 是客户端直传）。当前本地实现（方案 B）
 *      的「直传端点」在 infra/pub/PubFileController，那是本地模拟的特例。
 *   3. 下载地址只对**文件属主**签发（见 UploadService 的归属校验）。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.storage.FileStorage;
import com.faze.modules.account.bo.UploadBO;
import com.faze.modules.account.service.UploadService;
import com.faze.modules.account.vo.UploadTokenVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上传接口。
 */
@RestController
@RequestMapping("/api/v1/app/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    /**
     * 申请上传凭证。
     *
     * @param request   上传申请
     * @param loginUser 当前认证主体
     * @return key + 上传地址 + 有效期
     */
    @PostMapping("/token")
    public R<UploadTokenVO> token(@Valid @RequestBody UploadBO request,
                                  @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(uploadService.presign(request, loginUser.getUserId()));
    }

    /**
     * 生成短时下载地址。
     *
     * @param key       对象 key
     * @param loginUser 当前认证主体
     * @return 下载地址与有效期
     */
    @GetMapping("/url")
    public R<FileStorage.PresignedUrl> url(@RequestParam("key") String key,
                                           @AuthenticationPrincipal LoginUser loginUser) {
        requireLogin(loginUser);
        return R.ok(uploadService.downloadUrl(key, loginUser.getUserId()));
    }

    /**
     * 未认证兜底。
     *
     * @param loginUser 当前认证主体
     * @throws BusinessException 402003 未认证
     */
    private void requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
    }
}
