/*
 * 职责：A-07 资金流水分页的查询条件。
 * 调用方：modules/trade/admin/controller/AdminWalletLedgerController。
 * 数据来源：URL query 参数。
 * 注意：
 *   1. `userId` **必填**（契约 A-07：按用户查流水）。缺参走 401001 分支，
 *      不默认查全表——全表流水页既慢又没有业务意义。
 *   2. `bizType` 只做等值过滤，不做枚举白名单校验：流水类型将来还会增加
 *      （refund / income / withdraw…），在这里写死白名单只会让新类型查不到。
 */
package com.faze.modules.trade.admin.bo;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 资金流水查询条件。
 */
@Data
public class AdminFlowQueryBO {

    /** 用户主键（必填）。 */
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    /** 业务类型（可空=全部）。 */
    private String bizType;

    /** 时间下界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /** 时间上界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码。 */
    private Integer pageNum;

    /** 每页条数。 */
    private Integer pageSize;
}
