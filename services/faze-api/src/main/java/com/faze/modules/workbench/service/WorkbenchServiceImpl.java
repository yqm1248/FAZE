/*
 * 职责：律师工作台业务实现（段 5 D1~D5）。
 * 调用方：modules/workbench/controller/AppWorkbenchController。
 * 数据来源：document（经 DocumentQueryService / DocDeliveryService 跨域）、
 *          conversation（经 ConversationQueryService / ConversationService / MessageService）、
 *          account（LawyerQueryService / AccountQueryService / LawyerProfileService）、
 *          trade（LawyerIncomeQueryService）、ops（SysConfigQueryService）。
 * 注意：
 *   1. **归属条件进 SQL**：所有查询都以「先按当前用户解析出 lawyer.id」开始，再按 lawyer_id 过滤。
 *   2. **金额口径以 balance_flow 为准**，`lawyer_balance` 只作缓存展示（§八.1 第 1 条）。
 *   3. 「待结算」= `lawyer_balance.frozen_cents`（页面文案叫「待结算」，代码注释写 frozen，
 *      全页不出现「冻结」字样，与设计稿一致）。
 *   4. **不缓存律师价格**：段 2 的律师详情/列表都是直接查库，改价后用户端下一次请求即新价
 *      （D4 第 7 条的验收就是靠这个，不需要缓存失效逻辑）。
 */
package com.faze.modules.workbench.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.service.LawyerProfileService;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.account.vo.UserBriefVO;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.service.ConversationQueryService;
import com.faze.modules.conversation.service.ConversationService;
import com.faze.modules.conversation.service.MessageService;
import com.faze.modules.document.entity.Document;
import com.faze.modules.document.service.DocDeliveryService;
import com.faze.modules.document.service.DocumentQueryService;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.LawyerBalance;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.service.LawyerIncomeQueryService;
import com.faze.modules.workbench.bo.WithdrawAccountBO;
import com.faze.modules.workbench.bo.WorkbenchProfileBO;
import com.faze.modules.workbench.vo.ConsultantRowVO;
import com.faze.modules.workbench.vo.IncomeFlowPageVO;
import com.faze.modules.workbench.vo.IncomeFlowRowVO;
import com.faze.modules.workbench.vo.IncomeOverviewVO;
import com.faze.modules.workbench.vo.ServiceSettingsVO;
import com.faze.modules.workbench.vo.WithdrawAccountVO;
import com.faze.modules.workbench.vo.WorkbenchDocRowVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 律师工作台业务实现。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkbenchServiceImpl implements WorkbenchService {

    /** 分页上限（接口规范 §6：pageSize 超 100 由服务端 clamp）。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 简介字数上限（D5：≤200 字）。 */
    private static final int BIO_MAX_LENGTH = 200;

    /** 律所名称长度上限（数据表规范：law_firm VARCHAR(100)）。 */
    private static final int LAW_FIRM_MAX_LENGTH = 100;

    /** 专长标签上限（D5：6 个多选）。 */
    private static final int SPECIALTY_MAX_COUNT = 6;

    /** 「本周」的窗口天数。 */
    private static final int WEEK_DAYS = 7;

    /** 「本月」的窗口天数。 */
    private static final int MONTH_DAYS = 30;

    private final LawyerQueryService lawyerQueryService;
    private final AccountQueryService accountQueryService;
    private final LawyerProfileService lawyerProfileService;
    private final DocumentQueryService documentQueryService;
    private final DocDeliveryService docDeliveryService;
    private final ConversationQueryService conversationQueryService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final LawyerIncomeQueryService lawyerIncomeQueryService;
    private final SysConfigQueryService sysConfigQueryService;

    // ── D2：律师文书 ────────────────────────────────────────────────────────

    @Override
    public PageResult<WorkbenchDocRowVO> listDocuments(Long userId, String status, int pageNum,
                                                       int pageSize) {
        Lawyer lawyer = requireLawyer(userId);
        int size = clampPageSize(pageSize);
        int page = Math.max(pageNum, 1);

        List<Document> all = documentQueryService.listByLawyerId(lawyer.getId());
        List<Document> filtered = status == null || status.isBlank()
                ? all
                : all.stream().filter(doc -> status.equals(doc.getStatus())).toList();

        // 律师代写的文书量级很小（一个律师几十到几百），在内存里分页比造一条带 JOIN 的 SQL 更简单，
        // 且 listByLawyerId 已经按 updated_at 倒序排好。
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        List<Document> slice = filtered.subList(from, to);

        Set<Long> clientIds = slice.stream().map(Document::getUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, UserBriefVO> clients = accountQueryService.mapBriefs(clientIds);

        List<WorkbenchDocRowVO> rows = new ArrayList<>(slice.size());
        for (Document doc : slice) {
            WorkbenchDocRowVO vo = new WorkbenchDocRowVO();
            vo.setId(doc.getId());
            vo.setTitle(doc.getTitle());
            UserBriefVO client = clients.get(doc.getUserId());
            vo.setClientName(client == null ? null : client.nickname());
            vo.setStatus(doc.getStatus());
            vo.setPdfAvailable(pdfKeyOf(doc) != null);
            vo.setCreatedAt(doc.getCreatedAt());
            vo.setUpdatedAt(doc.getUpdatedAt());
            rows.add(vo);
        }
        return PageResult.of(rows, filtered.size(), page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sendDocumentToClient(Long userId, Long documentId) {
        Lawyer lawyer = requireLawyer(userId);

        // 任务 1：文书必须属于「本律师代写」且已有 PDF
        Document doc = documentQueryService.findById(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (!java.util.Objects.equals(doc.getLawyerId(), lawyer.getId())) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN, "该文书不是本人代写");
        }
        String pdfKey = pdfKeyOf(doc);
        if (pdfKey == null) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE, "文书还没有 PDF，不能发送");
        }

        // 任务 2：取（或建）与该委托人的会话
        Conversation conversation = conversationQueryService.findByUserAndLawyer(
                doc.getUserId(), lawyer.getId());
        if (conversation == null) {
            conversationService.createOrGet(doc.getUserId(), lawyer.getId());
            conversation = conversationQueryService.findByUserAndLawyer(
                    doc.getUserId(), lawyer.getId());
        }
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }

        // 任务 3：写入一条文书消息（服务端内部通道，客户端不能伪造 doc 类型）
        String title = doc.getTitle() == null || doc.getTitle().isBlank() ? "文书" : doc.getTitle();
        messageService.sendDocument(userId, conversation.getId(), title, pdfKey,
                title + ".pdf", null);

        // 任务 4：回填首次发送时间（已有值不覆盖：发第二次仍然显示第一次的「已发送」时间）
        if (doc.getSentAt() == null) {
            docDeliveryService.markSent(doc.getId(), LocalDateTime.now(ZoneOffset.UTC));
        }
        log.info("律师文书已发送 documentId={} lawyerId={} conversationId={}",
                documentId, lawyer.getId(), conversation.getId());
    }

    // ── D1：客户咨询 ────────────────────────────────────────────────────────

    @Override
    public PageResult<ConsultantRowVO> listConsultations(Long userId, int pageNum, int pageSize) {
        Lawyer lawyer = requireLawyer(userId);
        int size = clampPageSize(pageSize);
        int page = Math.max(pageNum, 1);

        List<Conversation> all = conversationQueryService.listByLawyerId(lawyer.getId());
        // 排序：未读优先，其余按最近消息倒序（设计稿原文的排序说明）
        List<Conversation> sorted = new ArrayList<>(all);
        sorted.sort(Comparator
                .comparingInt((Conversation c) -> c.getUnreadLawyer() == null ? 0 : c.getUnreadLawyer())
                .reversed()
                .thenComparing((Conversation c) -> c.getLastMsgAt() == null
                        ? LocalDateTime.MIN : c.getLastMsgAt(), Comparator.reverseOrder()));

        int from = Math.min((page - 1) * size, sorted.size());
        int to = Math.min(from + size, sorted.size());
        List<Conversation> slice = sorted.subList(from, to);

        Set<Long> peerIds = slice.stream().map(Conversation::getUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, UserBriefVO> peers = accountQueryService.mapBriefs(peerIds);
        Set<Long> paidUserIds = lawyerIncomeQueryService.paidUserIds(lawyer.getId(), peerIds);

        List<ConsultantRowVO> rows = new ArrayList<>(slice.size());
        for (Conversation c : slice) {
            ConsultantRowVO vo = new ConsultantRowVO();
            vo.setConversationId(c.getId());
            vo.setPeerUserId(c.getUserId());
            UserBriefVO peer = peers.get(c.getUserId());
            if (peer != null) {
                vo.setPeerName(peer.nickname());
                vo.setPeerAvatarUrl(peer.avatarUrl());
                vo.setPeerPhoneMasked(peer.phoneMasked());
            }
            vo.setUnreadCount(c.getUnreadLawyer() == null ? 0 : c.getUnreadLawyer());
            vo.setLastMessage(c.getLastMessage());
            vo.setLastMsgAt(c.getLastMsgAt());
            vo.setOrderPaid(paidUserIds.contains(c.getUserId()));
            rows.add(vo);
        }
        return PageResult.of(rows, sorted.size(), page, size);
    }

    // ── D3：收入 ────────────────────────────────────────────────────────────

    @Override
    public IncomeOverviewVO incomeOverview(Long userId, String range) {
        Lawyer lawyer = requireLawyer(userId);
        String normalized = normalizeRange(range);
        LocalDateTime from = fromOf(normalized);

        LawyerBalance balance = lawyerIncomeQueryService.balanceOf(lawyer.getId()).orElse(null);
        long available = balance == null || balance.getAvailableCents() == null
                ? 0L : balance.getAvailableCents();
        long frozen = balance == null || balance.getFrozenCents() == null
                ? 0L : balance.getFrozenCents();

        IncomeOverviewVO vo = new IncomeOverviewVO();
        vo.setRange(normalized);
        vo.setTotalIncomeCents(lawyerIncomeQueryService.incomeSumCents(lawyer.getId(), from));
        vo.setOrderCount(lawyerIncomeQueryService.incomeOrderCount(lawyer.getId(), from));
        vo.setAvailableCents(available);
        vo.setFrozenCents(frozen);
        // 页面文案叫「待结算」：就是 frozen_cents，两者互斥不相含（D3 第 4 条）
        vo.setPendingSettleCents(frozen);
        vo.setTotalWithdrawCents(lawyerIncomeQueryService.withdrawSumCents(lawyer.getId(), null));
        return vo;
    }

    @Override
    public IncomeFlowPageVO incomeFlows(Long userId, String range, Long cursor, int limit) {
        Lawyer lawyer = requireLawyer(userId);
        int size = clampPageSize(limit);
        LocalDateTime from = fromOf(normalizeRange(range));

        // 多取一条判断 hasMore，返回前裁掉
        List<BalanceFlow> rows = lawyerIncomeQueryService.incomeFlows(
                lawyer.getId(), from, cursor, size + 1);
        boolean hasMore = rows.size() > size;
        List<BalanceFlow> page = hasMore ? rows.subList(0, size) : rows;

        Set<Long> orderIds = page.stream().map(BalanceFlow::getBizId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Orders> orders = lawyerIncomeQueryService.ordersByIds(orderIds);

        List<IncomeFlowRowVO> list = new ArrayList<>(page.size());
        for (BalanceFlow flow : page) {
            IncomeFlowRowVO row = new IncomeFlowRowVO();
            row.setFlowId(flow.getId());
            row.setBizType(flow.getBizType());
            row.setDeltaCents(flow.getDeltaCents());
            row.setBalanceAfter(flow.getBalanceAfter());
            row.setRemark(flow.getRemark());
            row.setCreatedAt(flow.getCreatedAt());
            Orders order = flow.getBizId() == null ? null : orders.get(flow.getBizId());
            if (order != null) {
                // 每行必须有订单号（§零之二 第 18 项）：用户能逐笔追到订单
                row.setOrderNo(order.getOrderNo());
                row.setOrderTitle(order.getServiceType());
            }
            list.add(row);
        }

        IncomeFlowPageVO vo = new IncomeFlowPageVO();
        vo.setList(list);
        vo.setHasMore(hasMore);
        vo.setNextCursor(page.isEmpty() ? null : page.get(page.size() - 1).getId());
        return vo;
    }

    // ── D4：服务设置 ────────────────────────────────────────────────────────

    @Override
    public ServiceSettingsVO getServiceSettings(Long userId) {
        Lawyer lawyer = requireLawyer(userId);
        ServiceSettingsVO vo = new ServiceSettingsVO();
        vo.setServiceTextOn(lawyer.getServiceTextOn());
        vo.setServicePhoneOn(lawyer.getServicePhoneOn());
        vo.setServiceDocOn(lawyer.getServiceDocOn());
        vo.setAutoAccept(lawyer.getAutoAccept());
        vo.setConsultPriceCents(lawyer.getConsultPriceCents());
        vo.setPhonePriceCents(lawyer.getPhonePriceCents());
        vo.setDocumentPriceCents(lawyer.getDocumentPriceCents());
        vo.setRegion(lawyer.getRegion());
        vo.setPriceMinCents((long) sysConfigQueryService.getInt("lawyer.price.min_cents", 2000));
        vo.setPriceMaxCents((long) sysConfigQueryService.getInt("lawyer.price.max_cents", 200000));
        return vo;
    }


    // ── D5：资料 ────────────────────────────────────────────────────────────

    @Override
    public void updateProfile(Long userId, WorkbenchProfileBO bo) {
        Lawyer lawyer = requireLawyer(userId);
        if (bo.getBio() != null && bo.getBio().length() > BIO_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "个人简介不能超过 200 字");
        }
        if (bo.getLawFirm() != null && bo.getLawFirm().length() > LAW_FIRM_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "律所名称不能超过 100 字");
        }
        if (bo.getSpecialties() != null && bo.getSpecialties().size() > SPECIALTY_MAX_COUNT) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "专长最多选 6 个");
        }
        // 姓名 / 执业证号 / 地区 / 审核状态不在入参里，根本没有被改的可能（LawyerProfileServiceImpl 的白名单）
        lawyerProfileService.updateProfile(lawyer.getId(), bo.getBio(), bo.getSpecialties(),
                bo.getLawFirm());
    }

    // ── 收款账户（F 组提现前置）─────────────────────────────────────────────

    @Override
    public WithdrawAccountVO getWithdrawAccount(Long userId) {
        Lawyer lawyer = requireLawyer(userId);
        WithdrawAccountVO vo = new WithdrawAccountVO();
        String account = lawyer.getWithdrawBankAccount();
        boolean bound = account != null && account.length() >= 4;
        vo.setBound(bound);
        vo.setBankName(lawyer.getWithdrawBankName());
        vo.setBankAccountTail(bound ? account.substring(account.length() - 4) : null);
        vo.setAccountNameMasked(maskName(lawyer.getRealName()));
        vo.setUpdatedAt(lawyer.getUpdatedAt());
        return vo;
    }

    @Override
    public void updateWithdrawAccount(Long userId, WithdrawAccountBO bo) {
        Lawyer lawyer = requireLawyer(userId);
        // 实名一致：收款人姓名必须与律师实名完全一致（D4 规则第 4 条「本人实名一致的银行卡」）
        if (!bo.getAccountName().trim().equals(lawyer.getRealName())) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "收款人姓名必须与实名一致");
        }
        lawyerProfileService.updateWithdrawAccount(lawyer.getId(),
                bo.getBankName().trim(), bo.getBankAccount().trim());
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 取当前用户对应的律师档案；不是律师直接 403。
     *
     * @param userId 当前登录用户 app_user.id
     * @return 律师实体
     * @throws BusinessException 401008（非律师）
     */
    private Lawyer requireLawyer(Long userId) {
        return lawyerQueryService.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMON_FORBIDDEN, "当前用户不是律师"));
    }

    /**
     * 价格区间校验（上下限来自 sys_config）。
     *
     * @param cents 价格（分，null 表示不改，直接跳过）
     * @param min   下限（分）
     * @param max   上限（分）
     * @param label 服务名（写进错误信息，便于定位是哪一个越界）
     * @throws BusinessException 405006 金额非法
     */
    private void assertPriceInRange(Long cents, long min, long max, String label) {
        if (cents == null) {
            return;
        }
        if (cents < min || cents > max) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID,
                    label + "价格必须在 " + (min / 100) + " ~ " + (max / 100) + " 元之间");
        }
    }

    /**
     * 规整区间参数。
     *
     * @param range 原始值
     * @return week / month / total；非法或空按 month
     */
    private String normalizeRange(String range) {
        if ("week".equals(range) || "total".equals(range)) {
            return range;
        }
        return "month";
    }

    /**
     * 区间 → 起始时间。
     *
     * @param range week / month / total
     * @return 起始时间；total 返回 null 表示不限
     */
    private LocalDateTime fromOf(String range) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if ("week".equals(range)) {
            return now.minusDays(WEEK_DAYS);
        }
        if ("total".equals(range)) {
            return null;
        }
        return now.minusDays(MONTH_DAYS);
    }

    /**
     * 取文书生效的 PDF 对象键（盖过章用带签名那份）。
     *
     * @param doc 文书
     * @return 对象键；没有 PDF 时 null
     */
    private String pdfKeyOf(Document doc) {
        if (doc.getSignedPdfOssKey() != null && !doc.getSignedPdfOssKey().isBlank()) {
            return doc.getSignedPdfOssKey();
        }
        if (doc.getPdfOssKey() != null && !doc.getPdfOssKey().isBlank()) {
            return doc.getPdfOssKey();
        }
        return null;
    }

    /**
     * 姓名脱敏（保留姓氏，其余打码）。
     *
     * @param name 姓名
     * @return 脱敏结果；空值返回 null
     */
    private String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /**
     * 分页大小 clamp。
     *
     * @param pageSize 请求的每页条数
     * @return 落在 [1, 100] 的值
     */
    private static int clampPageSize(int pageSize) {
        if (pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
