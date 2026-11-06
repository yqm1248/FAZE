/*
 * 职责：律师工作台（G3.4）——看板统计、在线状态、服务开关与定价。
 * 调用方：modules/trade/controller/WorkbenchController。
 * 数据来源：orders（经 OrdersMapper#selectWorkbenchStats 聚合）、lawyer（读写）。
 * 注意：
 *   1. **看板收入是「预计」，不是到账**：来源是 `orders.lawyer_income_cents`，
 *      真实结算属段 5。前端文案必须带「预计」。
 *   2. 「今日」按东八区切日（`Asia/Shanghai` 的 0 点转 UTC），不用 UTC 0 点：
 *      新疆用户看到的今天从北京时间 0 点算，UTC 口径会把 08:00 前的单算到昨天。
 *   3. 改在线状态只接受三个值（online / busy / offline），**不许传任意字符串**：
 *      `online_status` 是 VARCHAR，脏值会让「在线优先」排序与下单开关失效。
 *   4. 改价格要校验：价格必须 > 0 或显式关闭上架；0 元价格会让下单被 405009 拒，
 *      与其让用户下单时才发现，不如在设置时就说清楚（这里允许 0 = 未定价，
 *      但必须同时把上架开关置 false，见 `updateServiceSettings`）。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.trade.bo.OnlineStatusBO;
import com.faze.modules.trade.bo.ServiceSettingsBO;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.vo.WorkbenchSummaryVO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 律师工作台服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LawyerWorkbenchService {

    /** 允许的在线状态（与 `lawyer.online_status` 的约定值一致）。 */
    private static final Set<String> ALLOWED_ONLINE = Set.of("online", "busy", "offline");

    /** 业务时区：东八区（「今日」按它切日）。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final LawyerOrderService lawyerOrderService;
    private final LawyerMapper lawyerMapper;
    private final OrdersMapper ordersMapper;

    /**
     * 工作台看板。
     *
     * @param userId 当前登录用户（律师）
     * @return 看板数据
     * @throws BusinessException 401008 当前用户不是律师
     */
    public WorkbenchSummaryVO summary(Long userId) {
        Lawyer lawyer = lawyerOrderService.requireLawyer(userId);
        LocalDateTime beginUtc = LocalDate.now(BIZ_ZONE).atStartOfDay().atZone(BIZ_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        WorkbenchSummaryVO stats = ordersMapper.selectWorkbenchStats(lawyer.getId(), beginUtc);
        WorkbenchSummaryVO vo = stats == null ? new WorkbenchSummaryVO() : stats;
        // SUM/COUNT 已用 COALESCE 兜到 0；这里再保一次 null（VO 字段是包装类型）
        vo.setTodayOrderCount(nullSafe(vo.getTodayOrderCount()));
        vo.setTodayIncomeCents(nullSafe(vo.getTodayIncomeCents()));
        vo.setTotalIncomeCents(nullSafe(vo.getTotalIncomeCents()));
        vo.setPendingAcceptCount(nullSafe(vo.getPendingAcceptCount()));
        vo.setInServiceCount(nullSafe(vo.getInServiceCount()));
        vo.setLawyerId(lawyer.getId());
        vo.setRealName(lawyer.getRealName());
        vo.setRating(lawyer.getRating() == null ? null : lawyer.getRating().toPlainString());
        vo.setReviewCount(lawyer.getReviewCount());
        vo.setOnlineStatus(lawyer.getOnlineStatus());
        vo.setConsultPriceCents(lawyer.getConsultPriceCents());
        vo.setPhonePriceCents(lawyer.getPhonePriceCents());
        vo.setDocumentPriceCents(lawyer.getDocumentPriceCents());
        vo.setServiceTextOn(lawyer.getServiceTextOn());
        vo.setServicePhoneOn(lawyer.getServicePhoneOn());
        vo.setServiceDocOn(lawyer.getServiceDocOn());
        return vo;
    }

    /**
     * 修改在线状态。
     *
     * @param userId 当前登录用户（律师）
     * @param status online / busy / offline
     * @throws BusinessException 401001 值非法 / 401008 不是律师
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateOnlineStatus(Long userId, String status) {
        Lawyer lawyer = lawyerOrderService.requireLawyer(userId);
        String value = status == null ? "" : status.trim();
        if (!ALLOWED_ONLINE.contains(value)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "onlineStatus 取值非法");
        }
        lawyerMapper.update(null, Wrappers.<Lawyer>lambdaUpdate()
                .set(Lawyer::getOnlineStatus, value)
                .set(Lawyer::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(Lawyer::getId, lawyer.getId()));
        log.info("律师改在线状态 lawyerId={} status={}", lawyer.getId(), value);
    }

    /**
     * 修改服务设置（价格与上架开关）。
     *
     * <p>规则一：字段为 null 表示**本次不改这一项**（与 {@code ServiceSettingsBO} 的字段注释一致），
     * 只有传了才写库。之前无条件 {@code set} 会把没传的价格清成 NULL，是隐藏的数据丢失。</p>
     *
     * <p>规则二：价格为 null 或 <= 0 时，对应服务**强制下架**——否则会出现「上架但 0 元」的律师，
     * 用户点下单直接 405009，是必然的客诉。本次没传价格时用**库内现值**判断能不能上架，
     * 不能因为「没传价格」就把已定价的服务一起下架。</p>
     *
     * @param userId  当前登录用户（律师）
     * @param request 服务设置（字段缺省 = 不修改）
     * @throws BusinessException 401001 值非法 / 401008 不是律师
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateServiceSettings(Long userId, ServiceSettingsBO request) {
        Lawyer lawyer = lawyerOrderService.requireLawyer(userId);
        Long consult = request.getConsultPriceCents();
        Long phone = request.getPhonePriceCents();
        Long document = request.getDocumentPriceCents();
        assertPrice(consult);
        assertPrice(phone);
        assertPrice(document);

        // 没传价格就用库内现值参与「能否上架」的判断
        long effectiveConsult = consult == null ? zeroOf(lawyer.getConsultPriceCents()) : consult;
        long effectivePhone = phone == null ? zeroOf(lawyer.getPhonePriceCents()) : phone;
        long effectiveDocument = document == null
                ? zeroOf(lawyer.getDocumentPriceCents()) : document;

        lawyerMapper.update(null, Wrappers.<Lawyer>lambdaUpdate()
                .set(consult != null, Lawyer::getConsultPriceCents, consult)
                .set(phone != null, Lawyer::getPhonePriceCents, phone)
                .set(document != null, Lawyer::getDocumentPriceCents, document)
                .set(Lawyer::getServiceTextOn, Boolean.TRUE.equals(request.getServiceTextOn())
                        && positive(effectiveConsult))
                .set(Lawyer::getServicePhoneOn, Boolean.TRUE.equals(request.getServicePhoneOn())
                        && positive(effectivePhone))
                .set(Lawyer::getServiceDocOn, Boolean.TRUE.equals(request.getServiceDocOn())
                        && positive(effectiveDocument))
                .set(Lawyer::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(Lawyer::getId, lawyer.getId()));
        log.info("律师改服务设置 lawyerId={} consult={} phone={} doc={}",
                lawyer.getId(), consult, phone, document);
    }

    /**
     * 价格合法性：null 或 >= 0 都允许（0 表示未定价，会被强制下架），负数拒绝。
     *
     * @param price 价格（分）
     * @throws BusinessException 405006 负数
     */
    private void assertPrice(Long price) {
        if (price != null && price < 0) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID);
        }
    }

    /**
     * 价格是否有效（> 0）。
     *
     * @param price 价格
     * @return 有效返回 true
     */
    private boolean positive(Long price) {
        return price != null && price > 0;
    }

    /**
     * null → 0（用于「库内现值是否有效」的判断）。
     *
     * @param value 原值
     * @return 非空值
     */
    private long zeroOf(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * null → 0。
     *
     * @param value 原值
     * @return 非空值
     */
    private Long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}
