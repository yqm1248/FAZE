/*
 * 职责：A-06 律师列表与详情（只读）。
 * 调用方：modules/account/admin/controller/AdminLawyerController。
 * 数据来源：faze.lawyer（主）+ faze.app_user（关联用户摘要）+ 本地存储（60 秒签名 URL）。
 * 注意：
 *   1. **每个 lawyer / app_user 查询都带 `deleted_at IS NULL`**（段 1 无 MP 逻辑删除拦截器）。
 *   2. 本段只做查询与详情：违规 / 限流 / 下架 / 清退四档在段 7，不在这里出现。
 *   3. 卡号只出后 4 位（脱敏在 Converter）；完整卡号不进响应、不进日志。
 *   4. 排序固定 `created_at DESC, id DESC`（同秒内用 id 兜底，翻页不重不漏）。
 */
package com.faze.modules.account.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.util.Pages;
import com.faze.modules.account.admin.bo.AdminLawyerQueryBO;
import com.faze.modules.account.admin.converter.AdminLawyerConverter;
import com.faze.modules.account.admin.vo.AdminLawyerDetailVO;
import com.faze.modules.account.admin.vo.AdminLawyerRowVO;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.account.service.RealnameStatusResolver;
import com.faze.modules.account.service.UploadService;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 律师查询服务（管理端）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminLawyerService {

    private static final Set<String> ALLOWED_AUDIT_STATUS = Set.of("pending", "approved", "rejected");

    private final LawyerMapper lawyerMapper;
    private final AccountMapper accountMapper;
    private final AdminLawyerConverter converter;
    private final RealnameStatusResolver realnameStatusResolver;
    private final UploadService uploadService;

    /**
     * 分页查询律师。
     *
     * @param query 查询条件（keyword 模糊姓名/律所、region、auditStatus、时间区间）
     * @return 分页结果
     * @throws BusinessException 401001 auditStatus 非法
     */
    public IPage<AdminLawyerRowVO> list(AdminLawyerQueryBO query) {
        String auditStatus = normalizeAuditStatus(query.getAuditStatus());
        IPage<Lawyer> page = lawyerMapper.selectPage(
                Pages.of(query.getPageNum(), query.getPageSize()),
                Wrappers.<Lawyer>lambdaQuery()
                        .isNull(Lawyer::getDeletedAt)
                        .and(StringUtils.hasText(query.getKeyword()), wrapper -> wrapper
                                .like(Lawyer::getRealName, query.getKeyword())
                                .or()
                                .like(Lawyer::getLawFirm, query.getKeyword()))
                        .eq(StringUtils.hasText(query.getRegion()), Lawyer::getRegion, query.getRegion())
                        .eq(auditStatus != null, Lawyer::getAuditStatus, auditStatus)
                        .ge(query.getBeginTime() != null, Lawyer::getCreatedAt, query.getBeginTime())
                        .le(query.getEndTime() != null, Lawyer::getCreatedAt, query.getEndTime())
                        .orderByDesc(Lawyer::getCreatedAt)
                        .orderByDesc(Lawyer::getId));
        return page.convert(converter::toLawyerRow);
    }

    /**
     * 查询律师详情（含服务设置与关联用户摘要）。
     *
     * @param id 律师主键
     * @return 详情
     * @throws BusinessException 401004 律师不存在或已清退
     */
    public AdminLawyerDetailVO detail(Long id) {
        Lawyer lawyer = lawyerMapper.selectOne(Wrappers.<Lawyer>lambdaQuery()
                .eq(Lawyer::getId, id)
                .isNull(Lawyer::getDeletedAt));
        if (lawyer == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        AppUser user = lawyer.getUserId() == null ? null : accountMapper.selectOne(
                Wrappers.<AppUser>lambdaQuery()
                        .eq(AppUser::getId, lawyer.getUserId())
                        .isNull(AppUser::getDeletedAt));
        String licenseImageUrl = uploadService.downloadUrlForAdmin(lawyer.getLicenseImage());
        // 用户摘要里的实名状态走派生（DRIFT-015），不读 app_user.realname_status 缓存列
        String realnameStatus = user == null ? null : realnameStatusResolver.resolve(user.getId());
        return converter.toLawyerDetail(lawyer, user, licenseImageUrl, realnameStatus);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 规范化审核状态（空表示全部）。
     *
     * @param auditStatus 原始状态（大写）
     * @return 库内小写状态；空值返回 null
     * @throws BusinessException 401001 不在白名单
     */
    private String normalizeAuditStatus(String auditStatus) {
        if (!StringUtils.hasText(auditStatus)) {
            return null;
        }
        String value = auditStatus.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_AUDIT_STATUS.contains(value)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return value;
    }
}
