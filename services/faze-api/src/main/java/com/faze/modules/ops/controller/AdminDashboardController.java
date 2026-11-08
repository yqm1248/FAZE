/*
 * 职责：管理端自检接口——返回服务端时间与「faze-api 认出来的管理员是谁」。
 * 调用方：PC 后台「业务总览」页（src/views/biz/dashboard/index.vue）。
 * 数据来源：SecurityContext 里的 AdminLoginUser（由 AdminAuthenticationFilter 从 RuoYi 登录态还原）。
 * 注意：
 *   1. 本接口**没有业务价值**，它的唯一用途是把「管理端鉴权桥」这条链路的每一环
 *      变成页面上一行可读的字：能拿到 adminId，就说明 RuoYi token → 验签 → Redis 登录态
 *      → ROLE_ADMIN 全部通了。链路断在哪一环，页面会显示对应错误。
 *   2. 它是**纯查询**，按审计规范 §3.2 不标 `@OperationLog`（只有写操作才标）。
 *   3. 不返回 token、不返回原始登录态：自检不等于把凭证摊开。
 */
package com.faze.modules.ops.controller;

import com.faze.common.R;
import com.faze.modules.ops.admin.service.AdminDashboardService;
import com.faze.modules.ops.admin.vo.DashboardRegionVO;
import com.faze.modules.ops.admin.vo.DashboardSummaryVO;
import com.faze.modules.ops.admin.vo.DashboardTodoVO;
import com.faze.modules.ops.admin.vo.DashboardTrendVO;
import com.faze.security.AdminLoginUser;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端自检接口 + A-02 看板接口。
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    /** 看板服务。 */
    private final AdminDashboardService dashboardService;

    /** 项目时区：所有面向人的时间按 Asia/Shanghai 展示（数据仍存 UTC）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** ISO-8601 带偏移，与接口规范 §5.3 的时间格式一致。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * 管理端连通性自检。
     *
     * @return `{serverTime, adminId, adminName}`
     */
    @GetMapping("/ping")
    public R<PingVO> ping() {
        AdminLoginUser admin = currentAdmin();
        PingVO vo = new PingVO();
        vo.setServerTime(OffsetDateTime.now(ZONE).format(TIME_FORMAT));
        vo.setAdminId(admin == null ? null : admin.adminId());
        vo.setAdminName(admin == null ? null : admin.username());
        return R.ok(vo);
    }

    /**
     * A-02 核心指标（4 卡 + 环比）。
     *
     * <p>口径见 `docs/acceptance/seg7/A-02-指标口径.md` §2~§5。</p>
     *
     * @param range today / 7d / 30d，默认 today
     * @return 指标
     */
    @GetMapping("/summary")
    public R<DashboardSummaryVO> summary(@RequestParam(required = false, defaultValue = "today") String range) {
        return R.ok(dashboardService.summary(range));
    }

    /**
     * A-02 近 14 天趋势（按日聚合、缺日补 0）。
     *
     * @param metric orders / revenue，默认 orders
     * @return 趋势点位
     */
    @GetMapping("/trends")
    public R<DashboardTrendVO> trends(@RequestParam(required = false, defaultValue = "orders") String metric) {
        return R.ok(dashboardService.trends(metric));
    }

    /**
     * A-02 用户地区分布。
     *
     * <p>本段无数据源（app_user 无 region 列），返回空列表 + 说明。</p>
     *
     * @return 地区分布
     */
    @GetMapping("/regions")
    public R<DashboardRegionVO> regions() {
        return R.ok(dashboardService.regions());
    }

    /**
     * A-02 待我处理（5 类计数，实时）。
     *
     * @return 5 项
     */
    @GetMapping("/todos")
    public R<List<DashboardTodoVO>> todos() {
        return R.ok(dashboardService.todos());
    }

    /**
     * 取当前管理员。
     *
     * @return 管理员；未认证（理论上不会走到，路径已被 hasRole 保护）时返回 null
     */
    private AdminLoginUser currentAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AdminLoginUser admin)) {
            return null;
        }
        return admin;
    }

    /**
     * 自检出参。
     */
    public static class PingVO {

        /** 服务端时间（ISO-8601，Asia/Shanghai 偏移）。 */
        private String serverTime;

        /** 当前管理员主键（faze_admin.sys_user.id），序列化为字符串。 */
        private Long adminId;

        /** 当前管理员登录名。 */
        private String adminName;

        public String getServerTime() {
            return serverTime;
        }

        public void setServerTime(String serverTime) {
            this.serverTime = serverTime;
        }

        public Long getAdminId() {
            return adminId;
        }

        public void setAdminId(Long adminId) {
            this.adminId = adminId;
        }

        public String getAdminName() {
            return adminName;
        }

        public void setAdminName(String adminName) {
            this.adminName = adminName;
        }
    }
}
