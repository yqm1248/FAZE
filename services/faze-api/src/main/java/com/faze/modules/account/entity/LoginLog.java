/*
 * 职责：login_log 表的实体映射（登录日志，B 追加型，只插入不修改）。
 * 调用方：modules/account/service/LoginLogService。
 * 数据来源：V3__account_auth.sql 建的 login_log。
 * 注意：
 *   1. `account` 列存**脱敏后**的手机号（表注释要求），原始号码在 app_user 里，靠 user_id 关联。
 *   2. 失败记录没有 user_id（用户可能不存在），只有 account 与 fail_reason，这是审计需要的口径。
 *   3. 本表只追加；不提供 update / delete 的业务入口。
 */
package com.faze.modules.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 登录日志。
 */
@Data
@TableName("login_log")
public class LoginLog {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录成功的用户 ID；失败时为 null。 */
    private Long userId;

    /** 登录账号（脱敏手机号）。 */
    private String account;

    /** 登录方式：password / sms / register。 */
    private String loginType;

    /** 结果：success / fail。 */
    private String result;

    /** 失败原因（不区分账号错还是密码错，防枚举）。 */
    private String failReason;

    /** 客户端 IP。 */
    private String ip;

    /** User-Agent。 */
    private String userAgent;

    /** 设备标识。 */
    private String deviceId;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;
}
