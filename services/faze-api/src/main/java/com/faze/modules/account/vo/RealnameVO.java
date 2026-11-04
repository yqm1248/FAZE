/*
 * 职责：实名认证查询出参（`GET /app/account/realname`）。
 * 调用方：modules/account/controller/RealnameController。
 * 数据来源：realname_auth。
 * 注意：
 *   1. **只回状态与驳回理由**，不回身份证号（明文密文都不回）、不回真实姓名完整值 ——
 *      审核阶段 App 端不需要看这些，多回一个字就多一份泄漏面。
 *   2. `status` 是大写枚举（PENDING/APPROVED/REJECTED/NONE），与用户 VO 的 realnameStatus 同口径。
 */
package com.faze.modules.account.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 实名认证状态出参。
 */
@Data
public class RealnameVO {

    /** NONE / PENDING / APPROVED / REJECTED。 */
    private String status;

    /** 驳回原因（status=REJECTED 时非空）。 */
    private String rejectReason;

    /** 身份证号掩码，供「审核中」页回显。 */
    private String idCardMasked;

    /** 提交时间（UTC）。 */
    private LocalDateTime submittedAt;

    /** 审核时间（UTC）。 */
    private LocalDateTime reviewedAt;
}
