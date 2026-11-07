/*
 * 职责：PC 案件管理（A-19）的业务——列表、详情、立案审核、进度纠错。
 * 调用方：modules/caseflow/admin/controller/AdminCaseController。
 * 数据来源：legal_case / case_progress / account 的 LawyerQueryService。
 * 注意：
 *   1. 域内使用、只有一个实现，**不建接口**（后端规范 §2.4 判据）。
 *   2. 审核必须写审计（`@OperationLog` 标在本类方法上，不标 Controller），动作码 `CASE_AUDIT`。
 *   3. 状态迁移一律经 CaseStateMachine + CAS；非法迁移抛 407002。
 */
package com.faze.modules.caseflow.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.caseflow.bo.AdminCaseQueryBO;
import com.faze.modules.caseflow.bo.AdminProgressBO;
import com.faze.modules.caseflow.bo.CaseAuditBO;
import com.faze.modules.caseflow.entity.CaseProgress;
import com.faze.modules.caseflow.entity.LegalCase;
import com.faze.modules.caseflow.enums.CaseStatus;
import com.faze.modules.caseflow.mapper.CaseProgressMapper;
import com.faze.modules.caseflow.mapper.LegalCaseMapper;
import com.faze.modules.caseflow.rule.CaseStateMachine;
import com.faze.modules.caseflow.vo.AdminCaseDetailVO;
import com.faze.modules.caseflow.vo.AdminCaseRowVO;
import com.faze.modules.caseflow.vo.CaseProgressVO;
import com.faze.modules.trade.enums.OperatorRole;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PC 案件管理业务。
 */
@Service
@Slf4j
public class AdminCaseService {

    /** 合法节点编码。 */
    private static final java.util.Set<String> NODE_CODES = java.util.Set.of(
            "submitted", "review", "accepted", "supplement", "hearing", "judgment", "effective", "closed");

    private final LegalCaseMapper legalCaseMapper;
    private final CaseProgressMapper caseProgressMapper;
    private final LawyerQueryService lawyerQueryService;
    private final JsonMapper jsonMapper;
    private final com.faze.infra.storage.FileRecordService fileRecordService;
    private final com.faze.modules.document.service.DocumentQueryService documentQueryService;

    /**
     * 构造器注入。
     *
     * @param legalCaseMapper      案件 Mapper
     * @param caseProgressMapper   进度 Mapper
     * @param lawyerQueryService   律师只读查询
     * @param jsonMapper           Jackson 3 映射器（解析快照取当事人）
     * @param fileRecordService    对象存储（证据 key 现签 URL）
     * @param documentQueryService 文书只读查询（送达选文书）
     */
    public AdminCaseService(LegalCaseMapper legalCaseMapper,
                            CaseProgressMapper caseProgressMapper,
                            LawyerQueryService lawyerQueryService,
                            JsonMapper jsonMapper,
                            com.faze.infra.storage.FileRecordService fileRecordService,
                            com.faze.modules.document.service.DocumentQueryService documentQueryService) {
        this.legalCaseMapper = legalCaseMapper;
        this.caseProgressMapper = caseProgressMapper;
        this.lawyerQueryService = lawyerQueryService;
        this.jsonMapper = jsonMapper;
        this.fileRecordService = fileRecordService;
        this.documentQueryService = documentQueryService;
    }

    /**
     * 案件列表（PC rows/total 场景）。
     *
     * @param query 查询入参
     * @return 分页响应
     */
    public TableDataInfo<AdminCaseRowVO> list(AdminCaseQueryBO query) {
        var wrapper = Wrappers.<LegalCase>lambdaQuery()
                .isNull(LegalCase::getDeletedAt)
                .orderByDesc(LegalCase::getCreatedAt);
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(LegalCase::getStatus, query.getStatus());
        }
        if (query.getType() != null && !query.getType().isBlank()) {
            wrapper.eq(LegalCase::getCaseType, query.getType());
        }
        if (query.getLawyerId() != null) {
            wrapper.eq(LegalCase::getLawyerId, query.getLawyerId());
        }
        if (query.getKw() != null && !query.getKw().isBlank()) {
            String kw = query.getKw().trim();
            // 案件号精确/前缀，或快照里的当事人姓名包含（JSON 用 LIKE 兜底，量小可接受）
            wrapper.and(w -> w.likeRight(LegalCase::getCaseNo, kw)
                    .or().like(LegalCase::getFormSnapshot, kw));
        }
        Page<LegalCase> page = legalCaseMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        List<AdminCaseRowVO> rows = page.getRecords().stream().map(this::toRowVO).toList();
        return TableDataInfo.of(rows, page.getTotal());
    }

    /**
     * 案件详情。
     *
     * @param id 案件 id
     * @return 详情
     */
    public AdminCaseDetailVO detail(Long id) {
        LegalCase e = legalCaseMapper.selectById(id);
        if (e == null || e.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND);
        }
        AdminCaseDetailVO vo = new AdminCaseDetailVO();
        vo.setId(e.getId());
        vo.setCaseNo(e.getCaseNo());
        vo.setCaseType(e.getCaseType());
        vo.setStatus(e.getStatus());
        vo.setTitle(e.getTitle());
        vo.setFormSnapshot(e.getFormSnapshot());
        vo.setEvidenceUrls(e.getEvidenceUrls());
        vo.setCourtName(e.getCourtName());
        vo.setCourtCaseNo(e.getCourtCaseNo());
        vo.setRejectReason(e.getRejectReason());
        vo.setAmountCents(e.getAmountCents());
        vo.setLawyerId(e.getLawyerId());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        if (e.getLawyerId() != null) {
            lawyerQueryService.findById(e.getLawyerId())
                    .ifPresent(l -> vo.setLawyerName(l.getRealName()));
        }
        vo.setEvidenceImageUrls(presignEvidence(e.getEvidenceUrls()));
        return vo;
    }

    /**
     * 把 `evidence_urls`（`file_record` 的 key 数组 JSON）现签成可访问的图片地址。
     *
     * <p>为什么在服务端签：OSS key 是内部标识，PC 拿 key 拼不出可访问 URL；
     * 且签名地址必须短时有效，不能由前端长期缓存（工作流 B2 第 3 条「签名 URL」）。</p>
     *
     * @param evidenceUrlsJson 证据 key 数组的 JSON 字符串（可能为 null / 空 / 脏数据）
     * @return 签名后的地址列表；解析失败返回空列表（不让一条脏数据把整页打 500）
     */
    private List<String> presignEvidence(String evidenceUrlsJson) {
        if (evidenceUrlsJson == null || evidenceUrlsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> keys = jsonMapper.readValue(evidenceUrlsJson,
                    new tools.jackson.core.type.TypeReference<List<String>>() { });
            List<String> urls = new java.util.ArrayList<>(keys.size());
            for (String key : keys) {
                if (key != null && !key.isBlank()) {
                    urls.add(fileRecordService.presignDownload(key));
                }
            }
            return urls;
        } catch (RuntimeException ex) {
            log.warn("证据 key 数组解析失败，返回空列表", ex);
            return List.of();
        }
    }

    /**
     * 本案用户的可送达文书列表（A-19 抽屉「送达」Tab 的「发起送达」弹窗选文书用）。
     *
     * @param caseId 案件 id
     * @return 文书摘要列表（按更新时间倒序）
     */
    public List<com.faze.modules.caseflow.vo.AdminCaseDocVO> documents(Long caseId) {
        LegalCase e = legalCaseMapper.selectById(caseId);
        if (e == null || e.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND);
        }
        return documentQueryService.listByUserId(e.getUserId()).stream().map(d -> {
            com.faze.modules.caseflow.vo.AdminCaseDocVO vo =
                    new com.faze.modules.caseflow.vo.AdminCaseDocVO();
            vo.setId(d.getId());
            vo.setTitle(d.getTitle());
            vo.setDocType(d.getDocType());
            vo.setStatus(d.getStatus());
            vo.setHasPdf((d.getSignedPdfOssKey() != null && !d.getSignedPdfOssKey().isBlank())
                    || (d.getPdfOssKey() != null && !d.getPdfOssKey().isBlank()));
            vo.setUpdatedAt(d.getUpdatedAt());
            return vo;
        }).toList();
    }

    /**
     * 进度时间轴（A-19 抽屉「进度」Tab）。
     *
     * @param caseId 案件 id
     * @return 节点列表（按 step 升序）
     */
    public List<CaseProgressVO> progress(Long caseId) {
        LegalCase e = legalCaseMapper.selectById(caseId);
        if (e == null || e.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND);
        }
        return caseProgressMapper.selectList(Wrappers.<CaseProgress>lambdaQuery()
                        .eq(CaseProgress::getCaseId, caseId)
                        .orderByAsc(CaseProgress::getStep))
                .stream().map(this::toProgressVO).toList();
    }

    /**
     * 立案审核（通过 → accepted / 驳回 → rejected）。
     *
     * @param caseId 案件 id
     * @param bo     审核入参
     */
    @OperationLog(module = "caseflow", action = "CASE_AUDIT", targetType = "LEGAL_CASE")
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long caseId, CaseAuditBO bo) {
        LegalCase e = legalCaseMapper.selectById(caseId);
        if (e == null || e.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND);
        }
        CaseStatus from = CaseStatus.of(e.getStatus());
        CaseStatus to;
        String reason = null;
        if ("approve".equals(bo.getAction())) {
            to = CaseStatus.ACCEPTED;
        } else if ("reject".equals(bo.getAction())) {
            if (bo.getReason() == null || bo.getReason().isBlank()) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "驳回必须填写理由");
            }
            to = CaseStatus.REJECTED;
            reason = bo.getReason();
        } else {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "审核动作只允许 approve / reject");
        }
        CaseStateMachine.assertCanTransit(from, to, OperatorRole.ADMIN);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = legalCaseMapper.casUpdateStatus(caseId, from.getCode(), to.getCode(), reason, now);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CASE_STATE_INVALID, "案件状态已被其他人修改");
        }
        int step = maxStep(caseId) + 1;
        insertProgress(caseId, step, to == CaseStatus.ACCEPTED ? "accepted" : "review",
                to == CaseStatus.ACCEPTED ? "立案审核通过" : "立案审核驳回",
                reason == null ? "平台形式审查通过，代为提交法院" : reason,
                null, OperatorRole.ADMIN, now);
    }

    /**
     * 进度纠错（PC A-19）：指定合法节点写入，必须填原因。
     *
     * @param caseId 案件 id
     * @param bo     纠错入参
     */
    @OperationLog(module = "caseflow", action = "CASE_PROGRESS_CORRECT", targetType = "LEGAL_CASE")
    @Transactional(rollbackFor = Exception.class)
    public CaseProgressVO correctProgress(Long caseId, AdminProgressBO bo) {
        LegalCase e = legalCaseMapper.selectById(caseId);
        if (e == null || e.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND);
        }
        if (!NODE_CODES.contains(bo.getNodeCode())) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "非法节点编码");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CaseStatus current = CaseStatus.of(e.getStatus());
        if ("closed".equals(bo.getNodeCode()) && current != CaseStatus.CLOSED) {
            CaseStateMachine.assertCanTransit(current, CaseStatus.CLOSED, OperatorRole.ADMIN);
            int rows = legalCaseMapper.casUpdateStatus(caseId, current.getCode(),
                    CaseStatus.CLOSED.getCode(), null, now);
            if (rows == 0) {
                throw new BusinessException(ErrorCode.CASE_STATE_INVALID, "案件状态已被其他人修改");
            }
        }
        CaseProgress p = insertProgress(caseId, maxStep(caseId) + 1, bo.getNodeCode(),
                bo.getTitle() == null ? defaultNodeTitle(bo.getNodeCode()) : bo.getTitle(),
                bo.getDescription(), null, OperatorRole.ADMIN, now);
        return toProgressVO(p);
    }

    /**
     * 组装 PC 列表行。
     *
     * @param e 案件实体
     * @return 列表行
     */
    private AdminCaseRowVO toRowVO(LegalCase e) {
        AdminCaseRowVO vo = new AdminCaseRowVO();
        vo.setId(e.getId());
        vo.setCaseNo(e.getCaseNo());
        vo.setCaseType(e.getCaseType());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setStatus(e.getStatus());
        parseParties(e.getFormSnapshot(), vo);
        if (e.getLawyerId() != null) {
            lawyerQueryService.findById(e.getLawyerId()).ifPresent(l -> vo.setLawyerName(l.getRealName()));
        }
        List<CaseProgress> latest = caseProgressMapper.selectList(Wrappers.<CaseProgress>lambdaQuery()
                .eq(CaseProgress::getCaseId, e.getId())
                .orderByDesc(CaseProgress::getStep));
        vo.setCurrentNode(latest.isEmpty() ? null : latest.get(0).getTitle());
        return vo;
    }

    /**
     * 从 form_snapshot 解析原告/被告姓名（解析失败不抛，列表不应因一条脏数据整页 500）。
     */
    private void parseParties(String snapshot, AdminCaseRowVO vo) {
        if (snapshot == null || snapshot.isBlank()) {
            return;
        }
        try {
            JsonNode root = jsonMapper.readTree(snapshot);
            if (root.has("plaintiff") && root.get("plaintiff").has("name")) {
                vo.setPlaintiffName(root.get("plaintiff").get("name").asString());
            }
            if (root.has("defendant") && root.get("defendant").has("name")) {
                vo.setDefendantName(root.get("defendant").get("name").asString());
            }
        } catch (RuntimeException ex) {
            log.warn("form_snapshot 解析失败", ex);
        }
    }

    private int maxStep(Long caseId) {
        List<CaseProgress> list = caseProgressMapper.selectList(Wrappers.<CaseProgress>lambdaQuery()
                .eq(CaseProgress::getCaseId, caseId)
                .orderByDesc(CaseProgress::getStep));
        return list.isEmpty() ? 0 : list.get(0).getStep();
    }

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
