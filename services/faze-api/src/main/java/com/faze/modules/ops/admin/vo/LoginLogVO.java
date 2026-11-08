/*
 * 职责：A-26 登录日志列表的出参。
 * 调用方：modules/ops 的 AdminLoginLogController。
 * 数据来源：faze.login_log。
 * 注意：
 *   1. `account` 在写库时**已经脱敏**（`138****5678`），不要再二次处理、也不要试图还原。
 *   2. `userAgent` 可能很长（已按列宽 500 截断），前端展示时截断显示即可。
 *   3. 本 VO 只是把 account 域的实体字段搬出来，**不含任何敏感字段**（无密码、无 token）。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 登录日志行。
 */
@Data
public class LoginLogVO {

    /** 主键。 */
    private Long id;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 用户主键（登录失败时可能为空）。 */
    private Long userId;

    /** 账号（已脱敏）。 */
    private String account;

    /** 登录方式：password / sms / refresh。 */
    private String loginType;

    /** 结果：success / fail。 */
    private String result;

    /** 失败原因（success 时为空）。 */
    private String failReason;

    /** 来源 IP。 */
    private String ip;

    /** User-Agent（≤500 字）。 */
    private String userAgent;

    /** 设备标识。 */
    private String deviceId;
}
