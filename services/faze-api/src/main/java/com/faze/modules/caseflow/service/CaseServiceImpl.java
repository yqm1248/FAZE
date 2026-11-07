/*
 * 职责：案件中心业务实现——立案、我的案件、详情、进度时间轴、律师推进节点。
 * 调用方：AppCaseController。
 * 数据来源：legal_case / case_progress / account 的 LawyerQueryService（跨域只读）。
 * 注意：
 *   1. **归属条件进 SQL**：用户侧按 `user_id`，律师侧按 `lawyer_id`，不在 Java 里先查再判（§11.2）。
 *   2. 状态迁移用 `LegalCaseMapper.casUpdateStatus`（CAS），影响行数 0 即拒。
 *   3. 立案幂等：Redis `faze:case:idem:{userId}:{key}` → caseId，TTL 24h；命中直接返回首次结果。
 *   4. 不在事务内做远程调用（本类没有远程调用）。
 */
package com.faze.modules.caseflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.infra.seq.BizNoGenerator;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.caseflow.bo.CaseCreateBO;
import com.faze.modules.caseflow.bo.CaseProgressBO;
import com.faze.modules.caseflow.bo.CaseQueryBO;
import com.faze.modules.caseflow.entity.CaseDelivery;
import com.faze.modules.caseflow.entity.CaseProgress;
import com.faze.modules.caseflow.entity.LegalCase;
import com.faze.modules.caseflow.enums.CaseStatus;
import com.faze.modules.caseflow.enums.CaseType;
import com.faze.modules.caseflow.enums.DeliveryStatus;
import com.faze.modules.caseflow.mapper.CaseDeliveryMapper;
import com.faze.modules.caseflow.mapper.CaseProgressMapper;
import com.faze.modules.caseflow.mapper.LegalCaseMapper;
import com.faze.modules.caseflow.rule.CaseStateMachine;
import com.faze.modules.caseflow.vo.CaseDetailVO;
import com.faze.modules.caseflow.vo.CaseProgressVO;
import com.faze.modules.caseflow.vo.CaseRowVO;
import com.faze.modules.caseflow.vo.CaseSubmitVO;
import com.faze.modules.trade.enums.OperatorRole;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 案件业务实现。
 */
@Service
@Slf4j
public class CaseServiceImpl implements CaseService {

    /** 立案幂等键前缀。 */
    private static final String IDEM_PREFIX = "faze:case:idem:";

    /** 幂等结果保留 24 小时（与订单幂等一致）。 */
    private static final Duration IDEM_TTL = Duration.ofHours(24);

    /** 「待我处理」聚合的 3 个状态（§三 B3 第 1 条：pending 是聚合语义，不是单值）。 */
    private static final Set<String> PENDING_STATUSES = Set.of(
            CaseStatus.FILED.getCode(), CaseStatus.REJECTED.getCode(), CaseStatus.ACCEPTED.getCode());

    /** 合法节点编码（§三 B4 第 4 条：至少 8 个，不许自由文本）。 */
    private static final Set<String> NODE_CODES = Set.of(
            "submitted", "review", "accepted", "supplement", "hearing", "judgment", "effective", "closed");

    private final LegalCaseMapper legalCaseMapper;
    private final CaseProgressMapper caseProgressMapper;
    private final CaseDeliveryMapper caseDeliveryMapper;
    private final LawyerQueryService lawyerQueryService;
    private final BizNoGenerator bizNoGenerator;
    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    /**
     * 构造器注入。
     *
     * @param legalCaseMapper    案件 Mapper
     * @param caseProgressMapper 进度 Mapper
     * @param caseDeliveryMapper 送达 Mapper（只用于算未签收红点）
     * @param lawyerQueryService account 域只读律师查询
     * @param bizNoGenerator     单号生成器
     * @param redis              Redis（幂等）
     * @param jsonMapper         Jackson 3 映射器（JSON 列序列化）
     */
    public CaseServiceImpl(LegalCaseMapper legalCaseMapper,
                           CaseProgressMapper caseProgressMapper,
                           CaseDeliveryMapper caseDeliveryMapper,
                           LawyerQueryService lawyerQueryService,
                           BizNoGenerator bizNoGenerator,
                           StringRedisTemplate redis,
                           JsonMapper jsonMapper) {
        this.legalCaseMapper = legalCaseMapper;
        this.caseProgressMapper = caseProgressMapper;
        this.caseDeliveryMapper = caseDeliveryMapper;
        this.lawyerQueryService = lawyerQueryService;
        this.bizNoGenerator = bizNoGenerator;
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CaseSubmitVO create(Long userId, CaseCreateBO bo, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "缺少 Idempotency-Key");
        }
        // 任务 1：幂等命中直接返回首次结果（不产生第二单）
        String idemKey = IDEM_PREFIX + userId + ":" + idempotencyKey;
        String cached = redis.opsForValue().get(idemKey);
        if (cached != null) {
            LegalCase exist = legalCaseMapper.selectById(Long.valueOf(cached));
            if (exist != null && Objects.equals(exist.getUserId(), userId)) {
                return toSubmitVO(exist);
            }
        }

        // 任务 2：生成单号 + 冻结四步表单快照
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LegalCase entity = new LegalCase();
        entity.setCaseNo(bizNoGenerator.nextCaseNo());
        entity.setUserId(userId);
        entity.setTitle(buildTitle(bo));
        entity.setCaseType(bo.getCaseType());
        entity.setDescription(bo.getStatement());
        entity.setStatus(CaseStatus.FILED.getCode());
        entity.setAmountCents(bo.getAmountCents());
        entity.setFormSnapshot(jsonMapper.writeValueAsString(bo));
        entity.setEvidenceUrls(bo.getEvidenceUrls() == null
                ? null : jsonMapper.writeValueAsString(bo.getEvidenceUrls()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        legalCaseMapper.insert(entity);

        // 任务 3：写第一条进度（submitted），与立案同事务
        insertProgress(entity.getId(), 1, "submitted", "材料已提交",
                "用户提交立案材料，等待平台形式审查", userId, OperatorRole.USER, now);

        // 任务 4：记幂等结果（失败不回滚业务，幂等只是防重）
        try {
            redis.opsForValue().set(idemKey, String.valueOf(entity.getId()), IDEM_TTL);
        } catch (RuntimeException e) {
            log.warn("立案幂等键写入失败 caseId={}", entity.getId(), e);
        }
        return toSubmitVO(entity);
    }

    @Override
    public PageResult<CaseRowVO> list(Long userId, CaseQueryBO query) {
        var wrapper = Wrappers.<LegalCase>lambdaQuery()
                .eq(LegalCase::getUserId, userId)
                .isNull(LegalCase::getDeletedAt)
                .orderByDesc(LegalCase::getCreatedAt);
        String filter = query.getFilter() == null ? "all" : query.getFilter();
        switch (filter) {
            case "pending" -> wrapper.in(LegalCase::getStatus, PENDING_STATUSES);
            case "in_progress" -> wrapper.eq(LegalCase::getStatus, CaseStatus.IN_PROGRESS.getCode());
            case "closed" -> wrapper.eq(LegalCase::getStatus, CaseStatus.CLOSED.getCode());
            default -> { /* all：不加状态条件 */ }
        }
        var page = legalCaseMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(query.getPageNum(), query.getPageSize()),
                wrapper);
        List<CaseRowVO> rows = page.getRecords().stream().map(this::toRowVO).toList();
        return PageResult.of(rows, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Override
    public CaseDetailVO detail(Long userId, Long caseId) {
        LegalCase entity = findAccessible(userId, caseId);
        LegalCase e = entity;
        CaseDetailVO vo = new CaseDetailVO();
        vo.setId(e.getId());
        vo.setCaseNo(e.getCaseNo());
        vo.setTitle(e.getTitle());
        vo.setCaseType(e.getCaseType());
        vo.setStatus(e.getStatus());
        vo.setCourtName(e.getCourtName());
        vo.setCourtCaseNo(e.getCourtCaseNo());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setRejectReason(e.getRejectReason());
        vo.setLawyerId(e.getLawyerId());
        fillLawyer(vo, e.getLawyerId(), userId);
        return vo;
    }

    @Override
    public List<CaseProgressVO> listProgress(Long userId, Long caseId) {
        findAccessible(userId, caseId);
        return caseProgressMapper.selectList(Wrappers.<CaseProgress>lambdaQuery()
                        .eq(CaseProgress::getCaseId, caseId)
                        .orderByAsc(CaseProgress::getStep))
                .stream().map(this::toProgressVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CaseProgressVO addProgress(Long userId, Long caseId, CaseProgressBO bo) {
        // 任务 1：带归属条件取案件——不是本案承办律师就查不到（不区分「不存在」与「不是你的」）
        Lawyer lawyer = lawyerQueryService.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CASE_FORBIDDEN, "当前用户不是律师"));
        LegalCase entity = legalCaseMapper.selectOne(Wrappers.<LegalCase>lambdaQuery()
                .eq(LegalCase::getId, caseId)
                .eq(LegalCase::getLawyerId, lawyer.getId())
                .isNull(LegalCase::getDeletedAt));
        if (entity == null) {
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND, "案件不存在或非本人承办");
        }
        if (!NODE_CODES.contains(bo.getNodeCode())) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "非法节点编码: " + bo.getNodeCode());
        }

        // 任务 2：按节点决定是否需要迁移状态（状态机校验，非法即拒）
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CaseStatus current = CaseStatus.of(entity.getStatus());
        if ("closed".equals(bo.getNodeCode()) && current != CaseStatus.CLOSED) {
            CaseStateMachine.assertCanTransit(current, CaseStatus.CLOSED, OperatorRole.LAWYER);
            int rows = legalCaseMapper.casUpdateStatus(caseId, current.getCode(),
                    CaseStatus.CLOSED.getCode(), null, now);
            if (rows == 0) {
                throw new BusinessException(ErrorCode.CASE_STATE_INVALID, "状态已被其他操作修改");
            }
        }
        if ("accepted".equals(bo.getNodeCode()) && bo.getCourtCaseNo() != null && !bo.getCourtCaseNo().isBlank()) {
            legalCaseMapper.updateCourtCaseNo(caseId, lawyer.getId(), bo.getCourtCaseNo(), now);
        }

        // 任务 3：写进度节点（step = 当前最大 + 1，唯一索引兜底）
        int nextStep = maxStep(caseId) + 1;
        CaseProgress progress = insertProgress(caseId, nextStep, bo.getNodeCode(),
                bo.getTitle() == null ? defaultNodeTitle(bo.getNodeCode()) : bo.getTitle(),
                bo.getDescription(), userId, OperatorRole.LAWYER, now);
        return toProgressVO(progress);
    }

    /**
     * 取案件并校验可访问（用户本人或承办律师）。
     *
     * @param userId 当前用户
     * @param caseId 案件 id
     * @return 案件实体
     */
    private LegalCase findAccessible(Long userId, Long caseId) {
        LegalCase entity = legalCaseMapper.selectOne(Wrappers.<LegalCase>lambdaQuery()
                .eq(LegalCase::getId, caseId)
                .isNull(LegalCase::getDeletedAt));
        if (entity == null) {
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND);
        }
        if (Objects.equals(entity.getUserId(), userId)) {
            return entity;
        }
        Lawyer lawyer = lawyerQueryService.findByUserId(userId).orElse(null);
        if (lawyer != null && Objects.equals(entity.getLawyerId(), lawyer.getId())) {
            return entity;
        }
        throw new BusinessException(ErrorCode.CASE_FORBIDDEN);
    }

    /**
     * 取当前最大 step。
     *
     * @param caseId 案件 id
     * @return 最大 step；无节点返回 0
     */
    private int maxStep(Long caseId) {
        List<CaseProgress> list = caseProgressMapper.selectList(Wrappers.<CaseProgress>lambdaQuery()
                .eq(CaseProgress::getCaseId, caseId)
                .orderByDesc(CaseProgress::getStep));
        return list.isEmpty() ? 0 : list.get(0).getStep();
    }

    /**
     * 插入一条进度。
     *
     * @return 已带 id 的实体
     */
    private CaseProgress insertProgress(Long caseId, int step, String nodeCode, String title,
                                        String description, Long operatorId, OperatorRole role,
                                        LocalDateTime now) {
        CaseProgress p = new CaseProgress();
        p.setCaseId(caseId);
        p.setStep(step);
        p.setNodeCode(nodeCode);
        p.setTitle(title);
        p.setDescription(description);
        p.setOperatorId(operatorId);
        p.setOperatorRole(role.getCode());
        p.setCreatedAt(now);
        caseProgressMapper.insert(p);
        return p;
    }

    /**
     * 组装列表行。
     *
     * @param e 案件实体
     * @return 列表 VO
     */
    private CaseRowVO toRowVO(LegalCase e) {
        CaseRowVO vo = new CaseRowVO();
        vo.setId(e.getId());
        vo.setCaseNo(e.getCaseNo());
        vo.setTitle(e.getTitle());
        vo.setStatus(e.getStatus());
        if (e.getLawyerId() != null) {
            lawyerQueryService.findById(e.getLawyerId()).ifPresent(l -> {
                vo.setLawyerName(l.getRealName());
                vo.setLawyerAvatarUrl(l.getAvatarUrl());
            });
        }
        List<CaseProgress> latest = caseProgressMapper.selectList(Wrappers.<CaseProgress>lambdaQuery()
                .eq(CaseProgress::getCaseId, e.getId())
                .orderByDesc(CaseProgress::getStep));
        vo.setLatestEventAt(latest.isEmpty() ? e.getCreatedAt() : latest.get(0).getCreatedAt());
        // 未签收送达份数：C4 的红点依据（签收后自然归零，不需要额外状态字段）
        vo.setUnreadDeliveryCount(Math.toIntExact(caseDeliveryMapper.selectCount(
                Wrappers.<CaseDelivery>lambdaQuery()
                        .eq(CaseDelivery::getCaseId, e.getId())
                        .eq(CaseDelivery::getStatus, DeliveryStatus.PENDING.getCode()))));
        return vo;
    }

    /**
     * 填律师信息与 canAdvance。
     */
    private void fillLawyer(CaseDetailVO vo, Long lawyerId, Long userId) {
        if (lawyerId == null) {
            vo.setCanAdvance(false);
            return;
        }
        boolean self = lawyerQueryService.findByUserId(userId)
                .map(l -> Objects.equals(l.getId(), lawyerId)).orElse(false);
        vo.setCanAdvance(self);
        lawyerQueryService.findById(lawyerId).ifPresent(l -> {
            vo.setLawyerName(l.getRealName());
            vo.setLawyerAvatarUrl(l.getAvatarUrl());
        });
    }

    private CaseSubmitVO toSubmitVO(LegalCase e) {
        CaseSubmitVO vo = new CaseSubmitVO();
        vo.setId(e.getId());
        vo.setCaseNo(e.getCaseNo());
        vo.setSubmittedAt(e.getCreatedAt());
        return vo;
    }

    private CaseProgressVO toProgressVO(CaseProgress p) {
        CaseProgressVO vo = new CaseProgressVO();
        vo.setId(p.getId());
        vo.setStep(p.getStep());
        vo.setNodeCode(p.getNodeCode());
        vo.setTitle(p.getTitle());
        vo.setDescription(p.getDescription());
        vo.setOperatorId(p.getOperatorId());
        vo.setOperatorRole(p.getOperatorRole());
        vo.setCreatedAt(p.getCreatedAt());
        return vo;
    }

    private String buildTitle(CaseCreateBO bo) {
        if (bo.getDisputeType() != null && !bo.getDisputeType().isBlank()) {
            return bo.getDisputeType();
        }
        CaseType type = CaseType.ofOrNull(bo.getCaseType());
        return (type == null ? "案件" : type.getLabel() + "案件");
    }

    private String defaultNodeTitle(String nodeCode) {
        return switch (nodeCode) {
            case "submitted" -> "材料已提交";
            case "review" -> "法院审查";
            case "accepted" -> "受理并立案";
            case "supplement" -> "材料补正";
            case "hearing" -> "开庭审理";
            case "judgment" -> "一审判决";
            case "effective" -> "判决生效";
            case "closed" -> "结案";
            default -> nodeCode;
        };
    }
}
