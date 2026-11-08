/*
 * 职责：lawyer 表的**只读**跨域查询接口。
 * 调用方：modules/trade（下单取价、订单列表展示律师名、退款/接单校验承办律师）。
 * 数据来源：faze.lawyer。
 * 注意：
 *   1. 按《后端代码规范》§2.3，**跨域只允许查询接口**：这里只暴露 find*，不暴露任何写方法。
 *      律师资料的写入是 account 域自己的事（PC 审核、律师改价都走 account 的 Service）。
 *   2. 返回实体与段 1 的 `SysConfigQueryService#findRow` 口径一致——查询接口可以返回实体；
 *      调用方**不许**拿着它去改（没有 update 能力，也禁止自己 new 一个 Mapper 去改）。
 *   3. `findById` / `findByUserId` 都**已排除软删**（`deleted_at IS NULL`），
 *      调用方不用再加条件，也不许"为了查已清退律师"绕过本接口。
 */
package com.faze.modules.account.service;

import com.faze.modules.account.entity.Lawyer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 律师只读查询。
 */
public interface LawyerQueryService {

    /**
     * 按律师主键查（不含已软删）。
     *
     * @param lawyerId lawyer.id
     * @return 律师行；不存在或已软删时返回空
     */
    Optional<Lawyer> findById(Long lawyerId);

    /**
     * 按关联用户主键查（不含已软删）。
     *
     * <p>用于「当前登录用户是不是律师、是哪位律师」，律师侧接口的入口。</p>
     *
     * @param userId app_user.id
     * @return 律师行；该用户不是律师时返回空
     */
    Optional<Lawyer> findByUserId(Long userId);

    /**
     * 按主键批量查（列表页展示律师名/头像用，避免 N+1）。
     *
     * <p>顺序不保证；入参为空时返回空 Map，不查库。</p>
     *
     * @param lawyerIds 律师主键集合
     * @return 主键 → 律师行（已排除软删）
     */
    Map<Long, Lawyer> mapByIds(Collection<Long> lawyerIds);

    /**
     * 按主键批量取律师展示简要信息（E3 收藏列表 / 关注列表跨域组装用）。
     *
     * <p>与 {@link #mapByIds(Collection)} 的区别：这里返回的是**已转换好的展示投影**
     * （`specialties` 已从 JSON 字符串解析成数组），调用方不需要自己解析 JSON——
     * JSON 解析集中在 account 域，跨域调用方拿字符串自己解析会形成第二份解析逻辑。</p>
     *
     * <p>**包含 `userId`**：关注列表要用它去查 `app_user.last_login_at`（最近活跃时间）。
     * 不过滤 `audit_status=approved`：收藏/关注列表要能显示「已下架/已清退」的律师
     * （用户关注过的律师消失会让列表莫名少行），可见性由调用方决定。</p>
     *
     * @param lawyerIds 律师主键集合（空集合返回空 Map）
     * @return 主键 → 简要信息（已排除软删）
     */
    Map<Long, com.faze.modules.account.vo.LawyerBriefVO> mapBriefsByIds(Collection<Long> lawyerIds);

    /**
     * 按姓名或律所模糊匹配律师主键（PC 结算 / 提现列表的「律师」查询栏用）。
     *
     * <p>只返回主键：调用方要做的是 `IN` 过滤，拿到整行没用且会浪费带宽。
     * 关键词为空时返回空列表——**空列表意味着「没有匹配」而不是「全部」**，
     * 调用方要自己判空并跳过这个条件（写成 `IN ()` 会查出 0 行，语义就反了）。</p>
     *
     * @param keyword 姓名 / 律所关键词
     * @return 匹配的律师主键；无匹配或关键词为空时返回空列表
     */
    List<Long> searchIdsByKeyword(String keyword);

    /**
     * 按手机号模糊匹配律师主键（PC 提现列表的「律师手机号」查询栏用）。
     *
     * <p>手机号在 `app_user` 表、律师在 `lawyer` 表，本方法负责两跳（先按号找用户、再找律师），
     * 不把联表 SQL 泄漏给调用方。</p>
     *
     * @param keyword 手机号片段
     * @return 匹配的律师主键；关键词为空或无匹配时返回空列表
     */
    List<Long> searchIdsByPhone(String keyword);
}
