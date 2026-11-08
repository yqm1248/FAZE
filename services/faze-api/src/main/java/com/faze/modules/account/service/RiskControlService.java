/*
 * 职责：账号风控的最小可执行规则（异常注册检测 + 新设备登录提醒）。
 * 调用方：modules/account 的 AuthService（register / login 两处接入）。
 * 数据来源：login_log（本域）+ sys_config（阈值，ops 域只读接口）+ user_violation（经 ops 写服务）。
 * 注意：
 *   1. **为什么放在 account 域**：它读 login_log（account 的表）。如果放 ops，
 *      ops → account（读日志）而 account → ops（登记违规）就形成**循环依赖**，
 *      后端规范 §2.3 第 3 条明令禁止（不许用 @Lazy 绕）。放这里之后依赖是单向的：
 *      account → ops（只调 ops 的写服务与只读配置）。
 *   2. **只记录、不硬拦**（工作流 B5 第 2 条）：同 IP 24h 注册第 4 个仍成功，
 *      只写一条 `user_violation(restrict)` 供运营看。硬拦会误伤「一家人共用 WiFi 注册」。
 *   3. **阈值全部读 sys_config**（`risk.*`），改完即时生效（每次请求读，不做本地缓存）。
 *      配置缺失时用代码里的默认值，不因缺配置而失败。
 *   4. 所有异常都在本类内部吞掉并打 WARN：风控是旁路能力，**不能因为它失败导致登录/注册失败**。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.account.entity.LoginLog;
import com.faze.modules.account.mapper.LoginLogMapper;
import com.faze.modules.ops.service.NotificationService;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.ops.service.UserViolationWriteService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 账号风控。
 */
@Slf4j
@Service
public class RiskControlService {

    /** 注册日志类型。 */
    private static final String LOGIN_TYPE_REGISTER = "register";

    /** 成功结果。 */
    private static final String RESULT_SUCCESS = "success";

    /** 同 IP 24h 注册数阈值配置键。 */
    public static final String KEY_IP_PER_DAY = "risk.register_ip_per_day";

    /** 同设备 24h 注册数阈值配置键。 */
    public static final String KEY_DEVICE_PER_DAY = "risk.register_device_per_day";

    /** 注册日志 Mapper（本域）。 */
    private final LoginLogMapper loginLogMapper;

    /** 平台参数（ops 只读接口）。 */
    private final SysConfigQueryService sysConfig;

    /** 违规写服务（ops 域）。 */
    private final UserViolationWriteService violationWriteService;

    /** 站内信（ops 域）。 */
    private final NotificationService notificationService;

    /**
     * 构造风控服务。
     *
     * @param loginLogMapper        登录日志 Mapper
     * @param sysConfig             平台参数
     * @param violationWriteService 违规写服务
     * @param notificationService   站内信
     */
    public RiskControlService(LoginLogMapper loginLogMapper, SysConfigQueryService sysConfig,
                              UserViolationWriteService violationWriteService,
                              NotificationService notificationService) {
        this.loginLogMapper = loginLogMapper;
        this.sysConfig = sysConfig;
        this.violationWriteService = violationWriteService;
        this.notificationService = notificationService;
    }

    /**
     * 注册后做异常检测（只记录不拦）。
     *
     * <p>副作用：同 IP / 同设备 24h 注册数超阈时写一条 `user_violation(restrict)`。</p>
     *
     * @param userId   新注册用户 id
     * @param clientIp 客户端 IP
     */
    public void checkAbnormalRegister(Long userId, String clientIp) {
        try {
            if (!StringUtils.hasText(clientIp)) {
                return;
            }
            LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
            long sameIp = loginLogMapper.selectCount(Wrappers.<LoginLog>lambdaQuery()
                    .eq(LoginLog::getLoginType, LOGIN_TYPE_REGISTER)
                    .eq(LoginLog::getResult, RESULT_SUCCESS)
                    .eq(LoginLog::getIp, clientIp)
                    .ge(LoginLog::getCreatedAt, since));
            int threshold = sysConfig.getInt(KEY_IP_PER_DAY, 3);
            if (sameIp > threshold) {
                violationWriteService.record(userId, "user", "abnormal_register", "restrict",
                        "同 IP 24 小时内注册 " + sameIp + " 个账号（阈值 " + threshold + "）",
                        "{\"ip\":\"" + clientIp + "\",\"count\":" + sameIp + "}", null, null);
                log.warn("异常注册检测命中 userId={} ip={} count={}", userId, clientIp, sameIp);
            }
        } catch (Exception e) {
            // 风控是旁路：失败不能影响注册主流程
            log.warn("异常注册检测失败（不影响注册） userId={}", userId, e);
        }
    }

    /**
     * 登录后检查是否新设备（是则发站内信）。
     *
     * <p>副作用：写一条站内信「检测到新设备登录」。</p>
     *
     * @param userId   用户 id
     * @param deviceId 设备标识（请求头 X-Device-Id）
     * @param clientIp 客户端 IP
     */
    public void notifyNewDeviceLogin(Long userId, String deviceId, String clientIp) {
        try {
            if (userId == null || !StringUtils.hasText(deviceId)) {
                return;
            }
            long previous = loginLogMapper.selectCount(Wrappers.<LoginLog>lambdaQuery()
                    .eq(LoginLog::getUserId, userId)
                    .eq(LoginLog::getResult, RESULT_SUCCESS)
                    .eq(LoginLog::getDeviceId, deviceId)
                    .ne(LoginLog::getLoginType, LOGIN_TYPE_REGISTER));
            // 当前这条刚写入，所以 previous == 1 表示「只有本次」= 新设备
            if (previous <= 1) {
                notificationService.send(userId, "system", "检测到新设备登录",
                        "您的账号在新设备上登录" + (StringUtils.hasText(clientIp) ? "（IP " + clientIp + "）" : "")
                                + "。如非本人操作，请立即修改密码。",
                        "security", null);
            }
        } catch (Exception e) {
            log.warn("新设备登录提醒失败（不影响登录） userId={}", userId, e);
        }
    }
}
