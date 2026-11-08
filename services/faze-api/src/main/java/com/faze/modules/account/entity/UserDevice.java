/*
 * 职责：user_device 表的实体映射（用户设备与推送标识）。
 * 调用方：modules/account/mapper/UserDeviceMapper、AccountQueryService（设备列表）。
 * 数据来源：V3__account_auth.sql 建的 user_device（12 字段）。
 * 注意：
 *   1. 表有 deleted_at（A 类业务实体），查询必须自己带 `isNull(deletedAt)`——
 *      段 1 仍没配 MP 逻辑删除拦截器（见 AppUser 类注释第 2 条）。
 *   2. `device_id` 是客户端生成的标识，不是手机硬件序列号；**不许**在这里存 IMEI/IDFA 等
 *      设备唯一硬件标识（合规上属个人信息，且 App 审核会查）。
 */
package com.faze.modules.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户设备。
 */
@Data
@TableName("user_device")
public class UserDevice {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户主键。 */
    private Long userId;

    /** 客户端设备标识（与请求头 X-Device-Id 一致）。 */
    private String deviceId;

    /** android / ios。 */
    private String platform;

    /** 离线推送 cid。 */
    private String pushCid;

    /** 机型。 */
    private String deviceModel;

    /** 系统版本。 */
    private String osVersion;

    /** App 版本号。 */
    private String appVersion;

    /** 最后活跃时间（UTC）。 */
    private LocalDateTime lastActiveAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删除时间（UTC）；NULL 表示未删除。 */
    private LocalDateTime deletedAt;
}
