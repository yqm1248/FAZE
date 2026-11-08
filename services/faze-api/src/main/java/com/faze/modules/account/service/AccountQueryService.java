/*
 * 职责：账号认证状态与设备的只读查询接口（跨域供 security 层与账号页使用）。
 * 调用方：security/JwtAuthenticationFilter（认证快照）、
 *         modules/account/controller/AccountSecurityController（设备列表）。
 * 数据来源：app_user、user_device。
 * 注意：
 *   1. **每次请求查一次库**：这是 token 失效（改密 / 换绑 / 注销）能立即生效的代价。
 *      段 1 数据量小可接受；若将来成为瓶颈，按《后端代码规范》§12.1 用 @Component 实例字段
 *      做短期缓存（不许用 static），且必须想清楚「改了密码但缓存没失效」的窗口有多长。
 *   2. 查不到 / 已软删 / 状态不可用 / 版本号不一致，一律由调用方按 402003 处理——
 *      这四种情况对客户端是同一件事：这张令牌不能用了。
 */
package com.faze.modules.account.service;

import com.faze.modules.account.vo.AccountAuthSnapshot;
import com.faze.modules.account.vo.DeviceVO;
import java.util.List;
import java.util.Optional;

/**
 * 账号认证与设备查询。
 */
public interface AccountQueryService {

    /**
     * 按用户主键取认证快照。
     *
     * @param userId 用户主键
     * @return 快照；用户不存在时返回空
     */
    Optional<AccountAuthSnapshot> findAuthSnapshot(Long userId);

    /**
     * 取用户的实名认证状态（第 6 段 B3 新增）。
     *
     * <p>取值来自 `app_user.realname_status`：`none` / `pending` / `approved` / `rejected`。
     * 充值侧只关心「是不是 approved」，但这里返回原始值，由调用方定口径——
     * 别在查询层把 `pending` 折成 `none`，否则「审核中」和「没提交」的错误文案分不出来。</p>
     *
     * @param userId 用户主键
     * @return 状态值；用户不存在或已软删时返回空（调用方按未实名处理）
     */
    Optional<String> findRealnameStatus(Long userId);

    /**
     * 取用户的有效设备列表（未软删）。
     *
     * @param userId          用户主键
     * @param currentDeviceId 当前请求的 X-Device-Id，用于标记 current（可为 null）
     * @return 设备列表，按最后活跃时间倒序
     */
    List<DeviceVO> listDevices(Long userId, String currentDeviceId);

    /**
     * 批量取用户的公开信息快照（段 5 D1：工作台客户咨询列表要显示客户昵称与头像）。
     *
     * <p>只返回昵称 / 头像 / 脱敏手机号，**不含完整手机号与身份证**（L4 字段不进这个出口）。</p>
     *
     * @param userIds 用户主键集合（空集合返回空 Map）
     * @return userId → 快照；查不到的用户不出现在结果里
     */
    java.util.Map<Long, com.faze.modules.account.vo.UserBriefVO> mapBriefs(
            java.util.Collection<Long> userIds);

    /**
     * 取启用中（status=normal 且未软删）的用户 id，按 id 升序，最多 limit 条。
     *
     * <p>公告「全量」目标用。limit 由调用方分批（D5：每批 ≤500），
     * 这里不再分页——分页状态留在调用方，查询层只负责一次取一批。</p>
     *
     * @param limit 上限
     * @return 用户 id 列表
     */
    java.util.List<Long> listActiveUserIds(int limit);

    /**
     * 按角色取启用中的用户 id。
     *
     * @param role  角色：user / lawyer / admin
     * @param limit 上限
     * @return 用户 id 列表
     */
    java.util.List<Long> listUserIdsByRole(String role, int limit);

    /**
     * 按给定 id 列表取**仍可用**的用户 id（过滤已软删与非 normal）。
     *
     * <p>公告「指定用户」目标用：配置里可能写了已注销的 id，发送时要跳过。</p>
     *
     * @param ids   候选 id
     * @param limit 上限
     * @return 可用用户 id
     */
    java.util.List<Long> listUserIdsByIds(java.util.Collection<Long> ids, int limit);
}
