/*
 * 职责：评价的律师侧入口——回复评价（G5.1，`POST /app/reviews/{id}/reply`）。
 * 调用方：APP 律师侧（段 2 只实现接口，律师端回复界面在段 6 的律师主页/消息中心一并做）；
 *         PC 后台 A-12 只读查看聊天与评价原文，不提供回复入口。
 * 数据来源：ReviewService（内部按 token 的 userId 反查 lawyer.id 做归属校验）。
 * 注意：
 *   1. **这个接口是段 2 新增的**：《技术路线》§4.8.2 的接口清单没有它，但 `review.lawyer_reply`
 *      字段（V200）与设计稿 34-review.html 的「律师可以回复你的评价」都已存在；
 *      按工作流 G5.1 第 4 条落地并登记到《接口规范》附录 A。
 *   2. **律师身份来自 token**：不接收 lawyerId 参数（接收即越权）。
 *      校验在 Service（`requireReviewOwner`），Controller 只做登录断言。
 *   3. 提交评价本身在 `OrderController#review`（URL 是订单子资源 `/app/orders/{id}/review`），
 *      不放在本类：`/app/reviews` 下只有「对已存在评价的操作」。
 */
package com.faze.modules.trade.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.bo.ReviewReplyBO;
import com.faze.modules.trade.service.ReviewService;
import com.faze.modules.trade.vo.ReviewVO;
import com.faze.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评价（律师侧）接口。
 */
@RestController
@RequestMapping("/api/v1/app/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * 律师回复评价。
     *
     * @param id        评价主键
     * @param request   回复正文
     * @param loginUser 当前认证主体（必须是被评价律师本人）
     * @return 回复后的评价
     */
    @PostMapping("/{id}/reply")
    public R<ReviewVO> reply(@PathVariable Long id,
                             @Valid @RequestBody ReviewReplyBO request,
                             @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(reviewService.reply(requireLogin(loginUser).getUserId(), id, request));
    }

    /**
     * 断言已登录。
     *
     * @param loginUser 认证主体
     * @return 非空认证主体
     * @throws BusinessException 402003 凭证无效
     */
    private LoginUser requireLogin(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return loginUser;
    }
}
