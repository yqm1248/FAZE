/*
 * 职责：E3「我的收藏 / 关注律师」的 4 个 C 端接口入口（App JWT 鉴权）。
 * 调用方：apps/mobile-app 的 51-favorites / 52-following 页。
 * 数据来源：SecurityContext 的 LoginUser（用户 id）；业务在 trade 域的 SocialQueryService。
 * 注意：
 *   1. **Controller 只做转发 + 取当前用户**：不注入 Mapper、不写业务、不开事务
 *      （《后端代码规范》§2.1 分层禁令）。
 *   2. 越权归属校验在 Service：DELETE 收藏时「别人的收藏」返回 403（401008），
 *      不是静默成功——静默成功让客户端以为删掉了，实际没删。
 *   3. `DELETE /follows` 是**工作流 E3 的 4 个接口之外的补登**：设计稿 52 关注页有「取消关注」
 *      按钮，而 E3 的表里只有收藏的 DELETE；与 T2 前端约定后补（工作流 §五 5.2 的接口清单
 *      也把「取消关注」算在复用范围内，不是新增页面能力）。
 *   4. 响应里 `targetId` / `lawyerId` 是 Long，JacksonConfig 统一序列化为字符串。
 */
package com.faze.modules.trade.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.service.SocialQueryService;
import com.faze.modules.trade.vo.SocialFavoritesVO;
import com.faze.modules.trade.vo.SocialFollowCountVO;
import com.faze.modules.trade.vo.SocialFollowsVO;
import com.faze.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏 / 关注接口。
 */
@RestController
@RequestMapping("/api/v1/app/social")
@RequiredArgsConstructor
public class AppSocialController {

    /** 收藏 / 关注读服务。 */
    private final SocialQueryService socialQueryService;

    /**
     * 收藏列表（一次返回三类型计数 + 当前类型分页）。
     *
     * @param targetType 当前 Tab（article / lawyer / case；空按 article）
     * @param cursor     游标（上一页最后一条 id；空表示第一页）
     * @param lang       语言（文章/案例标题降级用；可空）
     * @param loginUser  当前用户
     * @return 计数 + 分页
     */
    @GetMapping("/favorites")
    public R<SocialFavoritesVO> favorites(@RequestParam(required = false) String targetType,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) String lang,
                                          @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(socialQueryService.favorites(requireUser(loginUser).getUserId(),
                targetType, cursor, lang));
    }

    /**
     * 取消收藏（软删；越权 403）。
     *
     * @param targetType 目标类型
     * @param targetId   目标主键
     * @param loginUser  当前用户
     * @return 空响应
     */
    @DeleteMapping("/favorites")
    public R<Void> unfavorite(@RequestParam String targetType,
                              @RequestParam Long targetId,
                              @AuthenticationPrincipal LoginUser loginUser) {
        socialQueryService.unfavorite(requireUser(loginUser).getUserId(), targetType, targetId);
        return R.ok();
    }

    /**
     * 关注律师列表。
     *
     * @param cursor    游标（空表示第一页）
     * @param loginUser 当前用户
     * @return 关注分页
     */
    @GetMapping("/follows")
    public R<SocialFollowsVO> follows(@RequestParam(required = false) String cursor,
                                      @AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(socialQueryService.follows(requireUser(loginUser).getUserId(), cursor));
    }

    /**
     * 取消关注（软删，幂等）。
     *
     * @param lawyerId  律师主键
     * @param loginUser 当前用户
     * @return 空响应
     */
    @DeleteMapping("/follows")
    public R<Void> unfollow(@RequestParam Long lawyerId,
                            @AuthenticationPrincipal LoginUser loginUser) {
        socialQueryService.unfollow(requireUser(loginUser).getUserId(), lawyerId);
        return R.ok();
    }

    /**
     * 关注数（个人页角标）。
     *
     * @param loginUser 当前用户
     * @return 关注数
     */
    @GetMapping("/follow-count")
    public R<SocialFollowCountVO> followCount(@AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(socialQueryService.followCount(requireUser(loginUser).getUserId()));
    }

    /**
     * 取当前用户；未认证时抛 402003（路径已被 Security 保护，理论到不了这里，兜底不返回 null）。
     *
     * @param loginUser 认证主体
     * @return 非空认证主体
     * @throws BusinessException 402003
     */
    private LoginUser requireUser(LoginUser loginUser) {
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return loginUser;
    }
}
