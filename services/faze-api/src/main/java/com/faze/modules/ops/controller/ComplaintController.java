/*
 * 职责：APP 投诉接口（提交 / 我的投诉列表）。
 * 调用方：APP 57 投诉页、（投诉列表回看）。
 * 数据来源：ComplaintService。
 * 注意：
 *   1. 当前用户从 `@AuthenticationPrincipal LoginUser` 取，不从请求体取 userId。
 *   2. 提交成功返回**含工单号**的投诉行：用户要在页面上看到「已提交，单号 TK...」。
 *   3. 列表只返回本人的（归属条件进 SQL）。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.bo.ComplaintSubmitBO;
import com.faze.modules.ops.service.ComplaintService;
import com.faze.modules.ops.vo.ComplaintVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端投诉接口。
 */
@RestController
@RequestMapping("/api/v1/app/complaints")
@RequiredArgsConstructor
@Validated
public class ComplaintController {

    /** 投诉服务。 */
    private final ComplaintService complaintService;

    /**
     * 提交投诉。
     *
     * @param loginUser 当前用户
     * @param bo        入参
     * @return 投诉行（含工单号）
     */
    @PostMapping
    public R<ComplaintVO> submit(@AuthenticationPrincipal LoginUser loginUser,
                                 @Valid @RequestBody ComplaintSubmitBO bo) {
        return R.ok(complaintService.submit(requireLogin(loginUser).getUserId(), bo));
    }

    /**
     * 我的投诉列表。
     *
     * @param loginUser 当前用户
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<ComplaintVO>> listMine(@AuthenticationPrincipal LoginUser loginUser,
                                               @RequestParam(required = false) Integer pageNum,
                                               @RequestParam(required = false) Integer pageSize) {
        return R.ok(complaintService.listMine(requireLogin(loginUser).getUserId(), pageNum, pageSize));
    }

    /**
     * 断言已登录。
     *
     * @param loginUser 当前用户
     * @return 登录用户
     */
    private LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return loginUser;
    }
}
