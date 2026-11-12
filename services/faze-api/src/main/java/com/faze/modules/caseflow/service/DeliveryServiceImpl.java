/*
 * 职责：送达业务实现——发起送达、改造方式、签收、列表、回执。
 * 调用方：AppDeliveryController、AdminCaseController。
 * 数据来源：case_delivery / legal_case / document（跨域经 DocumentQueryService 只读）。
 * 注意：
 *   1. **归属条件进 SQL**：用户侧 `eq(user_id)`，不先查再判（后端代码规范 §11.2）。
 *   2. 签收用 `casSign`（CAS）：affected rows = 0 即「已签收或不属于你」，返回 407007。
 *   3. 回执号在**发起送达时**生成（C1 第 2 条），签收只回填时间与设备。
 *   4. 送达**不写 case_progress、不改案件状态**（C1 第 4 条）。
 *   5. 本类没有远程调用，所以事务范围只有本地写。
 */
package com.faze.modules.caseflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.infra.seq.BizNoGenerator;
import com.faze.infra.seq.BizNoRetry;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.caseflow.bo.DeliveryCreateBO;
import com.faze.modules.caseflow.entity.CaseDelivery;
import com.faze.modules.caseflow.entity.LegalCase;
import com.faze.modules.caseflow.enums.DeliveryMethod;
import com.faze.modules.caseflow.enums.DeliveryStatus;
import com.faze.modules.caseflow.mapper.CaseDeliveryMapper;
import com.faze.modules.caseflow.mapper.LegalCaseMapper;
import com.faze.modules.caseflow.vo.DeliveryRowVO;
import com.faze.modules.document.entity.Document;
import com.faze.modules.document.service.DocumentQueryService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 送达业务实现。
 */
@Service
@Slf4j
public class DeliveryServiceImpl implements DeliveryService {

    /** 可以发起送达的文书状态（必须已经有 PDF）。 */
    private static final Set<String> DELIVERABLE_DOC_STATUS = Set.of("generated", "signed");

    /** 用户列表的合法 status 筛选值。 */
    private static final Set<String> STATUS_FILTERS = Set.of(
            DeliveryStatus.PENDING.getCode(), DeliveryStatus.SIGNED.getCode());

    private final CaseDeliveryMapper caseDeliveryMapper;
    private final LegalCaseMapper legalCaseMapper;
    private final DocumentQueryService documentQueryService;
    private final LawyerQueryService lawyerQueryService;
    private final BizNoGenerator bizNoGenerator;

    /**
     * 构造器注入。
     *
     * @param caseDeliveryMapper   送达 Mapper
     * @param legalCaseMapper      案件 Mapper（取案号 / 案由 / 承办律师）
     * @param documentQueryService 文书只读查询（跨域）
     * @param lawyerQueryService   律师只读查询（跨域）
     * @param bizNoGenerator       回执号生成器
     */
    public DeliveryServiceImpl(CaseDeliveryMapper caseDeliveryMapper,
                               LegalCaseMapper legalCaseMapper,
                               DocumentQueryService documentQueryService,
                               LawyerQueryService lawyerQueryService,
                               BizNoGenerator bizNoGenerator) {
        this.caseDeliveryMapper = caseDeliveryMapper;
        this.legalCaseMapper = legalCaseMapper;
        this.documentQueryService = documentQueryService;
        this.lawyerQueryService = lawyerQueryService;
        this.bizNoGenerator = bizNoGenerator;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeliveryRowVO send(Long caseId, DeliveryCreateBO bo) {
        DeliveryMethod method = DeliveryMethod.of(bo.getMethod());

        // 任务 1：案件必须存在（软删的当不存在）
        LegalCase legalCase = legalCaseMapper.selectOne(Wrappers.<LegalCase>lambdaQuery()
                .eq(LegalCase::getId, caseId)
                .isNull(LegalCase::getDeletedAt));
        if (legalCase == null) {
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND);
        }

        // 任务 2：文书必须存在、属于同一用户、且已经有 PDF
        Document document = documentQueryService.findById(bo.getDocumentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CASE_NO_DOCUMENT, "文书不存在"));
        if (!Objects.equals(document.getUserId(), legalCase.getUserId())) {
            throw new BusinessException(ErrorCode.CASE_NO_DOCUMENT, "该文书不属于本案用户");
        }
        if (!DELIVERABLE_DOC_STATUS.contains(document.getStatus()) || pdfKeyOf(document) == null) {
            throw new BusinessException(ErrorCode.CASE_NO_DOCUMENT, "该文书还没有生成 PDF");
        }

        // 任务 3：生成回执号 + 落库（pending）
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CaseDelivery entity = new CaseDelivery();
        entity.setCaseId(caseId);
        entity.setDocumentId(document.getId());
        entity.setUserId(legalCase.getUserId());
        entity.setMethod(method.getCode());
        entity.setSentAt(now);
        entity.setStatus(DeliveryStatus.PENDING.getCode());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        BizNoRetry.insertWithNo("uk_delivery_receipt", bizNoGenerator::nextDeliveryNo, receiptNo -> {
            entity.setReceiptNo(receiptNo);
            return caseDeliveryMapper.insert(entity);
        });
        log.info("送达已发起 deliveryId={} caseId={} documentId={} method={} receiptNo={}",
                entity.getId(), caseId, document.getId(), method.getCode(), entity.getReceiptNo());
        return toRowVO(entity, legalCase, document, lawyerNameOf(legalCase.getLawyerId()));
    }

    @Override
    public List<DeliveryRowVO> listByCase(Long caseId) {
        List<CaseDelivery> list = caseDeliveryMapper.selectList(Wrappers.<CaseDelivery>lambdaQuery()
                .eq(CaseDelivery::getCaseId, caseId)
                .orderByDesc(CaseDelivery::getSentAt));
        return toRowVOs(list);
    }

    @Override
    public PageResult<DeliveryRowVO> listApp(Long userId, Long caseId, String status,
                                             int pageNum, int pageSize) {
        var wrapper = Wrappers.<CaseDelivery>lambdaQuery()
                .eq(CaseDelivery::getUserId, userId)
                .orderByDesc(CaseDelivery::getSentAt);
        if (caseId != null) {
            wrapper.eq(CaseDelivery::getCaseId, caseId);
        }
        if (status != null && STATUS_FILTERS.contains(status)) {
            wrapper.eq(CaseDelivery::getStatus, status);
        }
        Page<CaseDelivery> page = caseDeliveryMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(toRowVOs(page.getRecords()), page.getTotal(), pageNum, pageSize);
    }

    @Override
    public DeliveryRowVO detailApp(Long userId, Long deliveryId) {
        return toRowVOs(List.of(requireOwned(userId, deliveryId))).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long userId, Long deliveryId, String signDevice, String ip) {
        // 任务 1：先按归属取记录，区分「不存在」（407006）与「已签收」（407007）
        CaseDelivery entity = requireOwned(userId, deliveryId);
        if (DeliveryStatus.SIGNED.getCode().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.CASE_DELIVERY_ALREADY_SIGNED);
        }

        // 任务 2：CAS 签收；并发下第二次必为 0
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = caseDeliveryMapper.casSign(deliveryId, userId, now, signDevice, ip);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CASE_DELIVERY_ALREADY_SIGNED);
        }
        log.info("文书已签收 deliveryId={} userId={} device={} ip={}",
                deliveryId, userId, signDevice, ip);
    }

    @Override
    public void chooseMethod(Long userId, Long deliveryId, String methodCode) {
        // 非法方式先报 407008（**不静默回退**到 platform）
        DeliveryMethod method = DeliveryMethod.of(methodCode);
        CaseDelivery entity = requireOwned(userId, deliveryId);
        if (DeliveryStatus.SIGNED.getCode().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.CASE_DELIVERY_ALREADY_SIGNED, "已签收的送达不能改方式");
        }
        int rows = caseDeliveryMapper.updateMethod(deliveryId, userId, method.getCode(),
                LocalDateTime.now(ZoneOffset.UTC));
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CASE_DELIVERY_ALREADY_SIGNED);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 按 id + user_id 取送达记录（归属进 SQL）。
     *
     * @param userId     当前用户
     * @param deliveryId 送达记录 id
     * @return 送达记录
     * @throws BusinessException 不存在或不属于该用户时抛 407006
     */
    private CaseDelivery requireOwned(Long userId, Long deliveryId) {
        CaseDelivery entity = caseDeliveryMapper.selectOne(Wrappers.<CaseDelivery>lambdaQuery()
                .eq(CaseDelivery::getId, deliveryId)
                .eq(CaseDelivery::getUserId, userId));
        if (entity == null) {
            throw new BusinessException(ErrorCode.CASE_DELIVERY_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 批量组装 VO（案件、文书各一次批量查询，避免 N+1）。
     *
     * @param list 送达记录
     * @return VO 列表
     */
    private List<DeliveryRowVO> toRowVOs(List<CaseDelivery> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<Long> caseIds = list.stream().map(CaseDelivery::getCaseId).collect(Collectors.toSet());
        Map<Long, LegalCase> cases = legalCaseMapper.selectBatchIds(caseIds).stream()
                .collect(Collectors.toMap(LegalCase::getId, Function.identity(), (a, b) -> a));
        Set<Long> docIds = list.stream().map(CaseDelivery::getDocumentId).collect(Collectors.toSet());
        Map<Long, Document> documents = documentQueryService.findByIds(docIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity(), (a, b) -> a));
        Set<Long> lawyerIds = cases.values().stream()
                .map(LegalCase::getLawyerId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> lawyerNames = new HashMap<>();
        for (Long lawyerId : lawyerIds) {
            lawyerQueryService.findById(lawyerId)
                    .ifPresent(l -> lawyerNames.put(l.getId(), l.getRealName()));
        }
        return list.stream().map(d -> {
            LegalCase c = cases.get(d.getCaseId());
            String lawyerName = c == null || c.getLawyerId() == null
                    ? null : lawyerNames.get(c.getLawyerId());
            return toRowVO(d, c, documents.get(d.getDocumentId()), lawyerName);
        }).toList();
    }

    /**
     * 单条组装。空值一律留 null，展示兜底归前端（i18n）。
     *
     * @param d            送达记录
     * @param legalCase    案件（可能为 null）
     * @param document     文书（可能为 null）
     * @param lawyerName   承办律师姓名（可能为 null）
     * @return VO
     */
    private DeliveryRowVO toRowVO(CaseDelivery d, LegalCase legalCase, Document document,
                                  String lawyerName) {
        DeliveryRowVO vo = new DeliveryRowVO();
        vo.setId(d.getId());
        vo.setCaseId(d.getCaseId());
        vo.setDocumentId(d.getDocumentId());
        vo.setMethod(d.getMethod());
        vo.setStatus(d.getStatus());
        vo.setReceiptNo(d.getReceiptNo());
        vo.setSentAt(d.getSentAt());
        vo.setSignedAt(d.getSignedAt());
        vo.setSignDevice(d.getSignDevice());
        vo.setLawyerName(lawyerName);
        if (legalCase != null) {
            vo.setCaseNo(legalCase.getCaseNo());
            vo.setCaseTitle(legalCase.getTitle());
        }
        if (document != null) {
            vo.setDocumentTitle(document.getTitle());
        }
        return vo;
    }

    /**
     * 取案件的承办律师姓名。
     *
     * @param lawyerId 律师 id（可空）
     * @return 姓名；无律师时为 null
     */
    private String lawyerNameOf(Long lawyerId) {
        if (lawyerId == null) {
            return null;
        }
        return lawyerQueryService.findById(lawyerId).map(Lawyer::getRealName).orElse(null);
    }

    /**
     * 取文书生效的 PDF 对象键（盖过章用带签名那份）。
     *
     * @param document 文书
     * @return 对象键；没有 PDF 时 null
     */
    private String pdfKeyOf(Document document) {
        if (document.getSignedPdfOssKey() != null && !document.getSignedPdfOssKey().isBlank()) {
            return document.getSignedPdfOssKey();
        }
        if (document.getPdfOssKey() != null && !document.getPdfOssKey().isBlank()) {
            return document.getPdfOssKey();
        }
        return null;
    }
}
