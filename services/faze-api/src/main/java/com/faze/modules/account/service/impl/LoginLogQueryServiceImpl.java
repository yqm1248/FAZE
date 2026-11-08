/*
 * 职责：登录日志查询接口的实现。
 * 调用方：modules/account 的 LoginLogQueryService 消费方（ops 的日志中心）。
 * 数据来源：faze.login_log。
 * 注意：
 *   1. 单表查询，全用 `LambdaQueryWrapper`，不写 XML（规范 §8.1）。
 *   2. **按时间倒序 + 有上限**：登录日志是增长最快的表之一（每次登录一行），
 *      不带时间范围的全表翻页会越翻越慢；pageSize 在调用方已 clamp 到 ≤100。
 *   3. `account` 支持**模糊匹配**：库里存的是脱敏号（`138****5678`），
 *      管理员想搜「138」也能命中；不做全等，否则输入完整号永远搜不到（库里没有明文）。
 *   4. 时间字段按 UTC 比较，不做时区换算（换算在前端展示层）。
 */
package com.faze.modules.account.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.entity.LoginLog;
import com.faze.modules.account.mapper.LoginLogMapper;
import com.faze.modules.account.service.LoginLogQueryService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 登录日志查询实现。
 */
@Service
public class LoginLogQueryServiceImpl implements LoginLogQueryService {

    /** 登录日志 Mapper。 */
    private final LoginLogMapper loginLogMapper;

    /**
     * 构造实现。
     *
     * @param loginLogMapper 登录日志 Mapper
     */
    public LoginLogQueryServiceImpl(LoginLogMapper loginLogMapper) {
        this.loginLogMapper = loginLogMapper;
    }

    /**
     * 分页查询登录日志。
     *
     * @param account   账号片段（脱敏后的库值做 like）
     * @param ip        来源 IP
     * @param result    结果
     * @param beginTime 起始时间（UTC）
     * @param endTime   截止时间（UTC）
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 分页结果
     */
    @Override
    public PageResult<LoginLog> page(String account, String ip, String result,
                                     LocalDateTime beginTime, LocalDateTime endTime,
                                     int pageNum, int pageSize) {
        LambdaQueryWrapper<LoginLog> wrapper = Wrappers.<LoginLog>lambdaQuery()
                .like(StringUtils.hasText(account), LoginLog::getAccount, account)
                .eq(StringUtils.hasText(ip), LoginLog::getIp, ip)
                .eq(StringUtils.hasText(result), LoginLog::getResult, result)
                .ge(beginTime != null, LoginLog::getCreatedAt, beginTime)
                .le(endTime != null, LoginLog::getCreatedAt, endTime)
                .orderByDesc(LoginLog::getId);

        IPage<LoginLog> page = loginLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize);
    }
}
