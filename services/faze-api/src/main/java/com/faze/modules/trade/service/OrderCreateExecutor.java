/*
 * 职责：订单创建的事务执行器（一个事务内：插订单 + 写状态流水）。
 * 调用方：modules/trade/service/OrderService#create（编排层，负责幂等占位，不带事务）。
 * 数据来源：lawyer（取价与审核状态）、sys_config（平台费率、在线开关）、orders（写）。
 * 注意：
 *   1. **取价一律在服务端**（工作流 G2.1 第 1 条）：价格从 `lawyer` 表的三个价格字段读，
 *      客户端传的任何金额都被忽略（`OrderCreateBO` 根本没有金额字段）。
 *   2. **价格为空的律师不许下单成 0 元单**：`null` 与 `<= 0` 都抛 405009。
 *      这条是明确判据，不许用「0 元也能建单，反正没人接」糊过去。
 *   3. `platform_fee_rate` 从 `sys_config` 读（占位 `0.15`），**公式写死、值可配**；
 *      读不到或格式非法时回退 0.15 并 warn，商品价格不能因为配置丢失就算成错数。
 *   4. `platform_fee_cents` / `lawyer_income_cents` **只在订单上记账**，段 2 不产生流水、
 *      不动 lawyer_balance（结算属段 5）。
 *   5. 单号撞唯一键时重试（最多 3 次）：Redis 被清空/重启后序列会从 1 开始，
 *      真实运维场景里会撞上已用单号；静默 500 不如换号重试。
 *   6. 事务内**不做远程调用**（没有 HTTP/RPC）；这里只有 DB 与一次 Redis INCR（生成单号）。
 *   7. **不许给自己下单**（DEF-014）：下单人 `app_user.id` 与 `lawyer.user_id` 相等时抛 405031。
 *      这条不是「防手滑」，是防资金空转——理由写在 `create` 的任务 3 注释里。
 */
package com.faze.modules.trade.service;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.bo.OrderCreateBO;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.enums.ServiceType;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.rule.OrderTimeoutPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.faze.infra.mq.TaskQueueService;

/**
 * 订单创建事务执行器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCreateExecutor {

    /** 平台费率配置键（值形如 {@code 0.15}）。 */
    public static final String CONFIG_PLATFORM_FEE_RATE = "platform_fee_rate";

    /** 是否要求律师在线才可下单（默认 false：律师可能只是没开 App，不能写死误杀）。 */
    public static final String CONFIG_REQUIRE_LAWYER_ONLINE = "order_require_lawyer_online";

    /** 费率缺失时的兜底值，与 `database/seed/seg1_config.sql` 的种子一致。 */
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.15");

    /** 订单金额上限（分）= 100 万元；超过一律按金额非法拒（防误操作/恶意超大单）。 */
    private static final long MAX_AMOUNT_CENTS = 100_000_000L;

    /** 单号撞唯一键时的最大尝试次数。 */
    private static final int MAX_ORDER_NO_ATTEMPT = 3;

    /** 律师审核状态：已通过。 */
    private static final String LAWYER_AUDIT_APPROVED = "approved";

    /** 律师在线状态：在线。 */
    private static final String LAWYER_ONLINE = "online";

    private final OrdersMapper ordersMapper;
    private final OrderNoGenerator orderNoGenerator;
    private final OrderStatusLogService orderStatusLogService;
    private final LawyerQueryService lawyerQueryService;
    private final SysConfigQueryService sysConfigQueryService;
    private final TaskQueueService taskQueueService;

    /**
     * 创建订单（PENDING，不扣款）。
     *
     * <p>前置：调用方已完成幂等占位与登录校验。本方法只负责「校验律师 → 取价 → 落库 → 记账 → 写流水」。</p>
     * <p>副作用：插入一行 orders、插入一行 order_status_log。</p>
     *
     * @param userId  下单用户 ID（来自 token）
     * @param request 下单请求
     * @return 新建的订单（含自增主键）
     * @throws BusinessException 405008 律师不可接单 / 405031 给自己下单 /
     *                           405009 服务未开通或未定价 / 405010 律师不在线 / 405006 金额超限
     */
    @Transactional(rollbackFor = Exception.class)
    public Orders create(Long userId, OrderCreateBO request) {
        // 任务 1：服务类型必须本段可下单（case 直接拒）
        ServiceType serviceType = ServiceType.requireOrderable(request.getServiceType());

        // 任务 2：律师必须存在、已通过审核
        Lawyer lawyer = lawyerQueryService.findById(request.getLawyerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_LAWYER_UNAVAILABLE));
        if (!LAWYER_AUDIT_APPROVED.equals(lawyer.getAuditStatus())) {
            throw new BusinessException(ErrorCode.TRADE_LAWYER_UNAVAILABLE);
        }

        // 任务 3：不许给自己下单（DEF-014，2026-11-13 补）
        // **比较的是 app_user.id，不是 lawyer.id**：`lawyer.user_id` 才是「这个律师账号背后的人」。
        // 不拦的后果不是体验问题：律师用自己的号下单 → 余额付款扣钱 → 结算后律师收入流回
        // 自己的 lawyer_balance，资金转一圈回到原点，而平台按费率记的服务费是凭空多出来的
        // 一笔应收。同时这条单在后台所有「订单 / 结算 / 对账」列表里都是正常数据，查不出来。
        if (lawyer.getUserId() != null && lawyer.getUserId().equals(userId)) {
            log.warn("拒绝自下单 userId={} lawyerId={}", userId, lawyer.getId());
            throw new BusinessException(ErrorCode.TRADE_SELF_ORDER_FORBIDDEN,
                    "下单人与承办律师是同一 app_user userId=" + userId + " lawyerId=" + lawyer.getId());
        }

        // 任务 4：是否要求在线才可下单——**开关在 sys_config，不写死**（工作流 G2.1「卡住怎么办」）
        if (sysConfigQueryService.getBoolean(CONFIG_REQUIRE_LAWYER_ONLINE, false)
                && !LAWYER_ONLINE.equals(lawyer.getOnlineStatus())) {
            throw new BusinessException(ErrorCode.TRADE_LAWYER_OFFLINE);
        }

        // 任务 5：取价（服务未上架 / 价格为空都拒，不许 0 元单）
        long amountCents = resolvePriceCents(lawyer, serviceType);

        // 任务 6：算平台服务费与律师收入（只记账，不产生流水）
        long platformFeeCents = calcPlatformFeeCents(amountCents);
        long lawyerIncomeCents = amountCents - platformFeeCents;

        // 任务 7：生成单号并落库；撞唯一键则换号重试
        for (int attempt = 1; attempt <= MAX_ORDER_NO_ATTEMPT; attempt++) {
            Orders order = buildOrder(userId, request, lawyer, serviceType,
                    amountCents, platformFeeCents, lawyerIncomeCents);
            try {
                ordersMapper.insert(order);
            } catch (DuplicateKeyException e) {
                log.warn("订单号撞唯一键，换号重试 attempt={} orderNo={}", attempt, order.getOrderNo());
                if (attempt == MAX_ORDER_NO_ATTEMPT) {
                    throw e;
                }
                continue;
            }
            // 任务 8：写状态流水（订单创建：from = NULL → to = pending）
            orderStatusLogService.record(order.getId(), null, OrderStatus.PENDING,
                    OperatorRole.USER, userId, "用户下单");
            // 任务 9：投递 15 分钟关单任务（与下单同事务：下单回滚，任务也不该留下）
            taskQueueService.enqueue(TaskQueueService.TYPE_ORDER_CLOSE, order.getOrderNo(),
                    OrderTimeoutPolicy.payExpireAt(order.getCreatedAt()), MDC.get(R.TRACE_ID_MDC_KEY));
            log.info("订单创建成功 orderId={} orderNo={} userId={} lawyerId={} amountCents={}",
                    order.getId(), order.getOrderNo(), userId, lawyer.getId(), amountCents);
            return order;
        }
        // 循环必然 return 或 throw；这行只为让编译器安心，逻辑上不可达
        throw new BusinessException(ErrorCode.COMMON_INTERNAL, "订单号生成失败");
    }

    /**
     * 组装待插入的订单实体。
     *
     * @return 未落库的订单（id 为空，单号已生成）
     */
    private Orders buildOrder(Long userId, OrderCreateBO request, Lawyer lawyer, ServiceType serviceType,
                              long amountCents, long platformFeeCents, long lawyerIncomeCents) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Orders order = new Orders();
        order.setOrderNo(orderNoGenerator.nextOrderNo());
        order.setUserId(userId);
        order.setLawyerId(lawyer.getId());
        order.setServiceType(serviceType.getCode());
        order.setAmountCents(amountCents);
        order.setPlatformFeeCents(platformFeeCents);
        order.setLawyerIncomeCents(lawyerIncomeCents);
        order.setStatus(OrderStatus.PENDING.getCode());
        order.setRemark(request.getRemark());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    /**
     * 按服务类型取价，并校验「已上架 + 有价格」。
     *
     * @param lawyer      律师行
     * @param serviceType 服务类型
     * @return 价格（分，> 0）
     * @throws BusinessException 405009 未上架或未定价；405006 价格超上限
     */
    private long resolvePriceCents(Lawyer lawyer, ServiceType serviceType) {
        boolean listed;
        Long price;
        switch (serviceType) {
            case CONSULT_TEXT -> {
                listed = Boolean.TRUE.equals(lawyer.getServiceTextOn());
                price = lawyer.getConsultPriceCents();
            }
            case CONSULT_PHONE -> {
                listed = Boolean.TRUE.equals(lawyer.getServicePhoneOn());
                price = lawyer.getPhonePriceCents();
            }
            case DOCUMENT -> {
                listed = Boolean.TRUE.equals(lawyer.getServiceDocOn());
                price = lawyer.getDocumentPriceCents();
            }
            default -> throw new BusinessException(ErrorCode.TRADE_SERVICE_UNAVAILABLE);
        }
        if (!listed) {
            throw new BusinessException(ErrorCode.TRADE_SERVICE_UNAVAILABLE);
        }
        if (price == null || price <= 0) {
            throw new BusinessException(ErrorCode.TRADE_SERVICE_UNAVAILABLE);
        }
        if (price > MAX_AMOUNT_CENTS) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID);
        }
        return price;
    }

    /**
     * 按 `sys_config.platform_fee_rate` 计算平台服务费（四舍五入到分）。
     *
     * @param amountCents 订单金额（分）
     * @return 平台服务费（分），不会超过订单金额
     */
    private long calcPlatformFeeCents(long amountCents) {
        BigDecimal rate = readFeeRate();
        long fee = rate.multiply(BigDecimal.valueOf(amountCents))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        // 防御：费率配置被改成 > 1 时（readFeeRate 已拦，这是最后一道）不能出现负的律师收入
        return Math.min(fee, amountCents);
    }

    /**
     * 读平台费率。
     *
     * @return 合法费率（0~1）；缺失或非法时回退 0.15 并 warn
     */
    private BigDecimal readFeeRate() {
        String raw = sysConfigQueryService.getString(CONFIG_PLATFORM_FEE_RATE, DEFAULT_FEE_RATE.toPlainString());
        try {
            BigDecimal rate = new BigDecimal(raw.trim());
            if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                log.warn("platform_fee_rate 超出 0~1，回退默认值 value={}", raw);
                return DEFAULT_FEE_RATE;
            }
            return rate;
        } catch (NumberFormatException e) {
            log.warn("platform_fee_rate 不是合法小数，回退默认值 value={}", raw);
            return DEFAULT_FEE_RATE;
        }
    }
}
