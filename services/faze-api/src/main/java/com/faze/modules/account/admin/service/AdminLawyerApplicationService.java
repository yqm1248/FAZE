/*
 * 职责：A-05 律师入驻审核（队列查询、四步详情、审核路由）。
 * 调用方：modules/account/admin/controller/AdminLawyerApplicationController。
 * 数据来源：faze.lawyer_application、faze.lawyer_application_log、faze.app_user +
 *           本地存储（60 秒签名 URL）。
 * 注意：
 *   1. `status` 为空时**默认只看 pending**（待审队列）；`draft` 不进队列，
 *      传 draft / 其它非法值一律 401001（契约只开放 PENDING / APPROVED / REJECTED）。
 *   2. 查询参数 `applyType` 本段**没有对应字段**，Controller 不接收、Service 不处理；
 *      接收了却不读会让前端误以为筛选生效（规范 §6.5 的反例）。
 *   3. 审核写库在 AdminLawyerApplicationAuditExecutor（四个动作名要分开注解），
 *      本类只负责路由，不直接改状态。
 *   4. 影像只出 60 秒签名 URL，对象 key 不进响应 / 日志。
 */
package com.faze.modules.account.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.util.Pages;
import com.faze.modules.account.admin.bo.AdminLawyerApplicationAuditBO;
import com.faze.modules.account.admin.bo.AdminLawyerApplicationQueryBO;
import com.faze.modules.account.admin.converter.AdminLawyerConverter;
import com.faze.modules.account.admin.vo.AdminAuditResultVO;
import com.faze.modules.account.admin.vo.AdminLawyerApplicationDetailVO;
import com.faze.modules.account.admin.vo.AdminLawyerApplicationRowVO;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.LawyerApplication;
import com.faze.modules.account.entity.LawyerApplicationLog;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerApplicationLogMapper;
import com.faze.modules.account.mapper.LawyerApplicationMapper;
import com.faze.modules.account.service.UploadService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 律师入驻审核服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminLawyerApplicationService {

    private static final String STATUS_PENDING = "pending";
    private static final String ACTION_APPROVE = "approve";
    private static final Set<String> ALLOWED_STATUS = Set.of("pending", "approved", "rejected");

    private final LawyerApplicationMapper applicationMapper;
    private final LawyerApplicationLogMapper applicationLogMapper;
    private final AccountMapper accountMapper;
    private final AdminLawyerApplicationAuditExecutor auditExecutor;
    private final AdminLawyerConverter converter;
    private final UploadService uploadService;

    /**
     * 分页查询入驻申请队列（提交时间倒序）。
     *
     * @param query 查询条件（status 空 = 只看 PENDING）
     * @return 分页结果
     * @throws BusinessException 401001 status 非法
     */
    public IPage<AdminLawyerApplicationRowVO> list(AdminLawyerApplicationQueryBO query) {
        String status = normalizeStatus(query.getStatus());
        IPage<LawyerApplication> page = applicationMapper.selectPage(
                Pages.of(query.getPageNum(), query.getPageSize()),
                Wrappers.<LawyerApplication>lambdaQuery()
                        .eq(LawyerApplication::getAuditStatus, status)
                        .ge(query.getBeginTime() != null, LawyerApplication::getCreatedAt, query.getBeginTime())
                        .le(query.getEndTime() != null, LawyerApplication::getCreatedAt, query.getEndTime())
                        .orderByDesc(LawyerApplication::getCreatedAt)
                        .orderByDesc(LawyerApplication::getId));
        Map<Long, AppUser> applicants = loadApplicants(page.getRecords());
        return page.convert(application -> converter.toApplicationRow(
                application, applicants.get(application.getUserId())));
    }

    /**
     * 查询入驻申请详情（四步表单 + 审核流水 + 执业证签名 URL）。
     *
     * @param id 申请主键
     * @return 详情
     * @throws BusinessException 401004 申请不存在
     */
    public AdminLawyerApplicationDetailVO detail(Long id) {
        LawyerApplication application = applicationMapper.selectById(id);
        if (application == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        AppUser applicant = findApplicant(application.getUserId());
        List<LawyerApplicationLog> logs = applicationLogMapper.selectList(
                Wrappers.<LawyerApplicationLog>lambdaQuery()
                        .eq(LawyerApplicationLog::getApplicationId, id)
                        .orderByAsc(LawyerApplicationLog::getCreatedAt)
                        .orderByAsc(LawyerApplicationLog::getId));
        String licenseImageUrl = uploadService.downloadUrlForAdmin(application.getLicenseImage());
        return converter.toApplicationDetail(application, applicant, logs, licenseImageUrl);
    }

    /**
     * 审核入驻申请（通过 / 驳回）。
     *
     * @param id      申请主键
     * @param request 审核请求（action 必填，reject 时 reason 必填）
     * @return 审核结果（id + 大写状态）
     * @throws BusinessException 401001 理由缺失 / 401004 不存在 / 401005 证号撞库 / 401006 已审过
     */
    public AdminAuditResultVO audit(Long id, AdminLawyerApplicationAuditBO request) {
        return ACTION_APPROVE.equalsIgnoreCase(request.getAction())
                ? auditExecutor.approve(id, request.getReason())
                : auditExecutor.reject(id, request.getReason());
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 规范化状态：空 → pending；其它大写名 → 库内小写并校验。
     *
     * @param status 原始状态
     * @return 库内小写状态
     * @throws BusinessException 401001 不在白名单
     */
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return STATUS_PENDING;
        }
        String value = status.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_STATUS.contains(value)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return value;
    }

    /**
     * 批量加载申请人（一次 IN 查询）。
     *
     * @param applications 当前页申请
     * @return userId → 用户；已注销用户不在结果里
     */
    private Map<Long, AppUser> loadApplicants(List<LawyerApplication> applications) {
        if (applications == null || applications.isEmpty()) {
            return Map.of();
        }
        List<Long> userIds = applications.stream()
                .map(LawyerApplication::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<AppUser> users = accountMapper.selectList(Wrappers.<AppUser>lambdaQuery()
                .in(AppUser::getId, userIds)
                .isNull(AppUser::getDeletedAt));
        return users.stream().collect(Collectors.toMap(AppUser::getId, user -> user, (left, right) -> left));
    }

    /**
     * 取单个申请人。
     *
     * @param userId 用户主键
     * @return 用户；不存在或已注销时返回 null
     */
    private AppUser findApplicant(Long userId) {
        if (userId == null) {
            return null;
        }
        return accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getId, userId)
                .isNull(AppUser::getDeletedAt));
    }
}
