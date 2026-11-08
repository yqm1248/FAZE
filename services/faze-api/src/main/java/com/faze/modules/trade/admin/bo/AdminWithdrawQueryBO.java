/*
 * 职责：PC 提现审核列表（A-10）查询入参。
 * 调用方：modules/trade/admin/controller/AdminWithdrawController。
 * 数据来源：A-10 查询栏（律师姓名/手机号/执业证号、提现状态、申请时间）。
 * 注意：
 *   1. PC 管理端分页是 `rows`/`total`（接口规范 §6）。
 *   2. 申请时间用**东八区日期**做区间：财务是按天对账的，传 `yyyy-MM-dd`
 *      由 Service 换算成 UTC 半开区间（不把时区换算丢给前端）。
 *   3. `status` 空表示全部；值非法由 Service 抛 405025（不静默忽略）。
 */
package com.faze.modules.trade.admin.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * PC 提现列表查询入参。
 */
@Data
public class AdminWithdrawQueryBO {

    /** 律师姓名 / 手机号 / 执业证号关键词；空为全部。 */
    private String lawyerKw;

    /** 提现状态 applying / approved / rejected / paid；空为全部。 */
    private String status;

    /** 申请起始日（东八区，`yyyy-MM-dd`，含）。 */
    private String beginDate;

    /** 申请截止日（东八区，`yyyy-MM-dd`，含）。 */
    private String endDate;

    /** 页码。 */
    @Min(value = 1, message = "页码从 1 开始")
    private int pageNum = 1;

    /** 每页条数（≤100）。 */
    @Min(value = 1, message = "每页条数至少 1")
    @Max(value = 100, message = "每页条数最多 100")
    private int pageSize = 20;
}
