/*
 * 职责：APP 意见反馈接口（提交 / 我的反馈列表）。
 * 调用方：APP 58 反馈页。
 * 数据来源：FeedbackService。
 * 注意：
 *   1. 「具体情况」选填：空也能提交（稿面明写），服务端用类型标签兜底。
 *   2. 当前用户从登录态取，不从请求体取。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ops.bo.FeedbackSubmitBO;
import com.faze.modules.ops.service.FeedbackService;
import com.faze.modules.ops.vo.FeedbackVO;
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
 * C 端意见反馈接口。
 */
@RestController
@RequestMapping("/api/v1/app/feedback")
@RequiredArgsConstructor
@Validated
public class FeedbackController {

    /** 反馈服务。 */
    private final FeedbackService feedbackService;

    /**
     * 提交反馈。
     *
     * @param loginUser 当前用户
     * @param bo        入参
     * @return 反馈行（含工单号）
     */
    @PostMapping
    public R<FeedbackVO> submit(@AuthenticationPrincipal LoginUser loginUser,
                                @Valid @RequestBody FeedbackSubmitBO bo) {
        return R.ok(feedbackService.submit(requireLogin(loginUser).getUserId(), bo));
    }

    /**
     * 我的反馈列表。
     *
     * @param loginUser 当前用户
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<FeedbackVO>> listMine(@AuthenticationPrincipal LoginUser loginUser,
                                              @RequestParam(required = false) Integer pageNum,
                                              @RequestParam(required = false) Integer pageSize) {
        return R.ok(feedbackService.listMine(requireLogin(loginUser).getUserId(), pageNum, pageSize));
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
