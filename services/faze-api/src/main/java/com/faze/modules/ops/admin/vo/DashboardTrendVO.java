/*
 * 职责：A-02 趋势图（折线）的出参。
 * 调用方：modules/ops 的 AdminDashboardService / AdminDashboardController。
 * 数据来源：DashboardMapper 的按日聚合 SQL。
 * 注意：
 *   1. **点位按日期升序、缺日补 0**：数据库只会返回有数据的日期，缺的那天必须补 0
 *      再交给前端——否则折线会把「周二没单」画成「周一直接连到周三」，看起来像连续增长。
 *   2. `metric` 决定数值含义：`orders` 是笔数，`revenue` 是分。前端按 metric 决定单位与格式。
 *   3. 服务端**不做等间隔降采样**：14 个点不需要抽样；真要降采样也要按时间桶聚合，
 *      不能等间隔取点（会漏峰值，后端规范 §3.10）。
 */
package com.faze.modules.ops.admin.vo;

import java.util.List;
import lombok.Data;

/**
 * 趋势数据。
 */
@Data
public class DashboardTrendVO {

    /** 指标：orders / revenue。 */
    private String metric;

    /** 时间范围（固定 14d）。 */
    private String range;

    /** 点位（日期升序，缺日补 0）。 */
    private List<Point> points;

    /** 口径说明。 */
    private String note;

    /**
     * 单个点位。
     */
    @Data
    public static class Point {

        /** 日期（yyyy-MM-dd，Asia/Shanghai 口径）。 */
        private String date;

        /** 数值（orders=笔数，revenue=分）。 */
        private Long value;

        /**
         * 构造点位。
         *
         * @param date  日期
         * @param value 数值
         * @return 点位
         */
        public static Point of(String date, Long value) {
            Point p = new Point();
            p.setDate(date);
            p.setValue(value);
            return p;
        }
    }
}
