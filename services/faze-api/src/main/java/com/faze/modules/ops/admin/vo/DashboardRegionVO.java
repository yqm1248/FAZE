/*
 * 职责：A-02 用户地区分布的出参。
 * 调用方：modules/ops 的 AdminDashboardService / AdminDashboardController。
 * 数据来源：`app_user.region` —— 该列是 **V707（第 7 段尾巴）** 才补上的（段 1 没建），
 *          由 `DashboardMapper.selectRegions` 按地区分组统计。
 * 注意：
 *   1. **列已建、但还没有采集点**：注册流程不收集地区，`app_user.region` 恒为 NULL，
 *      所以 `list` 当前**只有一项「其他」**（NULL 与空串都归一化到「其他」），
 *      合计 = 未注销用户数（`deleted_at IS NULL`）。这既不是空态也不是假数据，
 *      是「列有了、数据还没采到」的如实结果。
 *   2. **口径是「前 5 名 + 其他」**：`AdminDashboardService.regions()` 取前 5 名，
 *      第 6 名起合并进「其他」——并入已有项，不新增第二个「其他」。
 *   3. **不按 status 过滤**：与 `countNewUsers` 口径一致，只看 `deleted_at` 判断是否注销；
 *      因此 `cancelled` 但未软删的用户**会计入看板、却收不到公告**
 *      （公告可发人群要求 `status='active'`）。这个差异已记 §十一 第 19 条。
 *   4. 采集入口（注册页 / 资料页的地区选择器）**段 8 补**；补完之后本接口才会出现真实地区。
 */
package com.faze.modules.ops.admin.vo;

import java.util.List;
import lombok.Data;

/**
 * 地区分布。
 */
@Data
public class DashboardRegionVO {

    /** 地区分布（按注册地，前 5 名 + 「其他」）；当前只有一项「其他」（尚无采集点）。 */
    private List<RegionItem> list;

    /** 说明：数据现状（是否已采集）与统计口径。 */
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
