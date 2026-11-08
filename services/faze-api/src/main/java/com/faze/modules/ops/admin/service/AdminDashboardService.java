/*
 * 职责：A-02 数据看板的 4 组数据组装 + 口径落地。
 * 调用方：modules/ops 的 AdminDashboardController。
 * 数据来源：DashboardMapper（跨表只读聚合）+ Redis（10 分钟缓存）。
 * 注意：
 *   1. **口径只有一份**：所有时间窗按 **Asia/Shanghai 自然日**切，再换算成 UTC 边界传给 SQL。
 *      不在 SQL 里用 `CURDATE()`/`NOW()`——那会跟着服务器时区走，与「库内 UTC」打架。
 *   2. **环比昨日为 0 时返回 null**（前端显示 `—`）：禁止 Infinity / NaN
 *      （工作流 A5 算做完第 4 条）。
 *   3. **趋势缺日补 0**：否则折线会把「没数据的那天」直接连起来。
 *   4. 缓存：summary / trends 缓存 10 分钟（稿面「每 10 分钟刷新一次」），
 *      todos **不缓存**（要实时）。缓存读失败一律回退查库，不让缓存成为单点。
 *   5. 口径文档在 `docs/acceptance/seg7/A-02-指标口径.md`，每个方法注释里引用了对应节。
 */
package com.faze.modules.ops.admin.service;

import com.faze.modules.ops.admin.vo.DashboardRegionVO;
import com.faze.modules.ops.admin.vo.DashboardSummaryVO;
import com.faze.modules.ops.admin.vo.DashboardTodoVO;
import com.faze.modules.ops.admin.vo.DashboardTrendVO;
import com.faze.modules.ops.mapper.DashboardMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 看板服务。
 */
@Slf4j
@Service
public class AdminDashboardService {

    /** 业务时区（所有自然日口径都按它切）。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 缓存 TTL（10 分钟）。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** 缓存 key 前缀（业务库 db1，faze: 前缀）。 */
    private static final String CACHE_PREFIX = "faze:dashboard:";

    /** 趋势固定 14 天。 */
    private static final int TREND_DAYS = 14;

    /** 日期格式。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 聚合查询。 */
    private final DashboardMapper dashboardMapper;

    /** Redis（缓存）。 */
    private final StringRedisTemplate redis;

    /** JSON。 */
    private final JsonMapper jsonMapper;

    /**
     * 构造服务。
     *
     * @param dashboardMapper 聚合查询
     * @param redis           Redis
     * @param jsonMapper      JSON 序列化
     */
    public AdminDashboardService(DashboardMapper dashboardMapper, StringRedisTemplate redis, JsonMapper jsonMapper) {
        this.dashboardMapper = dashboardMapper;
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 核心指标（4 卡 + 环比）。
     *
     * <p>口径见《A-02 指标口径》§2~§5。副作用：写 Redis 缓存 10 分钟。</p>
     *
     * @param range today / 7d / 30d
     * @return 指标
     */
    public DashboardSummaryVO summary(String range) {
        String key = CACHE_PREFIX + "summary:" + range;
        DashboardSummaryVO cached = readCache(key, DashboardSummaryVO.class);
        if (cached != null) {
            return cached;
        }

        int days = windowDays(range);
        LocalDate today = LocalDate.now(SHANGHAI);
        LocalDate beginDate = today.minusDays(days - 1L);
        LocalDateTime begin = toUtc(beginDate);
        LocalDateTime end = toUtc(today.plusDays(1));
        // 对比期：同样长度、紧邻的上一段
        LocalDateTime prevBegin = toUtc(beginDate.minusDays(days));
        LocalDateTime prevEnd = begin;

        long active = dashboardMapper.countActiveUsers(begin, end);
        long prevActive = dashboardMapper.countActiveUsers(prevBegin, prevEnd);
        long newUsers = dashboardMapper.countNewUsers(begin, end);
        long prevNewUsers = dashboardMapper.countNewUsers(prevBegin, prevEnd);
        long orders = dashboardMapper.countOrders(begin, end);
        long prevOrders = dashboardMapper.countOrders(prevBegin, prevEnd);
        long revenue = dashboardMapper.sumPlatformFee(begin, end);
        long prevRevenue = dashboardMapper.sumPlatformFee(prevBegin, prevEnd);

        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setRange(range);
        vo.setDailyActive(active);
        vo.setDailyActiveDelta(delta(active, prevActive));
        vo.setNewUsers(newUsers);
        vo.setNewUsersDelta(delta(newUsers, prevNewUsers));
        vo.setOrderCount(orders);
        vo.setOrderCountDelta(delta(orders, prevOrders));
        vo.setRevenueCents(revenue);
        vo.setRevenueDelta(delta(revenue, prevRevenue));
        vo.setOnlineCount(null);
        vo.setDataAsOf(LocalDateTime.now(ZoneOffset.UTC));
        vo.setNote("收入=已支付订单的服务费合计（platform_fee_cents），不含充值；活跃=登录成功去重用户");
        writeCache(key, vo);
        return vo;
    }

    /**
     * 趋势（14 天按日）。
     *
     * <p>口径见《A-02 指标口径》§6。副作用：写 Redis 缓存 10 分钟。</p>
     *
     * @param metric orders / revenue
     * @return 趋势
     */
    public DashboardTrendVO trends(String metric) {
        String key = CACHE_PREFIX + "trends:" + metric;
        DashboardTrendVO cached = readCache(key, DashboardTrendVO.class);
        if (cached != null) {
            return cached;
        }

        LocalDate today = LocalDate.now(SHANGHAI);
        LocalDate first = today.minusDays(TREND_DAYS - 1L);
        LocalDateTime begin = toUtc(first);

        List<DashboardMapper.TrendRow> rows = "revenue".equals(metric)
                ? dashboardMapper.selectRevenueTrend(begin)
                : dashboardMapper.selectOrderTrend(begin);

        Map<String, Long> byDate = new HashMap<>(TREND_DAYS * 2);
        for (DashboardMapper.TrendRow row : rows) {
            if (row.getD() != null) {
                byDate.put(row.getD(), row.getC() == null ? 0L : row.getC());
            }
        }

        List<DashboardTrendVO.Point> points = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) {
            LocalDate d = first.plusDays(i);
            points.add(DashboardTrendVO.Point.of(d.format(DATE), byDate.getOrDefault(d.format(DATE), 0L)));
        }

        DashboardTrendVO vo = new DashboardTrendVO();
        vo.setMetric(metric);
        vo.setRange(TREND_DAYS + "d");
        vo.setPoints(points);
        vo.setNote("按 Asia/Shanghai 自然日聚合；缺日补 0；revenue 单位为分");
        writeCache(key, vo);
        return vo;
    }

    /**
     * 用户地区分布。
     *
     * <p>本段无数据源：`app_user` 没有 `region` 列（见 {@link DashboardRegionVO} 注释）。</p>
     *
     * @return 空列表 + 说明
     */
    public DashboardRegionVO regions() {
        DashboardRegionVO vo = new DashboardRegionVO();
        vo.setList(List.of());
        vo.setNote("当前无法统计：app_user 未建 region 列（段 1 未交付此字段），需段 1 补列并采集后再接");
        return vo;
    }

    /**
     * 待我处理（5 类计数）。
     *
     * <p>口径见《A-02 指标口径》§8。**不缓存**（实时看）。</p>
     *
     * @return 5 项
     */
    public List<DashboardTodoVO> todos() {
        long realname = dashboardMapper.countPendingRealname();
        long lawyerApp = dashboardMapper.countPendingLawyerApplication();
        long withdraw = dashboardMapper.countPendingWithdraw();
        long tickets = dashboardMapper.countPendingTickets();
        long overtime = dashboardMapper.countOvertimeTickets(LocalDateTime.now(ZoneOffset.UTC));
        long reviews = dashboardMapper.countPendingContentReviews();

        List<DashboardTodoVO> list = new ArrayList<>(5);
        list.add(DashboardTodoVO.of("realname", "实名认证待审核", realname, null,
                "/biz/verification?type=realname&status=PENDING"));
        list.add(DashboardTodoVO.of("lawyerApplication", "律师入驻待审核", lawyerApp, null,
                "/biz/lawyerApplication?status=PENDING"));
        list.add(DashboardTodoVO.of("withdraw", "提现申请待审核", withdraw, null,
                "/biz/trade/withdraw?status=applying"));
        list.add(DashboardTodoVO.of("ticket", "投诉工单待处理", tickets,
                overtime > 0 ? "其中 " + overtime + " 条已超时" : null,
                "/biz/ops/tickets?status=waiting"));
        list.add(DashboardTodoVO.of("contentReview", "内容审核待处理", reviews, null,
                "/biz/ops/review-queue?status=pending"));
        return list;
    }

    /**
     * range 参数对应的天数。
     *
     * @param range today / 7d / 30d
     * @return 天数（非法值按 today）
     */
    private int windowDays(String range) {
        if ("7d".equals(range)) {
            return 7;
        }
        if ("30d".equals(range)) {
            return 30;
        }
        return 1;
    }

    /**
     * 上海自然日 00:00 → UTC LocalDateTime。
     *
     * @param date 上海日期
     * @return UTC 时间
     */
    private LocalDateTime toUtc(LocalDate date) {
        return date.atStartOfDay(SHANGHAI).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 环比百分比，保留 1 位小数。
     *
     * @param current  本期
     * @param previous 上期
     * @return 百分比；上期为 0 返回 null（"—"）
     */
    private Double delta(long current, long previous) {
        if (previous == 0) {
            return null;
        }
        return Math.round((current - previous) * 1000.0 / previous) / 10.0;
    }

    /**
     * 读缓存；任何异常都当作未命中（缓存不能成为故障点）。
     *
     * @param key   缓存键
     * @param clazz 目标类型
     * @param <T>   类型
     * @return 缓存值；未命中或解析失败返回 null
     */
    private <T> T readCache(String key, Class<T> clazz) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : jsonMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("看板缓存读取失败，回退查库 key={}", key, e);
            return null;
        }
    }

    /**
     * 写缓存；失败只打 WARN（不影响响应）。
     *
     * @param key 缓存键
     * @param vo  值
     */
    private void writeCache(String key, Object vo) {
        try {
            redis.opsForValue().set(key, jsonMapper.writeValueAsString(vo), CACHE_TTL);
        } catch (Exception e) {
            log.warn("看板缓存写入失败 key={}", key, e);
        }
    }
}
