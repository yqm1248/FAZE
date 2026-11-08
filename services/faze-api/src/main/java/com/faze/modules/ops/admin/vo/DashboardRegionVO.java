/*
 * 职责：A-02 用户地区分布的出参。
 * 调用方：modules/ops 的 AdminDashboardService / AdminDashboardController。
 * 数据来源：**本段无数据源**——`app_user` 表没有 `region` 列（段 1 未建），
 *          工作流 A5 假设的「app_user.region（段 1 已有）」与实际库不符。
 * 注意：
 *   1. 接口**照常返回结构**（`list` 为空数组 + `note` 说明原因），不返回 4xx、
 *      也不伪造数据——A-02 稿件的这块区域会显示空态，这与「宁可空态不给假数」一致。
 *   2. 不擅自 `ALTER TABLE app_user ADD region`：段 1 的表结构变更要走它自己的迁移，
 *      本段加列会让「谁改的、为什么改」失去记录（工作流 E2 卡住怎么办同一原则）。
 *   3. 要恢复这块能力的最小动作：段 1 补 `region` 列 + 注册/资料页采集，
 *      然后把本类与 Service 里的查询接上。已记 §十一 与挂账。
 */
package com.faze.modules.ops.admin.vo;

import java.util.List;
import lombok.Data;

/**
 * 地区分布。
 */
@Data
public class DashboardRegionVO {

    /** 地区分布（按注册地，前 5 + 其他）；本段恒为空列表。 */
    private List<RegionItem> list;

    /** 说明（为什么为空）。 */
    private String note;

    /**
     * 单项。
     */
    @Data
    public static class RegionItem {

        /** 地区名。 */
        private String name;

        /** 用户数。 */
        private Long count;

        /**
         * 构造单项。
         *
         * @param name  地区名
         * @param count 用户数
         * @return 单项
         */
        public static RegionItem of(String name, Long count) {
            RegionItem item = new RegionItem();
            item.setName(name);
            item.setCount(count);
            return item;
        }
    }
}
