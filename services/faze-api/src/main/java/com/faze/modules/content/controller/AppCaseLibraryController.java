/*
 * 职责：App 案例库的 HTTP 入口（04-case 列表 / 19-case-detail 详情）。
 * 调用方：apps/mobile-app 的案例页（`features/content/pages/CaseListPage.tsx`、`CaseDetailPage.tsx`）。
 * 数据来源：CaseLibraryQueryService。
 * 注意：
 *   1. 路径在 `/api/v1/app/**` 下，由 SecurityConfig 要求登录用户（案例是公开内容，
 *      但 App 全局都要求登录，这里跟随项目口径，不额外放行）。
 *   2. **只返回已上线案例**（Service 层保证）；已下架详情返回 409002，页面走空态。
 *   3. `lang` 从查询参数传：App 的 i18n 语言与应用语言一致，前端直接带上即可
 *      （比解析 `Accept-Language` 更可控——RN 的 Accept-Language 在 Web/原生不一致）。
 */
package com.faze.modules.content.controller;

import com.faze.common.R;
import com.faze.modules.content.service.CaseLibraryQueryService;
import com.faze.modules.content.vo.CaseDetailVO;
import com.faze.modules.content.vo.CaseListPageVO;
import com.faze.modules.trade.service.SocialActionService;
import com.faze.security.LoginUser;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 案例库接口。
 */
@RestController
@RequestMapping("/api/v1/app/case-library")
public class AppCaseLibraryController {

    private final CaseLibraryQueryService caseLibraryQueryService;
    private final SocialActionService socialActionService;

    /**
     * 构造控制器。
     *
     * @param caseLibraryQueryService 案例只读查询
     * @param socialActionService     收藏写服务（trade 域的通用互动行为）
     */
    public AppCaseLibraryController(CaseLibraryQueryService caseLibraryQueryService,
                                    SocialActionService socialActionService) {
        this.caseLibraryQueryService = caseLibraryQueryService;
        this.socialActionService = socialActionService;
    }

    /**
     * 按大类分页取案例（游标分页）。
     *
     * @param type   大类 code；空表示全部
     * @param cursor 游标（上一页最后一条 id）
     * @param limit  每页条数（默认 10）
     * @return 分页结果
     */
    @GetMapping
    public R<CaseListPageVO> list(@RequestParam(required = false) String type,
                                  @RequestParam(required = false) Long cursor,
                                  @RequestParam(required = false, defaultValue = "10") int limit) {
        return R.ok(caseLibraryQueryService.listByType(type, cursor, limit));
    }

    /**
     * 案例详情（按语言取正文，缺译回退中文）。
     *
     * @param loginUser 当前登录用户（回填收藏状态）
     * @param id        案例主键
     * @param lang      语言（默认 zh-Hans）
     * @return 详情
     */
    @GetMapping("/{id}")
    public R<CaseDetailVO> detail(@AuthenticationPrincipal LoginUser loginUser,
                                  @PathVariable @Positive Long id,
                                  @RequestParam(required = false) String lang) {
        Long userId = loginUser == null ? null : loginUser.getUserId();
        return R.ok(caseLibraryQueryService.detail(id, lang, userId));
    }

    /**
     * 收藏案例（幂等）。
     *
     * @param loginUser 当前登录用户
     * @param id        案例主键
     * @return 空响应
     */
    @PostMapping("/{id}/favorite")
    public R<Void> favorite(@AuthenticationPrincipal LoginUser loginUser,
                            @PathVariable @Positive Long id) {
        socialActionService.favorite(loginUser.getUserId(),
                SocialActionService.TARGET_CASE_LIBRARY, id);
        return R.ok();
    }

    /**
     * 取消收藏（幂等）。
     *
     * @param loginUser 当前登录用户
     * @param id        案例主键
     * @return 空响应
     */
    @DeleteMapping("/{id}/favorite")
    public R<Void> unfavorite(@AuthenticationPrincipal LoginUser loginUser,
                              @PathVariable @Positive Long id) {
        socialActionService.unfavorite(loginUser.getUserId(),
                SocialActionService.TARGET_CASE_LIBRARY, id);
        return R.ok();
    }
}
