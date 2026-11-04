/*
 * 职责：login_log 的数据访问。
 * 调用方：modules/account/service/LoginLogService。
 * 数据来源：faze.login_log。
 * 注意：只用于写入与审计查询；不要给业务代码提供删除入口（该表是证据链）。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.account.entity.LoginLog;

/**
 * 登录日志 Mapper。
 */
public interface LoginLogMapper extends BaseMapper<LoginLog> {
}
