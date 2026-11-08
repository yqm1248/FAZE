/*
 * 职责：lawyer_application 的数据访问。
 * 调用方：modules/account/service/LawyerApplicationService。
 * 数据来源：faze.lawyer_application。
 * 注意：本表**没有** user_id 唯一键（允许保留历史申请），
 *      「当前申请」= 按 id 倒序第一条，查询时不要用 selectOne(eq userId)。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.account.entity.LawyerApplication;

/**
 * 律师入驻申请 Mapper。
 */
public interface LawyerApplicationMapper extends BaseMapper<LawyerApplication> {
}
