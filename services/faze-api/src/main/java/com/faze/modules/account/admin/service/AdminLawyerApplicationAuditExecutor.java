/*
 * 职责：A-05 律师入驻审核的执行器（approve / reject 各一个带审计注解的 public 方法）。
 * 调用方：modules/account/admin/service/AdminLawyerApplicationService#audit（按 action 路由）。
 * 数据来源：写 faze.lawyer_application、faze.lawyer、faze.lawyer_application_log、faze.app_user。
 * 注意：
 *   1. **approve 必须同时做四件事**（契约 A-05）：写 lawyer 一行、申请置 approved、
 *      追加 lawyer_application_log（operator_type=admin + 操作人 + form_data 快照）、
 *      app_user.role='lawyer'。缺任何一件都会让「入驻通过」变成半成品。
 *   2. 重复审核用 CAS（`eq(audit_status,'pending')`）拦；rows=0 → 401006。
 *   3. `license_number` 撞库 → 401005：先查一次给明确错误码，插入时再捕获
 *      DuplicateKeyException 兜底（软删行仍占用唯一索引，只查不捕获会漏）。
 *   4. **不写 lawyer_balance**（段 5）；`lawyer.balance_cents` 也不写（历史缓存字段，
 *      权威是 balance_flow）。
 *   5. 审计 action 是注解常量，所以 approve / reject 分两个方法；同类自调用不走代理，
 *      因此由 Service 持有本类 bean 调用（见 AdminVerificationAuditExecutor 注释第 1 条）。
 */
package com.faze.modules.account.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.admin.vo.AdminAuditResultVO;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.entity.LawyerApplication;
import com.faze.modules.account.entity.LawyerApplicationLog;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerApplicationLogMapper;
import com.faze.modules.account.mapper.LawyerApplicationMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.security.CurrentAdmin;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 律师入驻审核执行器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminLawyerApplicationAuditExecutor {

    private static final String MODULE = "account";
    private static final String TARGET_TYPE = "LAWYER_APPLICATION";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    /** app_user.status 的可用值（DB 存小写）。 */
    private static final String STATUS_ACTIVE = "active";
    private static final String OUT_APPROVED = "APPROVED";
    private static final String OUT_REJECTED = "REJECTED";
    private static final String OPERATOR_ADMIN = "admin";
    private static final String ROLE_LAWYER = "lawyer";
    private static final int REASON_MAX = 200;

    private final LawyerApplicationMapper applicationMapper;
    private final LawyerApplicationLogMapper applicationLogMapper;
    private final LawyerMapper lawyerMapper;
    private final AccountMapper accountMapper;
    private final JsonMapper jsonMapper;

    /**
     * 入驻申请通过。
     *
     * <p>副作用：申请 → approved；写 lawyer 一行；追加审核流水；app_user.role='lawyer'；
     * 写 operation_log（action=LAWYER_APPLICATION_APPROVED）。</p>
     *
     * @param id     申请主键
     * @param reason 备注（可空）
     * @return 审核结果
     * @throws BusinessException 401004 申请不存在 / 401005 执业证号已存在 / 401006 已审过
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "LAWYER_APPLICATION_APPROVED", targetType = TARGET_TYPE)
    public AdminAuditResultVO approve(Long id, String reason) {
        // 任务 1：存在性 + 状态前置判断。
        // 必须在撞库检查之前判状态：重复审核时 lawyer 表里已经有这一行，
        // 先查撞库会让「重复审核」错报成 401005（契约要求 401006），
        // 而且会让管理员以为「证号被别人占了」。
        LawyerApplication application = requireApplication(id);
        if (!STATUS_PENDING.equals(application.getAuditStatus())) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        // 任务 1.5：申请人账号必须仍然可用。
        // 2026-11-05 补：原来不判，实测「已注销用户（status=cancelled）的入驻申请仍可通过」——
        // 通过会写 lawyer 行并改 role，等于给一个已注销账号开通律师身份，属越权加工。
        // 状态语义：不存在/已注销 → 401004；封禁或其它非 active → 401006（状态非法）。
        assertApplicantActive(application.getUserId());
        // 任务 2：撞库检查（软删行也占唯一索引，所以插入时还要兜底捕获）。
        assertLicenseAvailable(application.getLicenseNumber());
        // 任务 3：CAS 迁移 pending → approved，抢到才继续往下写。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = applicationMapper.update(null, Wrappers.<LawyerApplication>lambdaUpdate()
                .set(LawyerApplication::getAuditStatus, STATUS_APPROVED)
                .set(LawyerApplication::getRejectReason, null)
                .set(LawyerApplication::getAuditedBy, CurrentAdmin.id())
                .set(LawyerApplication::getAuditedAt, now)
                .set(LawyerApplication::getUpdatedAt, now)
                .eq(LawyerApplication::getId, id)
                .eq(LawyerApplication::getAuditStatus, STATUS_PENDING));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        // 任务 4：写 lawyer 主数据（字段来自申请，不读请求体）。
        insertLawyer(application, now);
        // 任务 5：追加审核流水（只追加）。
        appendLog(application, "approve", STATUS_APPROVED, reason, now);
        // 任务 6：用户角色升级为律师。
        accountMapper.update(null, Wrappers.<AppUser>lambdaUpdate()
                .set(AppUser::getRole, ROLE_LAWYER)
                .set(AppUser::getUpdatedAt, now)
                .eq(AppUser::getId, application.getUserId())
                .isNull(AppUser::getDeletedAt));
        // 任务 7：审计 before/after。
        markAudit("LAWYER_APPLICATION_APPROVED", id, application.getUserId(),
                STATUS_PENDING, STATUS_APPROVED, reason);
        log.info("律师入驻已通过 applicationId={} userId={}", id, application.getUserId());
        return result(id, OUT_APPROVED);
    }

    /**
     * 入驻申请驳回。
     *
     * <p>副作用：申请 → rejected（写驳回理由）；追加审核流水；写 operation_log
     * （action=LAWYER_APPLICATION_REJECTED）。**不写 lawyer 表、不改 role**。</p>
     *
     * @param id     申请主键
     * @param reason 驳回理由（必填，≤200）
     * @return 审核结果
     * @throws BusinessException 401001 理由为空 / 401004 申请不存在 / 401006 已审过
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "LAWYER_APPLICATION_REJECTED", targetType = TARGET_TYPE)
    public AdminAuditResultVO reject(Long id, String reason) {
        String rejectReason = requireReason(reason);
        LawyerApplication application = requireApplication(id);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = applicationMapper.update(null, Wrappers.<LawyerApplication>lambdaUpdate()
                .set(LawyerApplication::getAuditStatus, STATUS_REJECTED)
                .set(LawyerApplication::getRejectReason, rejectReason)
                .set(LawyerApplication::getAuditedBy, CurrentAdmin.id())
                .set(LawyerApplication::getAuditedAt, now)
                .set(LawyerApplication::getUpdatedAt, now)
                .eq(LawyerApplication::getId, id)
                .eq(LawyerApplication::getAuditStatus, STATUS_PENDING));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        appendLog(application, "reject", STATUS_REJECTED, rejectReason, now);
        markAudit("LAWYER_APPLICATION_REJECTED", id, application.getUserId(),
                STATUS_PENDING, STATUS_REJECTED, rejectReason);
        log.info("律师入驻已驳回 applicationId={} userId={}", id, application.getUserId());
        return result(id, OUT_REJECTED);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 取申请，取不到就 401004。
     *
     * @param id 申请主键
     * @return 申请实体
     */
    private LawyerApplication requireApplication(Long id) {
        LawyerApplication application = applicationMapper.selectById(id);
        if (application == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return application;
    }

    /**
     * 申请人账号可用性检查（approve 前必须过）。
     *
     * <p>2026-11-05 补：原来不判，实测「已注销用户（status=cancelled）的入驻申请仍可通过」。
     * 通过会写 `lawyer` 行并把 `app_user.role` 改成 lawyer——等于给一个已注销账号
     * 开通律师身份，是越权加工。封禁（blocked）同理不放行。</p>
     *
     * @param userId 申请人主键
     * @throws BusinessException 401004 用户不存在或已软删 / 401006 账号非 active
     */
    private void assertApplicantActive(Long userId) {
        AppUser user = accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getId, userId)
                .isNull(AppUser::getDeletedAt));
        if (user == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
    }

    /**
     * 执业证号可用性检查。
     *
     * @param licenseNumber 执业证号
     * @throws BusinessException 401005 已被占用
     */
    private void assertLicenseAvailable(String licenseNumber) {
        if (!StringUtils.hasText(licenseNumber)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        Long count = lawyerMapper.selectCount(Wrappers.<Lawyer>lambdaQuery()
                .eq(Lawyer::getLicenseNumber, licenseNumber)
                .isNull(Lawyer::getDeletedAt));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.COMMON_ALREADY_EXISTS);
        }
    }

    /**
     * 写 lawyer 一行（字段全部取自已提交的申请，不读请求体）。
     *
     * @param application 申请实体
     * @param now         当前时间（UTC）
     * @throws BusinessException 401005 唯一键冲突（软删行占号时才会走到）
     */
    private void insertLawyer(LawyerApplication application, LocalDateTime now) {
        Lawyer lawyer = new Lawyer();
        lawyer.setUserId(application.getUserId());
        lawyer.setRealName(application.getRealName());
        lawyer.setLicenseNumber(application.getLicenseNumber());
        lawyer.setLicenseImage(application.getLicenseImage());
        lawyer.setLawFirm(application.getLawFirm());
        lawyer.setSpecialties(application.getSpecialties());
        lawyer.setBio(application.getBio());
        lawyer.setAuditStatus(STATUS_APPROVED);
        lawyer.setCreatedAt(now);
        lawyer.setUpdatedAt(now);
        try {
            lawyerMapper.insert(lawyer);
        } catch (DuplicateKeyException e) {
            // 软删行仍占用 uk_lawyer_license：先查一次拿不到，插入时才暴露
            log.warn("律师执业证号唯一键冲突 applicationId={}", application.getId());
            throw new BusinessException(ErrorCode.COMMON_ALREADY_EXISTS);
        }
    }

    /**
     * 追加审核流水（只追加，不修改）。
     *
     * @param application 申请实体
     * @param action      approve / reject
     * @param toStatus    目标状态
     * @param reason      理由 / 备注
     * @param now         当前时间（UTC）
     */
    private void appendLog(LawyerApplication application, String action, String toStatus,
                           String reason, LocalDateTime now) {
        LawyerApplicationLog logRow = new LawyerApplicationLog();
        logRow.setApplicationId(application.getId());
        logRow.setAction(action);
        logRow.setFromStatus(application.getAuditStatus());
        logRow.setToStatus(toStatus);
        logRow.setOperatorType(OPERATOR_ADMIN);
        logRow.setOperatorId(CurrentAdmin.id());
        logRow.setReason(reason);
        logRow.setSnapshot(application.getFormData());
        logRow.setCreatedAt(now);
        applicationLogMapper.insert(logRow);
    }

    /**
     * 驳回理由校验。
     *
     * @param reason 原始理由
     * @return 去空白后的理由
     * @throws BusinessException 401001
     */
    private String requireReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        String value = reason.trim();
        if (value.length() > REASON_MAX) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return value;
    }

    /**
     * 组装审核结果。
     *
     * @param id     申请主键
     * @param status 大写状态
     * @return 审核结果
     */
    private AdminAuditResultVO result(Long id, String status) {
        AdminAuditResultVO vo = new AdminAuditResultVO();
        vo.setId(id);
        vo.setStatus(status);
        return vo;
    }

    /**
     * 写审计上下文（before/after + 理由）。
     *
     * @param action 动作码
     * @param id     申请主键
     * @param userId 申请人
     * @param from   变更前状态
     * @param to     变更后状态
     * @param reason 理由 / 备注
     */
    private void markAudit(String action, Long id, Long userId, String from, String to, String reason) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("auditStatus", from);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("auditStatus", to);
        after.put("applicantUserId", userId);
        after.put("reason", reason);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        AuditContext.mark(MODULE, action, TARGET_TYPE, String.valueOf(id),
                jsonMapper.writeValueAsString(detail));
    }
}
