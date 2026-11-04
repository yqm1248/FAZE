/*
 * 职责：A-04 认证队列的查询条件。
 * 调用方：modules/account/admin/controller/AdminVerificationController。
 * 数据来源：URL query 参数。
 * 注意：
 *   1. `type` / `status` 入参是**大写枚举名**（realname|enterprise、PENDING|…），
 *      但本 BO 传给 Mapper 前会被 Service 改写成库内小写；Mapper XML 直接用
 *      `#{bo.type}` 与 `#{bo.status}`，不再做大小写转换（SQL 里转换会让索引失效）。
 *   2. 空值表示「不过滤」，不是「查 null 状态」。
 */
package com.faze.modules.account.admin.bo;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 认证队列查询条件。
 */
@Data
public class AdminVerificationQueryBO {

    /** 认证类型：realname / enterprise；空=全部。 */
    private String type;

    /** 状态：pending / approved / rejected；空=全部。 */
    private String status;

    /** 页码。 */
    private Integer pageNum;

    /** 每页条数。 */
    private Integer pageSize;

    /** 提交时间下界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /** 提交时间上界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
