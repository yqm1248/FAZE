/*
 * 职责：PC A-11 / A-12 的查询服务——订单列表、订单详情、状态流转（G9.1 / G9.2）。
 * 调用方：modules/trade/admin/controller/AdminOrderController。
 * 数据来源：faze.orders、payment_record、refund_record、order_status_log、conversation、
 *          app_user（仅昵称与脱敏手机号）、lawyer。
 * 注意：
 *   1. **全部只读**：本类没有任何写操作，也不标 `@OperationLog`（纯查询不审计，审计规范 §3.2）。
 *   2. **批量补用户/律师**：列表 10~100 行必须一次 `in` 查完，禁止逐行查（N+1）。
 *   3. 用户手机号只出**脱敏值**（复用 `AccountConverter#maskPhone`）：管理端列表是高频页，
 *      明文手机号等于全站用户信息摊开。
 *   4. 关联会话按 `(user_id, lawyer_id)` 查（拍板第 6 项），查不到返回 null；
 *      **不编造会话编号**。
 *   5. 状态流水从 `order_status_log` 读，按时间正序（工作流 G9.2 第 2 条：
 *      不许用 orders 的四个时间戳拼）。
 */
package com.faze.modules.trade.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.account.converter.AccountConverter;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.mapper.ConversationMapper;
import com.faze.modules.trade.admin.bo.AdminOrderQueryBO;
import com.faze.modules.trade.admin.vo.AdminOrderDetailVO;
import com.faze.modules.trade.admin.vo.AdminOrderRowVO;
import com.faze.modules.trade.admin.vo.AdminOrderStatusLogVO;
import com.faze.modules.trade.admin.vo.AdminPaymentVO;
import com.faze.modules.trade.admin.vo.AdminRefundVO;
import com.faze.modules.trade.entity.OrderStatusLog;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.entity.PaymentRecord;
import com.faze.modules.trade.entity.RefundRecord;
import com.faze.modules.trade.mapper.OrderStatusLogMapper;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.mapper.PaymentRecordMapper;
import com.faze.modules.trade.mapper.RefundRecordMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PC 订单查询服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminOrderService {

    /** 每页条数上限。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final OrdersMapper ordersMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final ConversationMapper conversationMapper;
    private final AccountMapper accountMapper;
    private final LawyerMapper lawyerMapper;
    private final AccountConverter accountConverter;

    /**
     * 分页查订单（A-11）。
     *
     * @param query 筛选条件
     * @return RuoYi 口径分页响应（rows / total）
     */
    public TableDataInfo<AdminOrderRowVO> list(AdminOrderQueryBO query) {
        int pageNum = query == null || query.getPageNum() == null || query.getPageNum() < 1
                ? 1 : query.getPageNum();
        int pageSize = query == null || query.getPageSize() == null
                ? 10 : Math.min(Math.max(query.getPageSize(), 1), MAX_PAGE_SIZE);
        String status = query == null || query.getStatus() == null || query.getStatus().isBlank()
                ? null : query.getStatus().trim();

        IPage<Orders> page = ordersMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<Orders>lambdaQuery()
                        .isNull(Orders::getDeletedAt)
                        .eq(status != null, Orders::getStatus, status)
                        .eq(query != null && query.getLawyerId() != null, Orders::getLawyerId,
                                query == null ? null : query.getLawyerId())
                        .eq(query != null && query.getUserId() != null, Orders::getUserId,
                                query == null ? null : query.getUserId())
                        .ge(query != null && query.getStartDate() != null, Orders::getCreatedAt,
                                query == null ? null : query.getStartDate())
                        .le(query != null && query.getEndDate() != null, Orders::getCreatedAt,
                                query == null ? null : query.getEndDate())
                        .orderByDesc(Orders::getCreatedAt)
                        .orderByDesc(Orders::getId));

        List<Orders> rows = page.getRecords();
        Map<Long, AppUser> users = loadUsers(rows.stream().map(Orders::getUserId)
                .collect(Collectors.toSet()));
        Map<Long, Lawyer> lawyers = loadLawyers(rows.stream().map(Orders::getLawyerId)
                .collect(Collectors.toSet()));
        List<AdminOrderRowVO> list = rows.stream()
                .map(order -> toRow(order, users.get(order.getUserId()), lawyers.get(order.getLawyerId())))
                .collect(Collectors.toList());
        return TableDataInfo.of(list, page.getTotal());
    }

    /**
     * 查订单详情（A-12）。
     *
     * @param orderId 订单主键
     * @return 详情（含支付记录、退款单、关联会话）
     * @throws BusinessException 405002 订单不存在
     */
    public AdminOrderDetailVO detail(Long orderId) {
        Orders order = requireOrder(orderId);
        AdminOrderDetailVO vo = new AdminOrderDetailVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setServiceType(order.getServiceType());
        vo.setAmountCents(order.getAmountCents());
        // 段 5 B5：订单详情补服务费金额（只读展示；**不写 payment_record**，那张表永远只有支付/退款两行）
        vo.setPlatformFeeCents(order.getPlatformFeeCents());
        vo.setLawyerIncomeCents(order.getLawyerIncomeCents());
        vo.setRemark(order.getRemark());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setPaidAt(order.getPaidAt());
        vo.setAcceptedAt(order.getAcceptedAt());
        vo.setFinishedAt(order.getFinishedAt());
        vo.setCancelledAt(order.getCancelledAt());

        AppUser user = order.getUserId() == null ? null : accountMapper.selectById(order.getUserId());
        vo.setUserId(order.getUserId());
        vo.setUserName(user == null ? null : user.getNickname());
        vo.setUserPhoneMasked(user == null ? null : accountConverter.maskPhone(user.getPhone()));

        Lawyer lawyer = order.getLawyerId() == null ? null : lawyerMapper.selectById(order.getLawyerId());
        vo.setLawyerId(order.getLawyerId());
        vo.setLawyerName(lawyer == null ? null : lawyer.getRealName());
        vo.setLawyerFirm(lawyer == null ? null : lawyer.getLawFirm());

        // 关联会话：一个用户 × 一个律师只有一条（拍板第 6 项）
        Conversation conversation = order.getUserId() == null || order.getLawyerId() == null ? null
                : conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getUserId, order.getUserId())
                        .eq(Conversation::getLawyerId, order.getLawyerId())
                        .isNull(Conversation::getDeletedAt));
        vo.setConversationId(conversation == null ? null : conversation.getId());

        vo.setPayments(paymentRecordMapper.selectList(Wrappers.<PaymentRecord>lambdaQuery()
                        .eq(PaymentRecord::getOrderId, orderId)
                        .orderByAsc(PaymentRecord::getId))
                .stream().map(this::toPayment).collect(Collectors.toList()));
        vo.setRefunds(refundRecordMapper.selectList(Wrappers.<RefundRecord>lambdaQuery()
                        .eq(RefundRecord::getOrderId, orderId)
                        .orderByDesc(RefundRecord::getId))
                .stream().map(this::toRefund).collect(Collectors.toList()));
        return vo;
    }

    /**
     * 查订单状态流转（A-12 时间线）。
     *
     * @param orderId 订单主键
     * @return 流水（按时间正序）
     * @throws BusinessException 405002 订单不存在
     */
    public List<AdminOrderStatusLogVO> statusLog(Long orderId) {
        requireOrder(orderId);
        return orderStatusLogMapper.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                        .eq(OrderStatusLog::getOrderId, orderId)
                        .orderByAsc(OrderStatusLog::getCreatedAt)
                        .orderByAsc(OrderStatusLog::getId))
                .stream().map(this::toStatusLog).collect(Collectors.toList());
    }

    /**
     * 取订单（未软删）。
     *
     * @param orderId 订单主键
     * @return 订单
     * @throws BusinessException 405002
     */
    private Orders requireOrder(Long orderId) {
        Orders order = orderId == null ? null : ordersMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getId, orderId)
                .isNull(Orders::getDeletedAt));
        if (order == null) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 行 → 列表 VO。
     *
     * @param order  订单
     * @param user   用户（可空）
     * @param lawyer 律师（可空）
     * @return 列表行
     */
    private AdminOrderRowVO toRow(Orders order, AppUser user, Lawyer lawyer) {
        AdminOrderRowVO vo = new AdminOrderRowVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setServiceType(order.getServiceType());
        vo.setAmountCents(order.getAmountCents());
        vo.setUserId(order.getUserId());
        vo.setUserName(user == null ? null : user.getNickname());
        vo.setUserPhoneMasked(user == null ? null : accountConverter.maskPhone(user.getPhone()));
        vo.setLawyerId(order.getLawyerId());
        vo.setLawyerName(lawyer == null ? null : lawyer.getRealName());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setPaidAt(order.getPaidAt());
        vo.setFinishedAt(order.getFinishedAt());
        return vo;
    }

    /**
     * 支付记录 → VO。
     *
     * @param record 支付记录
     * @return VO
     */
    private AdminPaymentVO toPayment(PaymentRecord record) {
        AdminPaymentVO vo = new AdminPaymentVO();
        vo.setId(record.getId());
        vo.setChannel(record.getChannel());
        vo.setTransactionId(record.getTransactionId());
        vo.setAmountCents(record.getAmountCents());
        vo.setStatus(record.getStatus());
        vo.setPaidAt(record.getPaidAt());
        return vo;
    }

    /**
     * 退款单 → VO。
     *
     * @param record 退款单
     * @return VO
     */
    private AdminRefundVO toRefund(RefundRecord record) {
        AdminRefundVO vo = new AdminRefundVO();
        vo.setId(record.getId());
        vo.setOrderId(record.getOrderId());
        vo.setAmountCents(record.getAmountCents());
        vo.setReason(record.getReason());
        vo.setStatus(record.getStatus());
        vo.setAuditRemark(record.getAuditRemark());
        vo.setAuditedBy(record.getAuditedBy());
        vo.setAuditedAt(record.getAuditedAt());
        vo.setRefundedAt(record.getRefundedAt());
        vo.setTransactionId(record.getTransactionId());
        vo.setCreatedAt(record.getCreatedAt());
        return vo;
    }

    /**
     * 流水 → VO。
     *
     * @param log 流水
     * @return VO
     */
    private AdminOrderStatusLogVO toStatusLog(OrderStatusLog log) {
        AdminOrderStatusLogVO vo = new AdminOrderStatusLogVO();
        vo.setId(log.getId());
        vo.setOrderId(log.getOrderId());
        vo.setFromStatus(log.getFromStatus());
        vo.setToStatus(log.getToStatus());
        vo.setOperatorType(log.getOperatorType());
        vo.setOperatorId(log.getOperatorId() == null ? null : String.valueOf(log.getOperatorId()));
        vo.setReason(log.getReason());
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }

    /**
     * 批量取用户。
     *
     * @param ids 用户 id 集合
     * @return app_user.id → 用户
     */
    private Map<Long, AppUser> loadUsers(Set<Long> ids) {
        Set<Long> valid = ids.stream().filter(id -> id != null).collect(Collectors.toSet());
        if (valid.isEmpty()) {
            return Map.of();
        }
        return accountMapper.selectBatchIds(valid).stream()
                .collect(Collectors.toMap(AppUser::getId, item -> item, (left, right) -> left));
    }

    /**
     * 批量取律师。
     *
     * @param ids 律师 id 集合
     * @return lawyer.id → 律师
     */
    private Map<Long, Lawyer> loadLawyers(Set<Long> ids) {
        Set<Long> valid = ids.stream().filter(id -> id != null).collect(Collectors.toSet());
        if (valid.isEmpty()) {
            return Map.of();
        }
        return lawyerMapper.selectBatchIds(valid).stream()
                .collect(Collectors.toMap(Lawyer::getId, item -> item, (left, right) -> left));
    }
}
