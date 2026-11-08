/*
 * 职责：设备列表的出参（`GET /app/account/devices`）。
 * 调用方：modules/account/controller/AccountSecurityController。
 * 数据来源：user_device。
 * 注意：
 *   1. **不返回 push cid**：它是推送凭证，客户端不需要，泄漏出去可以给指定账号推任意通知。
 *   2. `current` 由服务端判：请求头 X-Device-Id 与记录一致即为当前设备——
 *      不能由客户端自己传「我是当前设备」，那等于自己给自己发奖状。
 *   3. deviceId 本身可以回给客户端：它是客户端自己生成并持久化的标识，不是硬件指纹。
 */
package com.faze.modules.account.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 设备出参。
 */
@Data
public class DeviceVO {

    /** 客户端设备标识。 */
    private String deviceId;

    /** android / ios。 */
    private String platform;

    /** 机型。 */
    private String deviceModel;

    /** 系统版本。 */
    private String osVersion;

    /** App 版本号。 */
    private String appVersion;

    /** 最后活跃时间（UTC）。 */
    private LocalDateTime lastActiveAt;

    /** 是否为当前发起请求的设备。 */
    private Boolean current;
}
