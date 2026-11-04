/*
 * 职责：user_device 的数据访问。
 * 调用方：modules/account/service/impl/AccountQueryServiceImpl（设备列表）。
 * 数据来源：faze.user_device。
 * 注意：查询必须自己带 `deletedAt IS NULL`（段 1 无逻辑删除拦截器）。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.account.entity.UserDevice;

/**
 * 用户设备 Mapper。
 */
public interface UserDeviceMapper extends BaseMapper<UserDevice> {
}
