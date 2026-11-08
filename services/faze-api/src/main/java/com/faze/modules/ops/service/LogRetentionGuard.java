/*
 * 职责：审计日志留存期的**启动校验**——配置低于合规下限时拒绝启动。
 * 调用方：Spring 启动阶段（@PostConstruct）。
 * 数据来源：sys_config 的 `log.operation_retention_days` / `log.login_retention_days`。
 * 注意：
 *   1. **这是「默认拒绝」原则的一次具体应用**：`operation_log` 要留 3 年（个保法/电商法）、
 *      `login_log` 要留 180 天（网安法 6 个月）。如果允许把配置改成 30 天并照常运行，
 *      就等于「合规下限可被一次误操作抹掉」，而且是静默的。
 *      所以低于下限时**启动直接失败**，让运维在日志里看到原因，而不是半年后才发现日志没了。
 *   2. 配置键用 `log.*`（V703 已落库）而不是工作流 A4 写的 `audit.operation-log-days`：
 *      两处键名不一致，**以已落库的为准**（改键名会让 PC A-08 页面上的项对不上）。
 *      该冲突记进 §十一 回写项。
 *   3. 配置**缺失**不报错（用默认值 1095 / 180）：新库可能还没种这两条，
 *      但「存在且低于下限」必须拦。
 *   4. 本类只做校验，不做清理——清理是 {@link LogRetentionCleanTask}。
 */
package com.faze.modules.ops.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审计日志留存期下限校验。
 */
@Slf4j
@Component
public class LogRetentionGuard {

    /** 操作日志合规下限（天）：1095 = 3 年。 */
    public static final int MIN_OPERATION_DAYS = 1095;

    /** 登录日志合规下限（天）：180 = 网安法 6 个月。 */
    public static final int MIN_LOGIN_DAYS = 180;

    /** 操作日志留存天数配置键。 */
    public static final String KEY_OPERATION_DAYS = "log.operation_retention_days";

    /** 登录日志留存天数配置键。 */
    public static final String KEY_LOGIN_DAYS = "log.login_retention_days";

    /** 平台参数查询。 */
    private final SysConfigQueryService sysConfig;

    /**
     * 构造校验器。
     *
     * @param sysConfig 平台参数查询
     */
    public LogRetentionGuard(SysConfigQueryService sysConfig) {
        this.sysConfig = sysConfig;
    }

    /**
     * 启动时校验两项留存期。
     *
     * @throws IllegalStateException 配置低于合规下限时
     */
    @PostConstruct
    public void verify() {
        int operationDays = sysConfig.getInt(KEY_OPERATION_DAYS, MIN_OPERATION_DAYS);
        int loginDays = sysConfig.getInt(KEY_LOGIN_DAYS, MIN_LOGIN_DAYS);
        log.info("审计日志留存期校验：operation={}天（下限 {}）, login={}天（下限 {}）",
                operationDays, MIN_OPERATION_DAYS, loginDays, MIN_LOGIN_DAYS);
        if (operationDays < MIN_OPERATION_DAYS) {
            throw new IllegalStateException("log.operation_retention_days=" + operationDays
                    + " 低于合规下限 " + MIN_OPERATION_DAYS + " 天（3 年），拒绝启动");
        }
        if (loginDays < MIN_LOGIN_DAYS) {
            throw new IllegalStateException("log.login_retention_days=" + loginDays
                    + " 低于合规下限 " + MIN_LOGIN_DAYS + " 天（网安法 6 个月），拒绝启动");
        }
    }
}
