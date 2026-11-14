/*
 * 职责：C 端文章收藏的写入口（收藏 / 取消收藏）。
 * 调用方：APP 文章详情页（设计稿 `13-article.html`）底部操作栏的收藏按钮。
 * 数据来源：SocialActionService（复用段 2 的 `social_action` 表，
 *          `action_type=favorite`、`target_type=article`），不新建收藏表。
 * 注意：
 *   1. **为什么不写进 ArticleController**：那个类同时挂 `/pub` 与 `/app` 两个前缀
 *      （游客首页也要能看文章），而收藏是**必须登录的写操作**，挂到 `/pub` 上
 *      等于对游客开放写接口。本类只挂 `/api/v1/app/articles`。
 *   2. 两个方向都幂等（重复收藏 / 重复取消都返回成功），与案例收藏一致。
 *   3. 本类不注入 Mapper、不写业务、不开事务（《后端代码规范》§2.1）。
 */
package com.faze.modules.content.controller;

import com.faze.common.R;
import com.faze.modules.trade.service.SocialActionService;
import com.faze.security.LoginUser;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章收藏接口（C 端）。
 */
@RestController
@RequestMapping("/api/v1/app/articles")
@RequiredArgsConstructor
public class AppArticleFavoriteController {

    private final SocialActionService socialActionService;

    /**
     * 收藏文章（幂等）。
     *
     * @param loginUser 当前登录用户
     * @param id        文章主键
     * @return 空响应
     */
    @PostMapping("/{id}/favorite")
    public R<Void> favorite(@AuthenticationPrincipal LoginUser loginUser,
                            @PathVariable @Positive Long id) {
        socialActionService.favorite(loginUser.getUserId(),
                SocialActionService.TARGET_ARTICLE, id);
        return R.ok();
    }

    /**
     * 取消收藏（幂等）。
     *
     * @param loginUser 当前登录用户
     * @param id        文章主键
     * @return 空响应
     */
    @DeleteMapping("/{id}/favorite")
    public R<Void> unfavorite(@AuthenticationPrincipal LoginUser loginUser,
                              @PathVariable @Positive Long id) {
        socialActionService.unfavorite(loginUser.getUserId(),
                SocialActionService.TARGET_ARTICLE, id);
        return R.ok();
    }
}
