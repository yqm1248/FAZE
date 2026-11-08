/*
 * 职责：`GET /api/v1/admin/ops/health-report` 的出参（G3 第 5 条：后台红标 / 每日巡检报告的数据）。
 * 调用方：modules/ops/controller/AdminOpsController → PC 后台运维健康页 / 巡检脚本。
 * 数据来源：OpsHealthService 的 6 项实时检查。
 * 注意：
 *   1. **异常项 `ok=false` 而不是缺项**：6 项永远都在，缺项会让「页面少一行」被当成正常。
 *   2. `detail` 是给人看的实测值（如「使用率 41.2%」），不是错误码；
 *      机器判断只看 `ok`。
 *   3. 报告级 `ok` 是 6 项的与：任意一项异常即 false，页面据此整体标红。
 *   4. 纯查询 VO，不落库、不进审计（《审计与日志规范》§3.2 只审计写操作）。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 运维健康报告。
 */
@Data
public class OpsHealthReportVO {

    /** 总体是否健康（6 项全部 ok）。 */
    private boolean ok;

    /** 检查时间（UTC）。 */
    private LocalDateTime checkedAt;

    /** 6 项检查结果。 */
    private List<Item> items;

    /**
     * 单项检查结果。
     */
    @Data
    public static class Item {

        /** 机器键：db / redis / disk / backup / sensitive / queue。 */
        private String key;

        /** 中文名。 */
        private String name;

        /** 是否正常（异常项为 false）。 */
        private boolean ok;

        /** 实测值说明。 */
        private String detail;

        /**
         * 构造一项。
         *
         * @param key    机器键
         * @param name   中文名
         * @param ok     是否正常
         * @param detail 实测值说明
         * @return 项
         */
        public static Item of(String key, String name, boolean ok, String detail) {
            Item item = new Item();
            item.setKey(key);
            item.setName(name);
            item.setOk(ok);
            item.setDetail(detail);
            return item;
        }
    }
}
