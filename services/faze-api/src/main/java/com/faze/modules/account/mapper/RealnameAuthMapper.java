/*
 * 职责：realname_auth 的数据访问。
 * 调用方：modules/account/service/RealnameService。
 * 数据来源：faze.realname_auth。
 * 注意：该表一对一（uk_realname_user），按 user_id 查用 selectOne；
 *      重复提交是 update 而不是 insert（C1 要求「不产生第二条」）。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.account.entity.RealnameAuth;

/**
 * 实名认证 Mapper。
 */
public interface RealnameAuthMapper extends BaseMapper<RealnameAuth> {
}
