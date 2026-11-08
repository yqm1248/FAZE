/*
 * 职责：C 端站内信接口（列表 / 标记已读 / 未读数）。
 * 调用方：APP 消息中心（38/39 两页）+ 首页 tab 角标。
 * 数据来源：NotificationQueryService。
 * 注意：
 *   1. 路径 `/api/v1/app/**`（需登录），当前用户从登录态取。
 *   2. 游标分页：`cursor` 传上一页最后一条 id，服务端用 `id < cursor` 取下一页。
 *   3. 标记已读会顺带回写公告打开率（只有公告类通知）。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.service.NotificationQueryService;
import com.faze.modules.ops.vo.NotificationVO;
import com.faze.security.LoginUser;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端站内信接口。
 */
@RestController
@RequestMapping("/api/v1/app/notifications")
@RequiredArgsConstructor
public class NotificationController {

    /** 站内信读服务。 */
    private final NotificationQueryService notificationQueryService;

    /**
     * 我的通知列表（游标分页）。
     *
     * @param loginUser 当前用户
     * @param cursor    游标（上一页最后一条 id）
     * @param type      类型筛选
     * @param limit     每页条数
     * @return 列表
     */
    @GetMapping
    public R<List<NotificationVO>> list(@AuthenticationPrincipal LoginUser loginUser,
                                        @RequestParam(required = false) Long cursor,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(required = false) Integer limit) {
        return R.ok(notificationQueryService.list(requireLogin(loginUser).getUserId(), cursor, type, limit));
    }

    /**
     * 标记已读。
     *
     * @param loginUser 当前用户
     * @param id        通知 id
     * @return 空
     */
    @PostMapping("/{id}/read")
    public R<Void> markRead(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        notificationQueryService.markRead(requireLogin(loginUser).getUserId(), id);
        return R.ok(null);
    }

    /**
     * 未读数（角标）。
     *
     * @param loginUser 当前用户
     * @return 未读条数
     */
    @GetMapping("/unread-count")
    public R<Long> unreadCount(@AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(notificationQueryService.unreadCount(requireLogin(loginUser).getUserId()));
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
