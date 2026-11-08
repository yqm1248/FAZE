/*
 * 职责：A-02 数据看板 4 组接口的聚合 SQL（跨表只读统计）。
 * 调用方：modules/ops 的 AdminDashboardService。
 * 数据来源：app_user / orders / login_log / realname_auth / lawyer_application /
 *           withdraw_record / service_ticket / content_review（全部只读）。
 * 注意：
 *   1. **为什么在 ops 域直接写跨表 SQL**：这是「管理端聚合看板」，统计口径跨 5 个域。
 *      如果每个数字都走一遍各域的 QueryService，会得到 6 个接口 + 6 次 N+1 往返，
 *      而口径反而更难保证一致（§零.4 第 5 条：口径只能有一份）。
 *      这些查询**不写任何业务表**，是纯只读聚合，不构成「A 域改 B 域的表」。
 *   2. **参数化边界**：时间范围一律由 Service 算好 UTC 边界传入（`begin`/`end`），
 *      SQL 里不做时区函数——MySQL 的 `CURDATE()` 跟着服务器时区走，会与「库内 UTC」口径打架。
 *   3. **表名以实际库为准**：工作流 A5 写的 `legal_order` 实际不存在，真实表是 `orders`；
 *      `app_user.region` 也不存在（段 1 没建这一列），地区分布接口因此返回空并挂账。
 *   4. 这里用注解式 SQL 而不是 XML：全是**无动态条件的单条聚合**，没有 if/foreach，
 *      放 XML 只会把一条 SQL 拆成两个文件。多表 join 的场景仍按规范用 XML
 *      （见 `mapper/LawItemMapper.xml`）。这是对 §3.4 的一处有意偏离，已记 §十一。
 */
package com.faze.modules.ops.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 看板聚合查询。
 */
@Mapper
public interface DashboardMapper {

    /**
     * 时间窗内有登录成功的去重用户数（DAU 降级口径：只算登录，不算任意业务请求）。
     *
     * @param begin 起始时间（UTC，含）
     * @param end   截止时间（UTC，不含）
     * @return 去重用户数
     */
    @Select("SELECT COUNT(DISTINCT user_id) FROM login_log "
            + "WHERE result = 'success' AND created_at >= #{begin} AND created_at < #{end}")
    long countActiveUsers(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    /**
     * 时间窗内新增注册数。
     *
     * @param begin 起始时间（UTC，含）
     * @param end   截止时间（UTC，不含）
     * @return 新增用户数
     */
    @Select("SELECT COUNT(*) FROM app_user "
            + "WHERE deleted_at IS NULL AND created_at >= #{begin} AND created_at < #{end}")
    long countNewUsers(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    /**
     * 时间窗内创建的订单数（全状态计入）。
     *
     * @param begin 起始时间（UTC，含）
     * @param end   截止时间（UTC，不含）
     * @return 订单数
     */
    @Select("SELECT COUNT(*) FROM orders WHERE created_at >= #{begin} AND created_at < #{end}")
    long countOrders(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    /**
     * 时间窗内已支付订单的平台服务费合计（分）。
     *
     * <p>口径：`orders.platform_fee_cents`，按 **paid_at** 落窗；不含充值（充值属预付款不是收入）。
     * 退款订单**仍计入**（服务费已在支付时确认，退款走退款流水），这与财务口径一致。</p>
     *
     * @param begin 起始时间（UTC，含）
     * @param end   截止时间（UTC，不含）
     * @return 服务费合计（分）；无数据为 0
     */
    @Select("SELECT COALESCE(SUM(platform_fee_cents), 0) FROM orders "
            + "WHERE paid_at IS NOT NULL AND paid_at >= #{begin} AND paid_at < #{end}")
    long sumPlatformFee(@Param("begin") LocalDateTime begin, @Param("end") LocalDateTime end);

    /**
     * 每日订单数趋势。
     *
     * @param begin 起始时间（UTC，含）
     * @return 日期（yyyy-MM-dd）→ 订单数
     */
    @Select("SELECT DATE(created_at) AS d, COUNT(*) AS c FROM orders "
            + "WHERE created_at >= #{begin} GROUP BY DATE(created_at) ORDER BY d")
    List<TrendRow> selectOrderTrend(@Param("begin") LocalDateTime begin);

    /**
     * 每日平台服务费趋势（分）。
     *
     * @param begin 起始时间（UTC，含）
     * @return 日期（yyyy-MM-dd）→ 金额
     */
    @Select("SELECT DATE(paid_at) AS d, COALESCE(SUM(platform_fee_cents), 0) AS c FROM orders "
            + "WHERE paid_at IS NOT NULL AND paid_at >= #{begin} GROUP BY DATE(paid_at) ORDER BY d")
    List<TrendRow> selectRevenueTrend(@Param("begin") LocalDateTime begin);

    /**
     * 待处理的实名认证数。
     *
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM realname_auth WHERE status = 'pending'")
    long countPendingRealname();

    /**
     * 待审核的律师入驻申请数。
     *
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM lawyer_application WHERE audit_status = 'pending'")
    long countPendingLawyerApplication();

    /**
     * 待审核的提现单数。
     *
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM withdraw_record WHERE status = 'applying'")
    long countPendingWithdraw();

    /**
     * 待处理的工单数（waiting / processing）。
     *
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM service_ticket WHERE status IN ('waiting', 'processing')")
    long countPendingTickets();

    /**
     * 已超时且未结束的工单数（SLA 过期）。
     *
     * @param now 当前时间（UTC）
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM service_ticket "
            + "WHERE status IN ('waiting', 'processing') AND sla_due_at < #{now}")
    long countOvertimeTickets(@Param("now") LocalDateTime now);

    /**
     * 待处置的内容审核命中数。
     *
     * @return 条数
     */
    @Select("SELECT COUNT(*) FROM content_review WHERE review_result = 'pending'")
    long countPendingContentReviews();

    /**
     * 趋势查询的行结构（日期 + 数值）。
     */
    class TrendRow {

        /** 日期（SQL 的 DATE() 结果，MyBatis 映射成字符串）。 */
        private String d;

        /** 数值。 */
        private Long c;

        /**
         * 取日期。
         *
         * @return 日期字符串
         */
        public String getD() {
            return d;
        }

        /**
         * 设置日期。
         *
         * @param d 日期字符串
         */
        public void setD(String d) {
            this.d = d;
        }

        /**
         * 取数值。
         *
         * @return 数值
         */
        public Long getC() {
            return c;
        }

        /**
         * 设置数值。
         *
         * @param c 数值
         */
        public void setC(Long c) {
            this.c = c;
        }
    }
}
