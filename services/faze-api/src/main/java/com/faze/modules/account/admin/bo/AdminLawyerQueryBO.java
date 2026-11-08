/*
 * 职责：A-06 律师列表的查询条件。
 * 调用方：modules/account/admin/controller/AdminLawyerController。
 * 数据来源：URL query 参数。
 * 注意：
 *   1. `keyword` 同时模糊匹配姓名与律所（`real_name LIKE ? OR law_firm LIKE ?`），
 *      两个条件在 Service 里用 Wrapper 的嵌套 and 表达，SQL 仍是参数化的。
 *   2. `auditStatus` 入参大写，Service 转库内小写后查询。
 *   3. 本段没有「违规 / 限流 / 下架 / 清退」筛选，契约明确推迟到段 7。
 */
package com.faze.modules.account.admin.bo;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 律师列表查询条件。
 */
@Data
public class AdminLawyerQueryBO {

    /** 页码。 */
    private Integer pageNum;

    /** 每页条数。 */
    private Integer pageSize;

    /** 姓名 / 律所模糊匹配。 */
    private String keyword;

    /** 执业地区精确匹配。 */
    private String region;

    /** 审核状态：PENDING / APPROVED / REJECTED；空=全部。 */
    private String auditStatus;

    /** 入驻时间下界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /** 入驻时间上界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
