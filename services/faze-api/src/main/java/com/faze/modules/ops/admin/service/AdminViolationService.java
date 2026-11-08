/*
 * 职责：B4 违规记录的登记与查询（律师违规 + 用户违规列表）。
 * 调用方：modules/ops 的 AdminViolationController。
 * 数据来源：faze.user_violation；律师 id → 用户 id 的换算走 account 域的 LawyerQueryService。
 * 注意：
 *   1. **律师违规的路径参数是 `lawyer.id`，落库的是 `app_user.id`**：
 *      `user_violation.user_id` 语义是「被处罚主体」，律师与普通用户共用这张表，
 *      靠 `user_type` 区分。换算必须走 account 域的查询接口（跨域读只走 QueryService）。
 *   2. **本段只写记录，不执行处罚**（工作流 B4 第 6 条）：`restrict` / `suspend` 的
 *      「真的限流 / 真的暂停接单」由段 2/5 的业务读 `user_violation` 判定；
 *      `ban` 的登录拦截走 account 域（封禁接口改 `status`）。
 *   3. 列表按 `start_at DESC`，分页 clamp 到 100。
 */
package com.faze.modules.ops.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.ops.admin.bo.ViolationCreateBO;
import com.faze.modules.ops.admin.vo.ViolationVO;
import com.faze.modules.ops.constants.OpsAuditAction;
import com.faze.modules.ops.entity.UserViolation;
import com.faze.modules.ops.mapper.UserViolationMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 违规记录服务。
 */
@Slf4j
@Service
public class AdminViolationService {

    /** 分页上限。 */
    private static final int PAGE_SIZE_MAX = 100;

    /** 违规 Mapper。 */
    private final UserViolationMapper userViolationMapper;

    /** 律师查询（跨域只读）。 */
    private final LawyerQueryService lawyerQueryService;

    /** JSON。 */
    private final JsonMapper jsonMapper;

    /**
     * 构造服务。
     *
     * @param userViolationMapper 违规 Mapper
     * @param lawyerQueryService  律师查询
     * @param jsonMapper          JSON
     */
    public AdminViolationService(UserViolationMapper userViolationMapper,
                                 LawyerQueryService lawyerQueryService,
                                 JsonMapper jsonMapper) {
        this.userViolationMapper = userViolationMapper;
        this.lawyerQueryService = lawyerQueryService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 登记律师违规。
     *
     * <p>副作用：写一行 user_violation（user_type=lawyer）；写审计 LAWYER_VIOLATION。</p>
     *
     * @param lawyerId 律师主键（lawyer.id）
     * @param bo       入参
     * @return 违规记录 id
     */
    @OperationLog(module = OpsAuditAction.MODULE_OPS, action = "LAWYER_VIOLATION",
            targetType = OpsAuditAction.TARGET_USER_VIOLATION)
    @Transactional(rollbackFor = Exception.class)
    public Long createLawyerViolation(Long lawyerId, ViolationCreateBO bo) {
        Lawyer lawyer = lawyerQueryService.findById(lawyerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_NOT_FOUND, "律师不存在: " + lawyerId));

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserViolation row = new UserViolation();
        row.setUserId(lawyer.getUserId());
        row.setUserType("lawyer");
        row.setViolationType(bo.getViolationType());
        row.setLevel(bo.getLevel());
        row.setReason(bo.getReason());
        Map<String, Object> evidence = new LinkedHashMap<>(2);
        evidence.put("lawyerId", lawyerId);
        row.setEvidence(jsonMapper.writeValueAsString(evidence));
        row.setStartAt(now);
        row.setEndAt(bo.getEndAt());
        row.setOperatorId(currentAdminId());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        userViolationMapper.insert(row);

        AuditContext.mark(null, null, null, String.valueOf(row.getId()),
                "{\"after\":" + jsonMapper.writeValueAsString(Map.of(
                        "userId", lawyer.getUserId(),
                        "level", bo.getLevel(),
                        "violationType", bo.getViolationType())) + "}");
        log.info("律师违规已登记 lawyerId={} userId={} level={}", lawyerId, lawyer.getUserId(), bo.getLevel());
        return row.getId();
    }

    /**
     * 分页查询某用户的违规记录（时间倒序）。
     *
     * @param userId   用户主键
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public PageResult<ViolationVO> listByUser(Long userId, Integer pageNum, Integer pageSize) {
        int num = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, PAGE_SIZE_MAX);
        IPage<UserViolation> page = userViolationMapper.selectPage(new Page<>(num, size),
                Wrappers.<UserViolation>lambdaQuery()
                        .eq(UserViolation::getUserId, userId)
                        .orderByDesc(UserViolation::getId));
        List<ViolationVO> rows = new ArrayList<>(page.getRecords().size());
        for (UserViolation entity : page.getRecords()) {
            rows.add(toVO(entity));
        }
        return PageResult.of(rows, page.getTotal(), num, size);
    }

    /**
     * 实体转 VO。
     *
     * @param entity 违规记录
     * @return VO
     */
    private ViolationVO toVO(UserViolation entity) {
        ViolationVO vo = new ViolationVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setUserType(entity.getUserType());
        vo.setViolationType(entity.getViolationType());
        vo.setLevel(entity.getLevel());
        vo.setLevelLabel(levelLabel(entity.getLevel()));
        vo.setReason(entity.getReason());
        vo.setStartAt(entity.getStartAt());
        vo.setEndAt(entity.getEndAt());
        vo.setOperatorId(entity.getOperatorId());
        vo.setRevokedAt(entity.getRevokedAt());
        vo.setRevokeReason(entity.getRevokeReason());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /**
     * 处罚级别中文名。
     *
     * @param level code
     * @return 中文名；未知值原样返回
     */
    private String levelLabel(String level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case "warning" -> "警告";
            case "restrict" -> "限流";
            case "suspend" -> "暂停接单";
            case "ban" -> "封禁";
            default -> level;
        };
    }

    /**
     * 当前管理员 id。
     *
     * @return 管理员 id；取不到返回 null
     */
    private Long currentAdminId() {
        AuditContext.AuditInfo info = AuditContext.current();
        return info == null ? null : info.getOperatorId();
    }
}
