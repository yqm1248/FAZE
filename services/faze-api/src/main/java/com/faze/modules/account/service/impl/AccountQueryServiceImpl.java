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
     * 取用户的实名认证状态（第 6 段 B3）。
     *
     * <p>只读 `realname_status` 一列，不查 `realname_auth` 表：充值拦截只需要「是否已通过」，
     * 去查审核记录会把充值链路和审核链路耦合起来，且多一次 JOIN 没有收益。</p>
     *
     * @param userId 用户主键
     * @return 状态值；用户不存在或已软删时返回空
     */
    @Override
    public Optional<String> findRealnameStatus(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        AppUser user = accountMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null) {
            return Optional.empty();
        }
        return Optional.ofNullable(user.getRealnameStatus());
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

    /**
     * 批量取用户公开信息快照（段 5 D1）。
     *
     * <p>一次 `selectBatchIds` 取完，避免工作台列表逐条查库（N+1）。
     * 已软删的用户直接跳过，不返回半条数据。</p>
     *
     * @param userIds 用户主键集合
     * @return userId → 快照
     */
    @Override
    public java.util.Map<Long, com.faze.modules.account.vo.UserBriefVO> mapBriefs(
            java.util.Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Set<Long> ids = userIds.stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, com.faze.modules.account.vo.UserBriefVO> result = new java.util.HashMap<>();
        for (AppUser user : accountMapper.selectBatchIds(ids)) {
            if (user.getDeletedAt() != null) {
                continue;
            }
            result.put(user.getId(), new com.faze.modules.account.vo.UserBriefVO(
                    user.getId(), user.getNickname(), user.getAvatarUrl(), maskPhone(user.getPhone())));
        }
        return result;
    }

    /**
     * 取启用中的用户 id。
     *
     * @param limit 上限
     * @return 用户 id 列表
     */
    @Override
    public java.util.List<Long> listActiveUserIds(int limit) {
        return selectUserIds(null, null, limit);
    }

    /**
     * 按角色取启用中的用户 id。
     *
     * @param role  角色
     * @param limit 上限
     * @return 用户 id 列表
     */
    @Override
    public java.util.List<Long> listUserIdsByRole(String role, int limit) {
        return selectUserIds(role, null, limit);
    }

    /**
     * 按给定 id 取仍可用的用户 id。
     *
     * @param ids   候选 id
     * @param limit 上限
     * @return 可用用户 id
     */
    @Override
    public java.util.List<Long> listUserIdsByIds(java.util.Collection<Long> ids, int limit) {
        if (ids == null || ids.isEmpty()) {
            return java.util.List.of();
        }
        return selectUserIds(null, new java.util.ArrayList<>(ids), limit);
    }

    /**
     * 统一的用户 id 查询。
     *
     * <p>状态口径固定：`status='normal' AND deleted_at IS NULL`——
     * 公告只发给能正常收消息的账号，封禁/注销的不发（发了用户也看不到）。</p>
     *
     * @param role  角色（null 不限）
     * @param ids   id 白名单（null 不限）
     * @param limit 上限
     * @return id 列表
     */
    private java.util.List<Long> selectUserIds(String role, java.util.List<Long> ids, int limit) {
        int capped = Math.max(1, Math.min(limit, 10000));
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AppUser> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AppUser>()
                        .select(AppUser::getId)
                        .eq(AppUser::getStatus, "normal")
                        .isNull(AppUser::getDeletedAt)
                        .eq(role != null, AppUser::getRole, role)
                        .in(ids != null && !ids.isEmpty(), AppUser::getId, ids)
                        .orderByAsc(AppUser::getId);
        wrapper.last("LIMIT " + capped);
        java.util.List<Long> result = new java.util.ArrayList<>();
        for (AppUser user : accountMapper.selectList(wrapper)) {
            result.add(user.getId());
        }
        return result;
    }

    /**
     * 手机号脱敏（留前 3 后 4）。
     *
     * @param phone 手机号明文
     * @return 脱敏结果；空值返回 null
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return null;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
