/*
 * 职责：A-03 封禁用户的入参。
 * 调用方：modules/account/admin/controller/AdminUserController（POST /users/{id}/ban）。
 * 数据来源：请求体。
 * 注意：
 *   1. `reason` **必填且 ≤200**：封禁是影响用户使用权的操作，没有理由的封禁事后无法复核。
 *   2. `until` 可空，表示永久封禁。**本段不把 until 落库**（app_user 没有到期列，
 *      自动解封属段 7 的处罚分级），只写进 operation_log.detail 供复核。
 *   3. 目标用户来自路径变量，**不从 body 取**（防止「路径改 A、body 改 B」的越权）。
 */
package com.faze.modules.account.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 封禁用户请求。
 */
@Data
public class AdminUserBanBO {

    /** 封禁理由（必填，≤200）。 */
    @NotBlank(message = "封禁理由不能为空")
    @Size(max = 200, message = "封禁理由过长")
    private String reason;

    /** 封禁到期时间（可空=永久）；本段只入审计，不落库。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime until;
}
