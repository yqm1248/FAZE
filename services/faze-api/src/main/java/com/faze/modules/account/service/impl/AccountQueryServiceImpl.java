/*
 * 职责：AccountQueryService 的实现。
 * 调用方：Spring 依赖注入（security/JwtAuthenticationFilter、AccountSecurityController）。
 * 数据来源：faze.app_user、faze.user_device。
 * 注意：
 *   1. selectById 会连软删的行一起读回来，所以必须自己判断 deletedAt——
 *      段 1 没有配 MP 的逻辑删除拦截器（见 AppUser 类注释第 2 条）。
 *   2. token_version 在库里是 NOT NULL DEFAULT 0，不会为 null；万一为 null 按 0 处理，
 *      保守地让老 token（ver=0）继续可用，而不是把所有用户踢下线。
 *   3. 设备列表**不返回 push cid**（见 DeviceVO 注释）。
 */
package com.faze.modules.account.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.UserDevice;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.UserDeviceMapper;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.vo.AccountAuthSnapshot;
import com.faze.modules.account.vo.DeviceVO;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 账号认证与设备查询实现。
 */
@Service
@RequiredArgsConstructor
public class AccountQueryServiceImpl implements AccountQueryService {

    private final AccountMapper accountMapper;
    private final UserDeviceMapper userDeviceMapper;

    /**
     * 按用户主键取认证快照。
     *
     * @param userId 用户主键
     * @return 快照；用户不存在时为空
     */
    @Override
    public Optional<AccountAuthSnapshot> findAuthSnapshot(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        AppUser user = accountMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        int version = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        return Optional.of(new AccountAuthSnapshot(
                user.getId(),
                user.getRole(),
                user.getStatus(),
                version,
                user.getDeletedAt() != null));
    }

    /**
     * 取设备列表。
     *
     * @param userId          用户主键
     * @param currentDeviceId 当前设备标识
     * @return 设备列表（最后活跃时间倒序）
     */
    @Override
    public List<DeviceVO> listDevices(Long userId, String currentDeviceId) {
        if (userId == null) {
            return List.of();
        }
        List<UserDevice> rows = userDeviceMapper.selectList(Wrappers.<UserDevice>lambdaQuery()
                .eq(UserDevice::getUserId, userId)
                .isNull(UserDevice::getDeletedAt)
                .orderByDesc(UserDevice::getLastActiveAt));
        return rows.stream().map(row -> toDeviceVO(row, currentDeviceId)).toList();
    }

    /**
     * 设备实体 → 出参。
     *
     * @param row             设备实体
     * @param currentDeviceId 当前设备标识
     * @return 设备出参
     */
    private DeviceVO toDeviceVO(UserDevice row, String currentDeviceId) {
        DeviceVO vo = new DeviceVO();
        vo.setDeviceId(row.getDeviceId());
        vo.setPlatform(row.getPlatform());
        vo.setDeviceModel(row.getDeviceModel());
        vo.setOsVersion(row.getOsVersion());
        vo.setAppVersion(row.getAppVersion());
        vo.setLastActiveAt(row.getLastActiveAt());
        vo.setCurrent(currentDeviceId != null && Objects.equals(currentDeviceId, row.getDeviceId()));
        return vo;
    }
}
