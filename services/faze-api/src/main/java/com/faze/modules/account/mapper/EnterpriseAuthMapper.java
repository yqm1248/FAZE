/*
 * 职责：enterprise_auth 的数据访问。
 * 调用方：modules/account/service/EnterpriseService。
 * 数据来源：faze.enterprise_auth。
 * 注意：一对表（uk_enterprise_user），按 user_id 查用 selectOne。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.account.entity.EnterpriseAuth;

/**
 * 企业认证 Mapper。
 */
public interface EnterpriseAuthMapper extends BaseMapper<EnterpriseAuth> {
}
