/*
 * 职责：A-04 实名 / 企业认证审核（合并队列查询、详情、审核路由）。
 * 调用方：modules/account/admin/controller/AdminVerificationController。
 * 数据来源：faze.realname_auth、faze.enterprise_auth、faze.app_user（昵称）+
 *           本地存储（60 秒签名 URL）。
 * 注意：
 *   1. 两张表**合并后分页**在 Mapper 的 UNION SQL 里完成（见 AdminVerificationMapper.xml），
 *      本类只做参数规范化与昵称补齐，不做内存合并——内存合并翻页必错。
 *   2. 审核动作**不在本类写库**：路由到 AdminVerificationAuditExecutor 的四个方法，
 *      因为 `@OperationLog` 的 action 是注解常量，四个动作名必须各占一个方法（见 Executor 注释）。
 *   3. 影像只出 60 秒签名 URL；对象 key 绝不进响应、不进日志。
 *   4. 完整身份证号本段不提供（契约：掩码展示），需要看全号属后续段的单独权限。
 */
package com.faze.modules.account.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.util.Pages;
import com.faze.modules.account.admin.bo.AdminVerificationAuditBO;
import com.faze.modules.account.admin.bo.AdminVerificationQueryBO;
import com.faze.modules.account.admin.converter.AdminAccountConverter;
import com.faze.modules.account.admin.vo.AdminAuditResultVO;
import com.faze.modules.account.admin.vo.AdminVerificationDetailVO;
import com.faze.modules.account.admin.vo.AdminVerificationRowVO;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.EnterpriseAuth;
import com.faze.modules.account.entity.RealnameAuth;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.AdminVerificationMapper;
import com.faze.modules.account.mapper.EnterpriseAuthMapper;
import com.faze.modules.account.mapper.RealnameAuthMapper;
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
 * 认证审核服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminVerificationService {

    private static final String TYPE_REALNAME = "realname";
    private static final String TYPE_ENTERPRISE = "enterprise";
    private static final String ACTION_APPROVE = "approve";
    private static final Set<String> ALLOWED_TYPES = Set.of(TYPE_REALNAME, TYPE_ENTERPRISE);
    private static final Set<String> ALLOWED_STATUS = Set.of("pending", "approved", "rejected");

    private final AdminVerificationMapper verificationMapper;
    private final RealnameAuthMapper realnameAuthMapper;
    private final EnterpriseAuthMapper enterpriseAuthMapper;
    private final AccountMapper accountMapper;
    private final AdminVerificationAuditExecutor auditExecutor;
    private final AdminAccountConverter converter;
    private final UploadService uploadService;

    /**
     * 分页查询认证队列（两张表合并，提交时间倒序）。
     *
     * @param query 查询条件（type / status / 时间区间 / 分页）
     * @return 分页结果
     * @throws BusinessException 401001 type 或 status 不在枚举内
     */
    public IPage<AdminVerificationRowVO> list(AdminVerificationQueryBO query) {
        AdminVerificationQueryBO normalized = normalize(query);
        Page<AdminVerificationRowVO> page = Pages.of(query.getPageNum(), query.getPageSize());
        // UNION 派生表的自动 count 不可控，总数由专用 SQL 算（见 Mapper 注释第 4 条）
        page.setSearchCount(false);
        IPage<AdminVerificationRowVO> result = verificationMapper.selectVerificationPage(page, normalized);
        fillNicknames(result.getRecords());
        result.setTotal(verificationMapper.countVerifications(normalized));
        return result;
    }

    /**
     * 查询认证详情。
     *
     * <p>影像字段返回 60 秒签名 URL；key 为 null 时字段为 null。</p>
     *
     * @param id   认证记录主键
     * @param type 认证类型：realname / enterprise
     * @return 认证详情
     * @throws BusinessException 401001 type 非法 / 401004 记录不存在
     */
    public AdminVerificationDetailVO detail(Long id, String type) {
        String normalizedType = normalizeType(type);
        if (TYPE_REALNAME.equals(normalizedType)) {
            return realnameDetail(id);
        }
        return enterpriseDetail(id);
    }

    /**
     * 审核认证（approve / reject）。
     *
     * <p>路由到 AuditExecutor 对应动作；状态机与审计都在执行器里。</p>
     *
     * @param id      认证记录主键
     * @param request 审核请求（type 必填、action 必填、reject 时 reason 必填）
     * @return 审核结果（id + 大写状态）
     * @throws BusinessException 401001 理由缺失 / 401004 记录不存在 / 401006 已审过
     */
    public AdminAuditResultVO audit(Long id, AdminVerificationAuditBO request) {
        String type = normalizeType(request.getType());
        boolean approve = ACTION_APPROVE.equalsIgnoreCase(request.getAction());
        if (TYPE_REALNAME.equals(type)) {
            return approve
                    ? auditExecutor.approveRealname(id, request.getReason())
                    : auditExecutor.rejectRealname(id, request.getReason());
        }
        return approve
                ? auditExecutor.approveEnterprise(id, request.getReason())
                : auditExecutor.rejectEnterprise(id, request.getReason());
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 实名详情 + 两个签名 URL。
     *
     * @param id 主键
     * @return 详情
     */
    private AdminVerificationDetailVO realnameDetail(Long id) {
        RealnameAuth auth = realnameAuthMapper.selectById(id);
        if (auth == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        String frontUrl = uploadService.downloadUrlForAdmin(auth.getIdCardFront());
        String backUrl = uploadService.downloadUrlForAdmin(auth.getIdCardBack());
        return converter.toRealnameDetail(auth, findNickname(auth.getUserId()), frontUrl, backUrl);
    }

    /**
     * 企业详情 + 营业执照签名 URL。
     *
     * @param id 主键
     * @return 详情
     */
    private AdminVerificationDetailVO enterpriseDetail(Long id) {
        EnterpriseAuth auth = enterpriseAuthMapper.selectById(id);
        if (auth == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        String licenseUrl = uploadService.downloadUrlForAdmin(auth.getLicenseImage());
        return converter.toEnterpriseDetail(auth, findNickname(auth.getUserId()), licenseUrl);
    }

    /**
     * 规范化并校验查询条件（入参大写 → 库内小写）。
     *
     * @param query 原始条件
     * @return 规范化后的条件
     * @throws BusinessException 401001 type / status 非法
     */
    private AdminVerificationQueryBO normalize(AdminVerificationQueryBO query) {
        AdminVerificationQueryBO normalized = new AdminVerificationQueryBO();
        normalized.setType(StringUtils.hasText(query.getType())
                ? normalizeType(query.getType()) : null);
        normalized.setStatus(normalizeStatus(query.getStatus()));
        normalized.setBeginTime(query.getBeginTime());
        normalized.setEndTime(query.getEndTime());
        return normalized;
    }

    /**
     * 规范化认证类型。
     *
     * @param type 原始类型
     * @return realname / enterprise（小写）
     * @throws BusinessException 401001 不在白名单
     */
    private String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        String value = type.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(value)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return value;
    }

    /**
     * 规范化状态（空表示不过滤）。
     *
     * @param status 原始状态（大小写不敏感）
     * @return 库内小写状态；空值返回 null
     * @throws BusinessException 401001 不在白名单
     */
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String value = status.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_STATUS.contains(value)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return value;
    }

    /**
     * 批量补齐队列行的用户昵称（一次 IN 查询，最多 100 行）。
     *
     * <p>查询带 `deleted_at IS NULL`：已注销用户的昵称不补（显示为空），
     * 审核结论仍按记录状态判断，不依赖昵称。</p>
     *
     * @param rows 队列行（原地填充 userNickname）
     */
    private void fillNicknames(List<AdminVerificationRowVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> userIds = rows.stream()
                .map(AdminVerificationRowVO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return;
        }
        List<AppUser> users = accountMapper.selectList(Wrappers.<AppUser>lambdaQuery()
                .select(AppUser::getId, AppUser::getNickname)
                .in(AppUser::getId, userIds)
                .isNull(AppUser::getDeletedAt));
        Map<Long, String> nicknameById = users.stream()
                .collect(Collectors.toMap(AppUser::getId, user -> user.getNickname() == null ? "" : user.getNickname(),
                        (left, right) -> left));
        rows.forEach(row -> row.setUserNickname(nicknameById.get(row.getUserId())));
    }

    /**
     * 取单个用户昵称。
     *
     * @param userId 用户主键
     * @return 昵称；用户不存在或已注销时返回 null
     */
    private String findNickname(Long userId) {
        if (userId == null) {
            return null;
        }
        AppUser user = accountMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .select(AppUser::getId, AppUser::getNickname)
                .eq(AppUser::getId, userId)
                .isNull(AppUser::getDeletedAt));
        return user == null ? null : user.getNickname();
    }
}
