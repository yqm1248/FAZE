/*
 * 职责：实名状态的**唯一派生入口**——把 `realname_auth` 的审核结论算成「用户当前的实名状态」。
 * 调用方：AuthService（登录响应）、ProfileService（资料）、AccountQueryServiceImpl（充值门禁）、
 *         AdminUserService / AdminLawyerService（后台列表与详情）。
 * 数据来源：`realname_auth` 一张表；**不读 `app_user.realname_status`**。
 * 注意：
 *   1. **真相源是 `realname_auth`，不是 `app_user.realname_status`**（DRIFT-015）。
 *      后者是冗余缓存列，只在 auth 变更时同步维护，**任何读取路径都不得再信任它**。
 *      踩过的坑：提交实名只 upsert auth、不写列，于是同一用户、同一时刻
 *      实名页显示「审核中」（读 auth）而我的页显示「未实名」（读列）；
 *      user 3 更极端，auth=rejected 而列=approved，两页结论相反。
 *   2. 口径固定四值且**小写**（none / pending / approved / rejected），与库内一致；
 *      转大写枚举是 Converter 的事，本类不做展示转换——展示口径改了只该改一处。
 *   3. 「没有记录」= none。不区分「从未提交」与「记录被清」——该表不做软删，
 *      真被清了就是没有，编不出第五种语义。
 *   4. auth.status 若为脏值（不在三值内），同样归 none：派生必须**总值**，
 *      返回一个调用方没见过的值会让下游 switch 走进 default 分支。
 *   5. 本类**只读**：不写库、不开事务、不缓存。缓存列由写路径维护
 *      （RealnameService#submit / AdminVerificationAuditExecutor#syncUserRealnameStatus）。
 */
package com.faze.modules.account.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.account.entity.RealnameAuth;
import com.faze.modules.account.mapper.RealnameAuthMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 实名状态派生器。
 */
@Component
@RequiredArgsConstructor
public class RealnameStatusResolver {

    /** 从未提交、或记录状态不可识别时的口径。 */
    public static final String STATUS_NONE = "none";
    /** 已提交待审核。 */
    public static final String STATUS_PENDING = "pending";
    /** 审核通过。 */
    public static final String STATUS_APPROVED = "approved";
    /** 审核驳回。 */
    public static final String STATUS_REJECTED = "rejected";

    private final RealnameAuthMapper realnameAuthMapper;

    /**
     * 取单个用户的实名状态。
     *
     * @param userId 用户主键；null 直接返回 none（不查库）
     * @return none / pending / approved / rejected，**永不为 null**
     */
    public String resolve(Long userId) {
        if (userId == null) {
            return STATUS_NONE;
        }
        RealnameAuth row = realnameAuthMapper.selectOne(Wrappers.<RealnameAuth>lambdaQuery()
                .eq(RealnameAuth::getUserId, userId));
        return resolveFromAuth(row);
    }

    /**
     * 批量取实名状态（后台列表用，一次 IN 查询）。
     *
     * <p>返回的 Map **保证每个入参 userId 都有值**：查不到记录的填 {@link #STATUS_NONE}，
     * 调用方不需要再写 `getOrDefault`——两处写兜底就一定会有一处忘。</p>
     *
     * @param userIds 用户主键集合；空集合返回空 Map
     * @return userId → 状态（小写口径）
     */
    public Map<Long, String> resolveBatch(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(userIds.size() * 2);
        for (Long id : userIds) {
            result.put(id, STATUS_NONE);
        }
        for (RealnameAuth row : realnameAuthMapper.selectList(Wrappers.<RealnameAuth>lambdaQuery()
                .in(RealnameAuth::getUserId, userIds))) {
            result.put(row.getUserId(), resolveFromAuth(row));
        }
        return result;
    }

    /**
     * 从已查询到的实名记录推状态（后台详情已有实体时省一次查询）。
     *
     * @param row 实名记录；null 表示从未提交
     * @return none / pending / approved / rejected
     */
    public String resolveFromAuth(RealnameAuth row) {
        if (row == null || row.getStatus() == null) {
            return STATUS_NONE;
        }
        return switch (row.getStatus().toLowerCase(Locale.ROOT)) {
            case STATUS_PENDING -> STATUS_PENDING;
            case STATUS_APPROVED -> STATUS_APPROVED;
            case STATUS_REJECTED -> STATUS_REJECTED;
            default -> STATUS_NONE;
        };
    }
}
