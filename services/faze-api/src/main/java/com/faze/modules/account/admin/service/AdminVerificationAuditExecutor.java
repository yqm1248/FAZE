/*
 * 职责：A-04 认证审核的执行器——四个「审核动作」各一个带审计注解的 public 方法。
 * 调用方：modules/account/admin/service/AdminVerificationService#audit（按 type + action 路由）。
 * 数据来源：faze.realname_auth / faze.enterprise_auth（写状态）+ faze.app_user（同步实名状态）。
 * 注意：
 *   1. **为什么单独一个 Executor 而不是在 Service 里写 if**：`@OperationLog` 的 action 是
 *      注解常量，四个动作（REALNAME_APPROVED / …）无法用同一方法的参数动态决定；
 *      而 Spring AOP 对「同类自调用」不生效（规范 §7.2 第 5 条）。所以路由放在 Service、
 *      执行放本类，两个 bean 之间的调用才走代理。
 *   2. 事务边界在本类的 public 方法上（一个审核动作 = 一个事务），rollbackFor 必须写 Exception。
 *   3. 状态迁移必须 CAS（`eq(status,'pending')`）：两个管理员同时点「通过」时，
 *      后到的 rows=0 → 401006，不会产生两次审计 + 两条 lawyer 风险。
 *   4. **不动 `app_user.role`**（契约 A-04 明确）：实名审核只同步 realname_status。
 *   5. 审计 detail 写 before/after 状态 + 结论 + 理由；理由进 detail 前由 AuditContext 承载，
 *      不进日志正文（日志只打 id 与结论）。
 */
package com.faze.modules.account.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.admin.vo.AdminAuditResultVO;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.EnterpriseAuth;
import com.faze.modules.account.entity.RealnameAuth;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.EnterpriseAuthMapper;
import com.faze.modules.account.mapper.RealnameAuthMapper;
import com.faze.security.CurrentAdmin;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 认证审核执行器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminVerificationAuditExecutor {

    private static final String MODULE = "account";
    private static final String TARGET_REALNAME = "REALNAME_AUTH";
    private static final String TARGET_ENTERPRISE = "ENTERPRISE_AUTH";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    private static final String OUT_APPROVED = "APPROVED";
    private static final String OUT_REJECTED = "REJECTED";
    private static final int REASON_MAX = 200;

    private final RealnameAuthMapper realnameAuthMapper;
    private final EnterpriseAuthMapper enterpriseAuthMapper;
    private final AccountMapper accountMapper;
    private final JsonMapper jsonMapper;

    /**
     * 实名认证通过。
     *
     * <p>副作用：realname_auth 状态 pending→approved、同步 app_user.realname_status=approved、
     * 写 operation_log（action=REALNAME_APPROVED）。</p>
     *
     * @param id     实名记录主键
     * @param reason 备注（可空）
     * @return 审核结果
     * @throws BusinessException 401004 记录不存在 / 401006 已审过
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "REALNAME_APPROVED", targetType = TARGET_REALNAME)
    public AdminAuditResultVO approveRealname(Long id, String reason) {
        RealnameAuth auth = requireRealname(id);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = realnameAuthMapper.update(null, Wrappers.<RealnameAuth>lambdaUpdate()
                .set(RealnameAuth::getStatus, STATUS_APPROVED)
                .set(RealnameAuth::getRejectReason, null)
                .set(RealnameAuth::getAuditedBy, CurrentAdmin.id())
                .set(RealnameAuth::getAuditedAt, now)
                .set(RealnameAuth::getUpdatedAt, now)
                .eq(RealnameAuth::getId, id)
                .eq(RealnameAuth::getStatus, STATUS_PENDING));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        syncUserRealnameStatus(auth.getUserId(), STATUS_APPROVED);
        markAudit("REALNAME_APPROVED", TARGET_REALNAME, id, auth.getUserId(),
                STATUS_PENDING, STATUS_APPROVED, reason);
        log.info("实名认证已通过 authId={} userId={}", id, auth.getUserId());
        return result(id, OUT_APPROVED);
    }

    /**
     * 实名认证驳回。
     *
     * <p>副作用：realname_auth 状态 pending→rejected、同步 app_user.realname_status=rejected、
     * 写 operation_log（action=REALNAME_REJECTED）。理由必填。</p>
     *
     * @param id     实名记录主键
     * @param reason 驳回理由（必填，≤200）
     * @return 审核结果
     * @throws BusinessException 401001 理由为空 / 401004 记录不存在 / 401006 已审过
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "REALNAME_REJECTED", targetType = TARGET_REALNAME)
    public AdminAuditResultVO rejectRealname(Long id, String reason) {
        String rejectReason = requireRejectReason(reason);
        RealnameAuth auth = requireRealname(id);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = realnameAuthMapper.update(null, Wrappers.<RealnameAuth>lambdaUpdate()
                .set(RealnameAuth::getStatus, STATUS_REJECTED)
                .set(RealnameAuth::getRejectReason, rejectReason)
                .set(RealnameAuth::getAuditedBy, CurrentAdmin.id())
                .set(RealnameAuth::getAuditedAt, now)
                .set(RealnameAuth::getUpdatedAt, now)
                .eq(RealnameAuth::getId, id)
                .eq(RealnameAuth::getStatus, STATUS_PENDING));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        syncUserRealnameStatus(auth.getUserId(), STATUS_REJECTED);
        markAudit("REALNAME_REJECTED", TARGET_REALNAME, id, auth.getUserId(),
                STATUS_PENDING, STATUS_REJECTED, rejectReason);
        log.info("实名认证已驳回 authId={} userId={}", id, auth.getUserId());
        return result(id, OUT_REJECTED);
    }

    /**
     * 企业认证通过。
     *
     * <p>副作用：enterprise_auth 状态 pending→approved、写 operation_log
     * （action=ENTERPRISE_APPROVED）。**不同步 app_user 任何字段**（契约 A-04）。</p>
     *
     * @param id     企业认证记录主键
     * @param reason 备注（可空）
     * @return 审核结果
     * @throws BusinessException 401004 记录不存在 / 401006 已审过
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "ENTERPRISE_APPROVED", targetType = TARGET_ENTERPRISE)
    public AdminAuditResultVO approveEnterprise(Long id, String reason) {
        EnterpriseAuth auth = requireEnterprise(id);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = enterpriseAuthMapper.update(null, Wrappers.<EnterpriseAuth>lambdaUpdate()
                .set(EnterpriseAuth::getStatus, STATUS_APPROVED)
                .set(EnterpriseAuth::getRejectReason, null)
                .set(EnterpriseAuth::getAuditedBy, CurrentAdmin.id())
                .set(EnterpriseAuth::getAuditedAt, now)
                .set(EnterpriseAuth::getUpdatedAt, now)
                .eq(EnterpriseAuth::getId, id)
                .eq(EnterpriseAuth::getStatus, STATUS_PENDING));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        markAudit("ENTERPRISE_APPROVED", TARGET_ENTERPRISE, id, auth.getUserId(),
                STATUS_PENDING, STATUS_APPROVED, reason);
        log.info("企业认证已通过 authId={} userId={}", id, auth.getUserId());
        return result(id, OUT_APPROVED);
    }

    /**
     * 企业认证驳回。
     *
     * <p>副作用：enterprise_auth 状态 pending→rejected、写 operation_log
     * （action=ENTERPRISE_REJECTED）。理由必填。</p>
     *
     * @param id     企业认证记录主键
     * @param reason 驳回理由（必填，≤200）
     * @return 审核结果
     * @throws BusinessException 401001 理由为空 / 401004 记录不存在 / 401006 已审过
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "ENTERPRISE_REJECTED", targetType = TARGET_ENTERPRISE)
    public AdminAuditResultVO rejectEnterprise(Long id, String reason) {
        String rejectReason = requireRejectReason(reason);
        EnterpriseAuth auth = requireEnterprise(id);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = enterpriseAuthMapper.update(null, Wrappers.<EnterpriseAuth>lambdaUpdate()
                .set(EnterpriseAuth::getStatus, STATUS_REJECTED)
                .set(EnterpriseAuth::getRejectReason, rejectReason)
                .set(EnterpriseAuth::getAuditedBy, CurrentAdmin.id())
                .set(EnterpriseAuth::getAuditedAt, now)
                .set(EnterpriseAuth::getUpdatedAt, now)
                .eq(EnterpriseAuth::getId, id)
                .eq(EnterpriseAuth::getStatus, STATUS_PENDING));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE);
        }
        markAudit("ENTERPRISE_REJECTED", TARGET_ENTERPRISE, id, auth.getUserId(),
                STATUS_PENDING, STATUS_REJECTED, rejectReason);
        log.info("企业认证已驳回 authId={} userId={}", id, auth.getUserId());
        return result(id, OUT_REJECTED);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 取实名记录，取不到就 401004。
     *
     * @param id 主键
     * @return 记录
     */
    private RealnameAuth requireRealname(Long id) {
        RealnameAuth auth = realnameAuthMapper.selectById(id);
        if (auth == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return auth;
    }

    /**
     * 取企业记录，取不到就 401004。
     *
     * @param id 主键
     * @return 记录
     */
    private EnterpriseAuth requireEnterprise(Long id) {
        EnterpriseAuth auth = enterpriseAuthMapper.selectById(id);
        if (auth == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return auth;
    }

    /**
     * 同步 app_user.realname_status（实名审核的唯一用户侧后果）。
     *
     * <p>用户已注销时更新 0 行：不报错——审核动作本身要留下，注销用户不必回写。</p>
     *
     * @param userId 用户主键
     * @param status approved / rejected
     */
    private void syncUserRealnameStatus(Long userId, String status) {
        if (userId == null) {
            return;
        }
        accountMapper.update(null, Wrappers.<AppUser>lambdaUpdate()
                .set(AppUser::getRealnameStatus, status)
                .set(AppUser::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(AppUser::getId, userId)
                .isNull(AppUser::getDeletedAt));
    }

    /**
     * 驳回理由校验。
     *
     * @param reason 原始理由
     * @return 去空白后的理由
     * @throws BusinessException 401001 为空或超长
     */
    private String requireRejectReason(String reason) {
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
     * @param id     记录主键
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
     * 写审计上下文（before/after + 结论 + 理由）。
     *
     * @param action   动作码
     * @param target   目标类型
     * @param id       目标主键
     * @param userId   关联用户
     * @param from     变更前状态
     * @param to       变更后状态
     * @param reason   备注 / 理由
     */
    private void markAudit(String action, String target, Long id, Long userId,
                           String from, String to, String reason) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", from);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", to);
        after.put("userId", userId);
        after.put("reason", reason);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        AuditContext.mark(MODULE, action, target, String.valueOf(id),
                jsonMapper.writeValueAsString(detail));
    }
}
