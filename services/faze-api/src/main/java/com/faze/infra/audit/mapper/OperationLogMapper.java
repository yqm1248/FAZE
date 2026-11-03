/*
 * 职责：operation_log 的数据访问。只暴露插入能力，不提供 update/delete。
 * 调用方：infra/audit/OperationLogAspect。
 * 数据来源：operation_log 表（V6__ops_config.sql）。
 * 注意：
 *   1. **不继承 BaseMapper 的全量方法**会给业务代码开口子（可以 update/delete 审计表），
 *      所以这里只声明 insert 与按条件查询，需要更多查询时显式加方法。
 *   2. 表是只追加的（《数据表规范》B 类），删除能力在 DB 账号层面也不授予：
 *      faze_app 有 DELETE 权限是段 0 的历史授权，收口时应收回 operation_log 的 DELETE。
 */
package com.faze.infra.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.infra.audit.entity.OperationLogEntity;

/**
 * 审计日志 Mapper。
 */
public interface OperationLogMapper extends BaseMapper<OperationLogEntity> {
}
