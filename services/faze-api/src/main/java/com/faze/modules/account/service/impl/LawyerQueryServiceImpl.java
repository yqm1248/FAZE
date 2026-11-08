/*
 * 职责：LawyerQueryService 的实现（律师只读查询）。
 * 调用方：Spring 依赖注入（modules/trade 下单与订单展示）。
 * 数据来源：faze.lawyer（单表，无联查）。
 * 注意：
 *   1. 两个查询都必须带 `deleted_at IS NULL`：段 1/2 没有 MP 逻辑删除拦截器，
 *      漏掉会把已清退律师查出来当正常律师接单。
 *   2. 查不到返回 `Optional.empty()`，不抛异常——「这个用户不是律师」是正常分支
 *      （普通用户下单调律师列表时也会走到）。
 *   3. 不做任何写操作，也不缓存：律师改价/审核状态变化要立刻生效（下单取价必须用最新值）。
 */
package com.faze.modules.account.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.account.service.LawyerQueryService;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 律师只读查询实现。
 */
@Service
@RequiredArgsConstructor
public class LawyerQueryServiceImpl implements LawyerQueryService {

    private final LawyerMapper lawyerMapper;
    private final AccountMapper accountMapper;
    private final com.faze.modules.account.converter.LawyerBrowseConverter lawyerBrowseConverter;

    /**
     * 按律师主键查。
     *
     * @param lawyerId lawyer.id，可为空
     * @return 律师行；为空或已软删时返回空
     */
    @Override
    public Optional<Lawyer> findById(Long lawyerId) {
        if (lawyerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lawyerMapper.selectOne(Wrappers.<Lawyer>lambdaQuery()
                .eq(Lawyer::getId, lawyerId)
                .isNull(Lawyer::getDeletedAt)));
    }

    /**
     * 按关联用户主键查。
     *
     * @param userId app_user.id，可为空
     * @return 律师行；该用户不是律师时返回空
     */
    @Override
    public Optional<Lawyer> findByUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lawyerMapper.selectOne(Wrappers.<Lawyer>lambdaQuery()
                .eq(Lawyer::getUserId, userId)
                .isNull(Lawyer::getDeletedAt)));
    }

    /**
     * 按主键批量查。
     *
     * <p>一次 `IN` 查询，避免列表页按行查律师（N+1）。返回的 Map 只含查到的行，
     * 调用方要按「可能没有律师」处理缺行。</p>
     *
     * @param lawyerIds 律师主键集合，可为空
     * @return 主键 → 律师行
     */
    @Override
    public Map<Long, Lawyer> mapByIds(Collection<Long> lawyerIds) {
        if (lawyerIds == null || lawyerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return lawyerMapper.selectList(Wrappers.<Lawyer>lambdaQuery()
                        .in(Lawyer::getId, lawyerIds)
                        .isNull(Lawyer::getDeletedAt))
                .stream()
                .filter(lawyer -> lawyer.getId() != null)
                .collect(Collectors.toMap(Lawyer::getId, Function.identity(), (left, right) -> left));
    }

    /**
     * 按主键批量取律师展示简要信息（E3 收藏/关注列表用）。
     *
     * <p>一次 IN 查询 + 复用本域 Converter 解析 specialties；跨域调用方拿到的已是数组。</p>
     *
     * @param lawyerIds 律师主键集合
     * @return 主键 → 简要信息
     */
    @Override
    public Map<Long, com.faze.modules.account.vo.LawyerBriefVO> mapBriefsByIds(Collection<Long> lawyerIds) {
        Map<Long, Lawyer> rows = mapByIds(lawyerIds);
        if (rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, com.faze.modules.account.vo.LawyerBriefVO> result = new java.util.HashMap<>(rows.size());
        for (Map.Entry<Long, Lawyer> entry : rows.entrySet()) {
            Lawyer lawyer = entry.getValue();
            List<String> specialties = lawyerBrowseConverter.toCard(lawyer).getSpecialties();
            result.put(entry.getKey(), new com.faze.modules.account.vo.LawyerBriefVO(
                    lawyer.getId(),
                    lawyer.getUserId(),
                    lawyer.getRealName(),
                    lawyer.getLawFirm(),
                    lawyer.getRegion(),
                    lawyer.getPracticeYears(),
                    specialties,
                    lawyer.getOnlineStatus(),
                    lawyer.getRating(),
                    lawyer.getAvatarUrl()));
        }
        return result;
    }

    /**
     * 按姓名或律所模糊匹配律师主键。
     *
     * <p>两条 `LIKE` 用 OR 组合：姓名命中或律所命中都算，
     * 与 PC 用户习惯一致（财务记得住律所名但不一定记得律师全名）。</p>
     *
     * @param keyword 关键词
     * @return 律师主键列表；关键词为空或无匹配时为空列表
     */
    @Override
    public List<Long> searchIdsByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = keyword.trim();
        return lawyerMapper.selectList(Wrappers.<Lawyer>lambdaQuery()
                        .select(Lawyer::getId)
                        .and(wrapper -> wrapper.like(Lawyer::getRealName, kw)
                                .or()
                                .like(Lawyer::getLawFirm, kw)
                                .or()
                                .like(Lawyer::getLicenseNumber, kw))
                        .isNull(Lawyer::getDeletedAt))
                .stream()
                .map(Lawyer::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 按手机号模糊匹配律师主键（两跳：手机号在 app_user、律师在 lawyer）。
     *
     * @param keyword 手机号片段
     * @return 律师主键列表；关键词为空或无匹配时为空列表
     */
    @Override
    public List<Long> searchIdsByPhone(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = keyword.trim();
        List<Long> userIds = accountMapper.selectList(Wrappers.<AppUser>lambdaQuery()
                        .select(AppUser::getId)
                        .like(AppUser::getPhone, kw)
                        .isNull(AppUser::getDeletedAt))
                .stream()
                .map(AppUser::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return lawyerMapper.selectList(Wrappers.<Lawyer>lambdaQuery()
                        .select(Lawyer::getId)
                        .in(Lawyer::getUserId, userIds)
                        .isNull(Lawyer::getDeletedAt))
                .stream()
                .map(Lawyer::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
