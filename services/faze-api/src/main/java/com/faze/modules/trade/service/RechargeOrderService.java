/*
 * 职责：App 充值单编排层——下单、单笔查询、记录列表（第 6 段 B1 的三个接口的服务端）。
 * 调用方：modules/trade/controller/RechargeController。
 * 数据来源：recharge_record（读写）、sys_config（阈值，经 RechargeQuotaPolicy）、
 *           PaymentGatewayFactory（渠道）、balance_flow 汇总（详情里的余额）。
 * 注意：
 *   1. **校验顺序按工作流 B1 第 1 条固定**：登录态 → 金额 → 渠道 → 幂等 → 实名 → 单日限额 →
 *      单号/落单 → prepay → 回写。顺序不能换：先做幂等再去查限额，能省掉重复提交时的两次全表汇总；
 *      而实名必须在限额之前——未实名用户的额度是 0，先查限额会对每个未实名请求都白跑一遍 SUM。
 *   2. **本类不带 @Transactional**：事务边界在 RechargeCreateExecutor（落单/回写/置失败各自一个短事务），
 *      prepay() 是远程调用，必须留在事务之外（《后端代码规范》§7.3）。
 *   3. **失败时释放幂等占位、成功时绑定**：只有「单没建成」才释放；单已落库（哪怕随后 prepay 失败
 *      并置 failed）也先释放，因为用户确实需要能重新下一单，而 failed 单不占额度。
 *   4. **绝不把渠道异常原文返回客户端**：prepay 抛什么都在这里收敛成 405019
 *      （《后端代码规范》§5.4「绝不把 e.getMessage() 返回客户端」）。
 *   5. `detail` 里的 `balanceAfterCents` 走流水汇总（段 1 D1 同源），不读 user_balance 缓存。
 *   6. **「查不到就现查渠道」的入账部分留到 C3/D1**：本轮只调 `query()` 记日志、不就地改库——
 *      本地 pending 却被改成 success 而不写流水，会直接破坏「余额 = 流水汇总」这条铁律。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.pay.PaymentGateway;
import com.faze.infra.pay.PaymentGatewayFactory;
import com.faze.infra.pay.model.PrepayResult;
import com.faze.infra.pay.model.QueryResult;
import com.faze.infra.pay.model.RechargeOrder;
import com.faze.modules.trade.bo.RechargeCreateBO;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.vo.BalanceVO;
import com.faze.modules.trade.vo.RechargeCreateVO;
import com.faze.modules.trade.vo.RechargeDetailVO;
import com.faze.modules.trade.vo.RechargeListPageVO;
import com.faze.modules.trade.vo.RechargeRowVO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * App 充值单服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RechargeOrderService {

    /** 列表单页上限，与《接口规范》C 端列表口径一致。 */
    private static final int MAX_LIMIT = 50;

    /** 单笔查询时「现查渠道」的最小等待时间：刚下单的 pending 立刻查渠道一定是未支付。 */
    private static final Duration QUERY_CHANNEL_AFTER = Duration.ofSeconds(5);

    /** 渠道账单展示的商品标题（固定文案，不含用户信息）。 */
    private static final String ORDER_SUBJECT = "法泽账户充值";

    private final RechargeRecordMapper rechargeRecordMapper;

    private final RechargeCreateExecutor rechargeCreateExecutor;

    private final RechargeQuotaPolicy rechargeQuotaPolicy;

    private final RechargeIdempotencyGuard rechargeIdempotencyGuard;

    private final RechargePrepayStore rechargePrepayStore;

    private final PaymentGatewayFactory paymentGatewayFactory;

    private final RechargeSettleService rechargeSettleService;

    private final BalanceService balanceService;

    /**
     * 发起充值下单。
     *
     * <p>副作用：落一条 pending 单、缓存渠道支付参数、写幂等键；渠道侧创建一笔待支付交易。</p>
     *
     * @param userId         当前用户（来自 token，绝不从请求体取）
     * @param request        下单请求（金额 + 渠道）
     * @param idempotencyKey `Idempotency-Key` 头，可为空
     * @return 单号 + 金额 + 拉起渠道的参数 + 截止时间
     * @throws BusinessException 405014 金额非法 / 405013 渠道未启用 / 405020 未实名 /
     *                           405016 超出单日限额 / 405019 渠道下单失败
     */
    public RechargeCreateVO create(Long userId, RechargeCreateBO request, String idempotencyKey) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        // ① 金额区间（0 元单、负数、超出单笔上限都在这里拦掉）
        long amountCents = request.getAmountCents() == null ? 0L : request.getAmountCents();
        rechargeQuotaPolicy.ensureAmountAllowed(amountCents);
        // ② 渠道：未启用抛 405013，不回退到其他渠道
        PayChannel channel = PayChannel.of(request.getChannel());
        PaymentGateway gateway = paymentGatewayFactory.get(channel);
        // ③ 幂等占位：命中则直接返回原单，不新建、不重新 prepay
        Optional<Long> hit = rechargeIdempotencyGuard.tryStart(userId, idempotencyKey);
        if (hit.isPresent()) {
            return rebuildOnIdempotentHit(userId, hit.get());
        }
        boolean recordCreated = false;
        try {
            // ④ 实名：未通过且额度为 0 → 405020（是拦截，不是限额）
            Long dailyLimit = rechargeQuotaPolicy.ensureRealnameAndGetDailyLimit(userId);
            // ⑤ 单日金额与笔数限额（pending 计入）
            rechargeQuotaPolicy.ensureDailyQuota(userId, amountCents, dailyLimit);
            // ⑥⑦ 生成单号 + 落 pending 单（短事务）
            LocalDateTime expireAt = rechargeQuotaPolicy.resolveExpireAt();
            RechargeRecord record = rechargeCreateExecutor.createPending(
                    userId, amountCents, channel, expireAt);
            recordCreated = true;
            // ⑧ 调渠道 prepay（事务外）→ 回写 prepay_id → 缓存给客户端拉起用的参数
            PrepayResult prepay;
            try {
                prepay = gateway.prepay(toOrder(record), channel);
            } catch (RuntimeException e) {
                log.error("充值渠道下单失败 orderNo={} channel={}",
                        record.getOrderNo(), channel.getCode(), e);
                rechargeCreateExecutor.markFailed(record.getId(), "prepay 异常");
                rechargeIdempotencyGuard.release(userId, idempotencyKey);
                throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED);
            }
            rechargeCreateExecutor.attachPrepayId(record.getId(), prepay.getPrepayId());
            rechargePrepayStore.save(record.getOrderNo(), prepay.getPayParams());
            rechargeIdempotencyGuard.bind(userId, idempotencyKey, record.getId());
            return buildCreateVO(record, prepay.getPayParams());
        } catch (RuntimeException e) {
            if (!recordCreated) {
                // 单还没落库，把占位释放掉，用户可以立刻重试
                rechargeIdempotencyGuard.release(userId, idempotencyKey);
            }
            throw e;
        }
    }

    /**
     * 查询单笔充值单（App 从渠道返回后轮询用）。
     *
     * <p>副作用：本地仍 pending 且距下单超过 5 秒时，会向渠道发起一次主动查询（只记日志，
     * 不改本地状态——入账能力在 C3 接入后由补单链路统一处理）。</p>
     *
     * @param userId  当前用户
     * @param orderNo 平台充值单号
     * @return 单号、状态、金额、渠道、支付时间、当前余额
     * @throws BusinessException 405002 单不存在 / 405007 非本人订单
     */
    public RechargeDetailVO detail(Long userId, String orderNo) {
        RechargeRecord record = requireOwnedRecord(userId, orderNo);
        queryChannelIfStalePending(record);
        BalanceVO balance = balanceService.getBalance(userId);
        RechargeDetailVO vo = new RechargeDetailVO();
        vo.setOrderNo(record.getOrderNo());
        RechargeStatus status = RechargeStatus.of(record.getStatus());
        vo.setStatus(status.getCode());
        vo.setStatusKey(status.i18nKey());
        vo.setAmountCents(record.getAmountCents() == null ? 0L : record.getAmountCents());
        vo.setChannel(record.getChannel());
        vo.setChannelKey(PayChannel.of(record.getChannel()).i18nKey());
        vo.setPaidAt(record.getPaidAt());
        vo.setCreatedAt(record.getCreatedAt());
        vo.setExpireAt(record.getExpireAt());
        vo.setBalanceAfterCents(balance.getAvailableCents() == null ? 0L : balance.getAvailableCents());
        return vo;
    }

    /**
     * 查询当前用户的充值记录（游标分页）。
     *
     * @param userId 当前用户
     * @param cursor 上一页最后一条 id；首页传 null
     * @param limit  每页条数（1~50，越界自动收敛）
     * @return 本页条目 + 下一页游标
     */
    public RechargeListPageVO list(Long userId, Long cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        var wrapper = Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getUserId, userId);
        if (cursor != null) {
            wrapper.lt(RechargeRecord::getId, cursor);
        }
        // 多查一条判断 hasMore，避免额外 count(*)（案例列表同一做法）
        wrapper.orderByDesc(RechargeRecord::getId).last("LIMIT " + (size + 1));
        List<RechargeRecord> records = rechargeRecordMapper.selectList(wrapper);
        boolean hasMore = records.size() > size;
        List<RechargeRowVO> page = (hasMore ? records.subList(0, size) : records)
                .stream().map(this::toRowVO).toList();
        RechargeListPageVO result = new RechargeListPageVO();
        result.setList(page);
        result.setHasMore(hasMore);
        // 游标本页最后一条的 id（page 非空时才有）；不是 records.get(size-1)，那会在 hasMore=false 时越界
        result.setNextCursor(hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null);
        return result;
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /**
     * 幂等命中时用已存在的单重建响应（不重新调 prepay，见 RechargePrepayStore 第 2 条）。
     *
     * @param userId   当前用户
     * @param recordId 幂等键绑定的充值单 id
     * @return 与首次下单同构的响应
     * @throws BusinessException 405002 单不存在 / 405007 非本人订单
     */
    private RechargeCreateVO rebuildOnIdempotentHit(Long userId, Long recordId) {
        RechargeRecord record = rechargeRecordMapper.selectById(recordId);
        if (record == null) {
            // 幂等键还在但单没了（极端脏数据）：按单不存在处理，让客户端重新下单
            log.warn("幂等命中但充值单不存在 userId={} recordId={}", userId, recordId);
            throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND);
        }
        if (!userId.equals(record.getUserId())) {
            throw new BusinessException(ErrorCode.TRADE_NOT_ORDER_OWNER);
        }
        Map<String, String> payParams = rechargePrepayStore.load(record.getOrderNo());
        return buildCreateVO(record, payParams);
    }

    /**
     * 取当前用户名下的充值单，做归属校验。
     *
     * @param userId  当前用户
     * @param orderNo 平台充值单号
     * @return 充值单
     * @throws BusinessException 405002 单不存在 / 405007 非本人订单
     */
    private RechargeRecord requireOwnedRecord(Long userId, String orderNo) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND);
        }
        RechargeRecord record = rechargeRecordMapper.selectOne(Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getOrderNo, orderNo.trim()));
        if (record == null) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND);
        }
        if (!userId.equals(record.getUserId())) {
            // 不区分「不存在」与「不是你的」会让攻击者能枚举单号，所以两者错误码不同：
            // 存在但不属于你 → 405007（403），不存在 → 405002
            throw new BusinessException(ErrorCode.TRADE_NOT_ORDER_OWNER);
        }
        return record;
    }

    /**
     * 本地仍 pending 且已过等待窗口时，向渠道查一次。
     *
     * <p>**本轮只读不写**：渠道已成功但本地 pending 说明回调丢了（或晚到），
     * 正确的处置是「补单入账」（D1/C3），而不是在这里顺手改状态——改状态必须与写流水同事务，
     * 否则会出现「状态 success 但余额没加」的坏数据。</p>
     *
     * @param record 充值单
     */
    private void queryChannelIfStalePending(RechargeRecord record) {
        if (!RechargeStatus.PENDING.getCode().equals(record.getStatus())) {
            return;
        }
        LocalDateTime createdAt = record.getCreatedAt();
        if (createdAt == null
                || createdAt.plus(QUERY_CHANNEL_AFTER).isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            return;
        }
        try {
            PayChannel channel = PayChannel.of(record.getChannel());
            QueryResult result = paymentGatewayFactory.get(channel).query(record.getOrderNo());
            if (result.isExists() && result.getStatus() == RechargeStatus.SUCCESS) {
                // 渠道确认已收款而本地仍 pending → 回调丢了，走同一入账链路（幂等，重复查也不会加两次）
                RechargeSettleService.Outcome outcome = rechargeSettleService.settleByCallback(
                        record.getOrderNo(), result.getChannelTxnId(),
                        record.getAmountCents() == null ? 0L : record.getAmountCents(),
                        result.getPaidAt(), null);
                log.warn("主动查渠道确认已收款并补入账 orderNo={} outcome={}", record.getOrderNo(), outcome);
            }
        } catch (RuntimeException e) {
            // 查单失败不影响本次查询返回：用户轮询时会再查一次
            log.warn("充值单主动查渠道失败 orderNo={}", record.getOrderNo(), e);
        }
    }

    /**
     * 实体 → 下单响应。
     *
     * @param record    充值单
     * @param payParams 拉起渠道的参数
     * @return 下单响应
     */
    private RechargeCreateVO buildCreateVO(RechargeRecord record, Map<String, String> payParams) {
        PayChannel channel = PayChannel.of(record.getChannel());
        RechargeCreateVO vo = new RechargeCreateVO();
        vo.setOrderNo(record.getOrderNo());
        vo.setAmountCents(record.getAmountCents() == null ? 0L : record.getAmountCents());
        vo.setChannel(channel.getCode());
        vo.setChannelKey(channel.i18nKey());
        vo.setPayParams(payParams == null ? Map.of() : payParams);
        vo.setExpireAt(record.getExpireAt());
        return vo;
    }

    /**
     * 实体 → 列表行。
     *
     * @param record 充值单
     * @return 列表行
     */
    private RechargeRowVO toRowVO(RechargeRecord record) {
        RechargeStatus status = RechargeStatus.of(record.getStatus());
        PayChannel channel = PayChannel.of(record.getChannel());
        RechargeRowVO row = new RechargeRowVO();
        row.setId(record.getId());
        row.setOrderNo(record.getOrderNo());
        row.setAmountCents(record.getAmountCents() == null ? 0L : record.getAmountCents());
        row.setChannel(channel.getCode());
        row.setChannelKey(channel.i18nKey());
        row.setStatus(status.getCode());
        row.setStatusKey(status.i18nKey());
        row.setCreatedAt(record.getCreatedAt());
        row.setPaidAt(record.getPaidAt());
        return row;
    }

    /**
     * 实体 → 渠道下单视图。
     *
     * @param record 充值单
     * @return 渠道下单入参
     */
    private RechargeOrder toOrder(RechargeRecord record) {
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(record.getOrderNo());
        order.setUserId(record.getUserId());
        order.setAmountCents(record.getAmountCents());
        order.setChannel(PayChannel.of(record.getChannel()));
        order.setExpireAt(record.getExpireAt());
        order.setSubject(ORDER_SUBJECT);
        return order;
    }
}
