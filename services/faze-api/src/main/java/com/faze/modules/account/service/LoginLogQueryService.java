/*
 * 职责：登录日志的**只读**跨域查询接口（供 PC A-26 日志中心用）。
 * 调用方：modules/ops 的 AdminLoginLogController / AdminLoginLogService。
 * 数据来源：faze.login_log（本域表）。
 * 注意：
 *   1. **只暴露查询**：登录日志是 B 类追加表，没有任何写入口——连本域都不改不删
 *      （《数据表规范》§四：B 类表是证据链，不许 UPDATE / DELETE）。
 *   2. 返回 `PageResult<LoginLog>`（本域实体）而不是 VO：本接口的消费方是 ops 的管理端
 *      聚合层，由它负责转 VO；如果这里返回 VO，就得把 ops 的展示字段塞进 account 域，
 *      依赖方向会反过来。
 *   3. 时间参数按 **UTC** 传（库内 UTC），由调用方换算。
 */
package com.faze.modules.account.service;

import com.faze.common.pojo.PageResult;
import com.faze.modules.account.entity.LoginLog;
import java.time.LocalDateTime;

/**
 * 登录日志查询。
 */
public interface LoginLogQueryService {

    /**
     * 分页查询登录日志（按时间倒序）。
     *
     * @param account   账号（写库时已脱敏，按前缀/全等匹配由实现决定）；可为空
     * @param ip        来源 IP 精确匹配；可为空
     * @param result    结果 success / fail；可为空
     * @param beginTime 起始时间（含，UTC）；可为空
     * @param endTime   截止时间（含，UTC）；可为空
     * @param pageNum   页码（从 1 开始）
     * @param pageSize  每页条数（已由调用方 clamp）
     * @return 分页结果
     */
    PageResult<LoginLog> page(String account, String ip, String result,
                              LocalDateTime beginTime, LocalDateTime endTime,
                              int pageNum, int pageSize);
}
