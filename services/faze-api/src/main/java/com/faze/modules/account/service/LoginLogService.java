/*
 * 职责：登录日志的写入（B 追加型，只插入）。
 * 调用方：modules/account/service/AuthService（密码登录 / 短信登录 / 注册）。
 * 数据来源：登录流程的上下文（用户 id、手机号、IP、UA、设备号）。
 * 注意：
 *   1. **写日志失败不能影响登录**：日志是记录，不是校验；抛异常会让「日志表满」变成
 *      「全站登录失败」。这里吞异常并打 error，便于巡检。
 *   2. account 列存脱敏手机号（表注释要求）；原始号在 app_user，靠 user_id 关联。
 *   3. 失败记录没有 userId（用户可能不存在），这是审计需要的口径，不要硬塞 0。
 */
package com.faze.modules.account.service;

import com.faze.modules.account.entity.LoginLog;
import com.faze.modules.account.mapper.LoginLogMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 登录日志服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginLogService {

    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAIL = "fail";

    private final LoginLogMapper loginLogMapper;

    /**
     * 记录一次成功登录。
     *
     * @param userId    用户主键
     * @param phone     手机号（入库前脱敏）
     * @param loginType password / sms / register
     * @param ip        客户端 IP
     * @param userAgent User-Agent
     * @param deviceId  设备标识
     */
    public void recordSuccess(Long userId, String phone, String loginType,
                              String ip, String userAgent, String deviceId) {
        write(userId, phone, loginType, RESULT_SUCCESS, null, ip, userAgent, deviceId);
    }

    /**
     * 记录一次失败登录。
     *
     * @param phone     手机号（入库前脱敏）
     * @param loginType password / sms
     * @param reason    失败原因（不含敏感信息）
     * @param ip        客户端 IP
     * @param userAgent User-Agent
     * @param deviceId  设备标识
     */
    public void recordFailure(String phone, String loginType, String reason,
                              String ip, String userAgent, String deviceId) {
        write(null, phone, loginType, RESULT_FAIL, reason, ip, userAgent, deviceId);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private void write(Long userId, String phone, String loginType, String result, String failReason,
                       String ip, String userAgent, String deviceId) {
        try {
            LoginLog row = new LoginLog();
            row.setUserId(userId);
            row.setAccount(maskPhone(phone));
            row.setLoginType(loginType);
            row.setResult(result);
            row.setFailReason(failReason);
            row.setIp(ip);
            row.setUserAgent(truncate(userAgent, 500));
            row.setDeviceId(truncate(deviceId, 128));
            row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            loginLogMapper.insert(row);
        } catch (Exception e) {
            log.error("登录日志落库失败 userId={} type={} result={}", userId, loginType, result, e);
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
