/*
 * 职责：lawyer_application_log 的数据访问（只插入与按申请查询）。
 * 调用方：modules/account/service/LawyerApplicationService。
 * 数据来源：faze.lawyer_application_log。
 * 注意：不给业务代码提供 update / delete——该表是审核证据链。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.account.entity.LawyerApplicationLog;

/**
 * 入驻审核流水 Mapper。
 */
public interface LawyerApplicationLogMapper extends BaseMapper<LawyerApplicationLog> {
}
