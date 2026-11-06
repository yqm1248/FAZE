/*
 * 职责：C 端律师浏览 HTTP 入口（G3.1 列表 / G3.2 详情、评价、关注）。
 * 调用方：APP「找律师」页（02-lawyer.html）、「律师详情」页（14-lawyer-detail.html）。
 * 数据来源：LawyerBrowseService、LawyerFollowService。
 * 注意：
 *   1. **列表 / 详情 / 评价是公开接口**：找律师对游客开放，SecurityConfig 已放行这三个 GET。
 *      因此登录主体可能为 null，**不能对它直接解引用**。
 *   2. **关注 / 取关需要登录**（走 `/api/v1/app/**` 的 authenticated 规则），
 *      未登录调用会返回 401，这里只在为空时抛 401003 兜底（防御式，正常不会走到）。
 *   3. 本类不注入 Mapper、不写业务、不开事务（后端规范 §2.1）。
 */
package com.faze.modules.account.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.bo.LawyerListQueryBO;
import com.faze.modules.account.service.LawyerBrowseService;
import com.faze.modules.account.service.LawyerFollowService;
import com.faze.modules.account.vo.LawyerCardVO;
import com.faze.modules.account.vo.LawyerDetailVO;
import com.faze.modules.account.vo.LawyerReviewVO;
import com.faze.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 律师浏览接口。
 */
@RestController
@RequestMapping("/api/v1/app/lawyers")
@RequiredArgsConstructor
public class LawyerBrowseController {

    private final LawyerBrowseService lawyerBrowseService;
    private final LawyerFollowService lawyerFollowService;

    /**
     * 律师列表（分类 / 搜索 / 分页）。
     *
     * @param query 分页、分类与关键词
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<LawyerCardVO>> list(LawyerListQueryBO query) {
        return R.ok(lawyerBrowseService.list(query));
    }

    /**
     * 律师详情。
     *
     * @param id        律师主键
     * @param loginUser 当前认证主体（游客为 null）
     * @return 律师详情（含是否已关注）
     */
    @GetMapping("/{id}")
    public R<LawyerDetailVO> detail(@PathVariable Long id,
                                    @AuthenticationPrincipal LoginUser loginUser) {
        Long viewerId = loginUser == null ? null : loginUser.getUserId();
        return R.ok(lawyerBrowseService.detail(id, viewerId));
    }

    /**
     * 律师评价列表。
     *
     * @param id       律师主键
     * @param pageNum  页码（默认 1）
     * @param pageSize 每页条数（默认 20，服务端 clamp 到 1~100）
     * @return 评价分页结果
     */
    @GetMapping("/{id}/reviews")
    public R<PageResult<LawyerReviewVO>> reviews(@PathVariable Long id,
                                                 @RequestParam(required = false) Integer pageNum,
                                                 @RequestParam(required = false) Integer pageSize) {
        return R.ok(lawyerBrowseService.reviews(id, pageNum, pageSize));
    }

    /**
     * 关注律师。
     *
     * @param id        律师主键
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @PostMapping("/{id}/follow")
    public R<Void> follow(@PathVariable Long id,
                          @AuthenticationPrincipal LoginUser loginUser) {
        lawyerFollowService.follow(requireLogin(loginUser).getUserId(), id);
        return R.ok();
    }

    /**
     * 取消关注。
     *
     * @param id        律师主键
     * @param loginUser 当前认证主体
     * @return 空响应
     */
    @DeleteMapping("/{id}/follow")
    public R<Void> unfollow(@PathVariable Long id,
                            @AuthenticationPrincipal LoginUser loginUser) {
        lawyerFollowService.unfollow(requireLogin(loginUser).getUserId(), id);
        return R.ok();
    }

    /**
     * 断言已登录（关注类接口用；浏览类接口允许游客）。
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
