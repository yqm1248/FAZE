/*
 * 职责：A-09 对账差异行的出参（内存态，不落 reconciliation_diff 表）。
 * 调用方：modules/trade/admin/service/AdminReconciliationService、AdminReconciliationController。
 * 数据来源：现算（缓存值 - 流水汇总）。
 * 注意：
 *   1. `ownerId` 是 **Long**（ID，序列化成字符串）；三个金额是**原生 long**（JSON 数字）。
 *   2. `ownerType=user` 时 ownerId 是 app_user.id；`ownerType=lawyer` 时是 lawyer.id
 *      ——两者主键各自自增，值会撞，展示时必须带 ownerType（契约也这么定义）。
 *   3. `diffCents = cacheCents - flowCents`，正数=缓存多了、负数=缓存少了；
 *      不在接口里判断哪边对——流水是权威，处置动作属段 5 的差异工单。
 */
package com.faze.modules.trade.admin.vo;

import lombok.Data;

/**
 * 对账差异行。
 */
@Data
public class ReconciliationDiffVO {

    /** 归属类型：user / lawyer。 */
    private String ownerType;

    /** 归属主键。 */
    private Long ownerId;

    /** 缓存值（分，可用 + 冻结）。 */
    private long cacheCents;

    /** 流水汇总值（分）。 */
    private long flowCents;

    /** 差异 = cache - flow。 */
    private long diffCents;
}
