/*
 * 职责：PC 律师结算列表（A-13）查询入参。
 * 调用方：modules/trade/admin/controller/AdminSettlementController。
 * 数据来源：PC A-13 查询栏（律师姓名/律所、结算周期、结算状态）。
 * 注意：
 *   1. PC 管理端分页场景是 `rows`/`total`（接口规范 §6），不是 C 端的 `list`。
 *   2. `lawyerKw` 同时匹配律师姓名与律所名，匹配方式与 A-19 的 `kw` 一致（`LIKE '%kw%'`）。
 *   3. `period` 只认 `yyyy-MM`；传别的格式由 Service 抛 400001，不在 BO 上做正则校验
 *      （这样错误码统一、文案可 i18n）。
 */
package com.faze.modules.trade.admin.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * PC 律师结算列表查询入参。
 */
@Data
public class SettlementQueryBO {

    /** 律师姓名 / 律所关键词；空为全部。 */
    private String lawyerKw;

    /** 结算周期 `yyyy-MM`；空为全部。 */
    private String period;

    /** 结算状态 pending / settled / rejected；空为全部。 */
    private String status;

    /** 页码。 */
    @Min(value = 1, message = "页码从 1 开始")
    private int pageNum = 1;

    /** 每页条数（≤100）。 */
    @Min(value = 1, message = "每页条数至少 1")
    @Max(value = 100, message = "每页条数最多 100")
    private int pageSize = 20;
}
