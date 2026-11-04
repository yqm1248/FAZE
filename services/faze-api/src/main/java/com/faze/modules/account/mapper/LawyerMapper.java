/*
 * 职责：lawyer 表的数据访问（律师主数据，A-05 写入 / A-06 查询）。
 * 调用方：modules/account/admin/service/AdminLawyerApplicationAuditExecutor（插入）、
 *         modules/account/admin/service/AdminLawyerService（列表 / 详情）。
 * 数据来源：MySQL faze.lawyer。
 * 注意：
 *   1. 继承 BaseMapper 即可，单表条件查询用 LambdaQueryWrapper（规范 §8.1）；
 *      本段没有多表联查，不写 XML。
 *   2. **查询必须自己带 `deleted_at IS NULL`**：段 1 没有 MP 逻辑删除拦截器
 *      （见 Lawyer 类注释第 2 条），漏掉就会把已清退律师查出来。
 *   3. 不要在这里暴露「按 license_number 查」以外的写方法；审核写入由
 *      AdminLawyerApplicationAuditExecutor 负责，Mapper 不做业务判断。
 */
package com.faze.modules.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.account.entity.Lawyer;

/**
 * 律师表 Mapper。
 */
public interface LawyerMapper extends BaseMapper<Lawyer> {
}
