/*
 * 职责：登录设备的登记与心跳（B5 设备列表的数据来源）。
 * 调用方：modules/account/service/AuthService（登录 / 注册 / 短信登录成功后）。
 * 数据来源：user_device。
 * 注意：
 *   1. **只记客户端自报的 deviceId**，不采集硬件指纹（IMEI / IDFA / MAC）。后者属个人信息，
 *      且应用商店审核会查；合规上「能不用就不用」。
 *   2. platform 列是 NOT NULL，客户端没传时按 User-Agent 粗判，判不出就写 `unknown`——
 *      写假值（比如硬写 android）会让设备列表对 iOS 用户展示错误信息。
 *   3. 同一 (user_id, device_id) 由唯一键 uk_device_user_device 保证只有一行，
 *      所以这里是「有则更新活跃时间、无则插入」。并发下靠唯一键兜底，
 *      插入冲突按「已被别人插入」处理，不抛给调用方（登录不能因为设备表冲突而失败）。
 *   4. 登记失败**不影响登录**：设备列表是辅助功能，登录是主流程。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.account.entity.UserDevice;
import com.faze.modules.account.mapper.UserDeviceMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceService {

    private static final String PLATFORM_UNKNOWN = "unknown";
    private static final int PLATFORM_MAX_LENGTH = 10;

    private final UserDeviceMapper userDeviceMapper;

    /**
     * 登记/刷新一次设备活跃。
     *
     * @param userId       用户主键
     * @param deviceId     客户端设备标识（可为空，为空则跳过）
     * @param userAgent    User-Agent，用于粗判平台（可为空）
     */
    public void touch(Long userId, String deviceId, String userAgent) {
        if (userId == null || deviceId == null || deviceId.isBlank()) {
            return;
        }
        try {
            UserDevice existing = userDeviceMapper.selectOne(Wrappers.<UserDevice>lambdaQuery()
                    .eq(UserDevice::getUserId, userId)
                    .eq(UserDevice::getDeviceId, deviceId)
                    .isNull(UserDevice::getDeletedAt));
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            if (existing != null) {
                UserDevice patch = new UserDevice();
                patch.setId(existing.getId());
                patch.setLastActiveAt(now);
                patch.setUpdatedAt(now);
                userDeviceMapper.updateById(patch);
                return;
            }
            UserDevice row = new UserDevice();
            row.setUserId(userId);
            row.setDeviceId(deviceId);
            row.setPlatform(detectPlatform(userAgent));
            row.setLastActiveAt(now);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            userDeviceMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发首登：另一个请求刚插了同一 (user, device)，按已存在处理即可
            log.debug("设备记录并发插入冲突，已忽略 userId={} deviceId={}", userId, abbreviate(deviceId));
        } catch (Exception e) {
            // 设备列表是辅助功能，登记失败不能影响登录
            log.error("设备登记失败 userId={} deviceId={}", userId, abbreviate(deviceId), e);
        }
    }

    /**
     * 按 User-Agent 粗判平台。
     *
     * @param userAgent UA 字符串
     * @return android / ios / unknown
     */
    private String detectPlatform(String userAgent) {
        if (userAgent == null) {
            return PLATFORM_UNKNOWN;
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("android")) {
            return "android";
        }
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ios")) {
            return "ios";
        }
        return PLATFORM_UNKNOWN;
    }

    /**
     * 截断设备标识用于日志（不打印完整指纹）。
     *
     * @param deviceId 设备标识
     * @return 前 8 位 + 省略号
     */
    private String abbreviate(String deviceId) {
        if (deviceId == null) {
            return "";
        }
        return deviceId.length() <= 8 ? deviceId : deviceId.substring(0, 8) + "...";
    }
}
