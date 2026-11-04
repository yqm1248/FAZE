/*
 * 职责：手工充值 / 调账（第 1 段 D4，PC A-07 的服务端）。
 * 调用方：modules/trade/controller/AdminWalletController。
 * 数据来源：user_balance（加锁读）、balance_flow（写）。
 * 注意：
 *   1. **必须带幂等键**（`Idempotency-Key` 头）。充值可重复提交是真实场景
 *      （网络重试、运营连点），没有幂等键会重复入账，这是资金事故。
 *   2. **`biz_id` 必须是非空整数**。`balance_flow.biz_id` 是 BIGINT，
 *      装不下 `RC20261101000045` 这种字符串单号；而唯一键对 NULL 不生效，
 *      给 NULL 等于幂等失效。本类用「幂等键的 SHA-256 前 8 字节」做稳定整数 biz_id：
 *      同一幂等键 → 同一 biz_id → 唯一键拦重复；跨进程/跨实例结果一致。
 *   3. 调账扣减（负向）**必须复用余额校验**：余额不足要拒绝，
 *      否则调账能把余额调成负数，对账永远不平。
 *   4. 事务隔离级别用 READ_COMMITTED（与扣费引擎同理：锁后复查幂等需要看到最新提交）。
 *   5. 审计：`@OperationLog` 落 operation_log，detail 存 before/after（规范 §10.4）。
 *      没有 before/after 的审计在出事后无法还原，等于没记。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.vo.AccountAuthSnapshot;
import com.faze.modules.trade.bo.AdjustBO;
import com.faze.modules.trade.bo.RechargeBO;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.vo.RechargeResultVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 手工充值 / 调账服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RechargeService {

    private static final String MODULE_TRADE = "trade";
    private static final String BIZ_RECHARGE = "recharge";
    private static final String BIZ_ADJUST = "adjust";

    private final UserBalanceMapper userBalanceMapper;
    private final BalanceFlowMapper balanceFlowMapper;
    private final BalanceService balanceService;
    private final AccountQueryService accountQueryService;

    /**
     * 锁余额行；行不存在但用户存在时先补建一行 0 余额再锁。
     *
     * <p><b>为什么需要这一步</b>：`user_balance` 是**惰性建行**的（只有查过余额的用户才有行）。
     * 于是「运营给一个只注册过、还没查过余额的用户手工充值」会因为没有行而报 401004 ——
     * 这是真实运营场景，不是脏数据。补建的行余额为 0、不产生任何流水，所以不违反
     * 「余额只能由流水推导」（V5 迁移铁律）。</p>
     *
     * <p>顺序不能反：必须先确认**用户存在**再建行，否则打错一个 userId 就会凭空造出一条
     * 幽灵余额户，对账时永远平不掉。</p>
     *
     * @param userId 目标用户
     * @return 已加行锁的余额行
     * @throws BusinessException 401004 用户不存在或已注销
     */
    private UserBalance lockOrCreateBalanceRow(Long userId) {
        UserBalance cache = userBalanceMapper.selectByUserIdForUpdate(userId);
        if (cache != null) {
            return cache;
        }
        AccountAuthSnapshot snapshot = accountQueryService.findAuthSnapshot(userId).orElse(null);
        if (snapshot == null || snapshot.deleted()) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        balanceService.ensureRow(userId);
        cache = userBalanceMapper.selectByUserIdForUpdate(userId);
        if (cache == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return cache;
    }

    /**
     * 手工充值。
     *
     * <p>前置：金额 > 0；幂等键必填；凭证号必填。</p>
     * <p>副作用：写一条 recharge 流水、更新余额缓存、写 operation_log。</p>
     *
     * @param request        充值请求
     * @param idempotencyKey 幂等键（HTTP 头，缺失时回退用 bizNo）
     * @return 入账结果
     * @throws BusinessException 405006 金额非法 / 401002 缺幂等键或凭证号 / 401004 用户余额行不存在
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    @OperationLog(module = MODULE_TRADE, action = "MANUAL_RECHARGE", targetType = "USER_BALANCE")
    public RechargeResultVO recharge(RechargeBO request, String idempotencyKey) {
        if (request.getAmountCents() == null || request.getAmountCents() <= 0) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID);
        }
        String key = resolveIdempotencyKey(idempotencyKey, request.getBizNo());
        long bizId = stableBizId(key);
        String detail = "{\"bizNo\":\"" + escape(request.getBizNo())
                + "\",\"reason\":\"" + escape(request.getReason())
                + "\",\"amountCents\":" + request.getAmountCents() + "}";
        RechargeResultVO vo = applyDelta(request.getUserId(), BIZ_RECHARGE, bizId,
                request.getAmountCents(), request.getReason() + " / 凭证号 " + request.getBizNo(),
                request.getRemark(), detail);
        vo.setBizNo(request.getBizNo());
        return vo;
    }

    /**
     * 手工调账（正负均可）。
     *
     * <p>前置：变动金额非 0；幂等键必填；原因是必填。</p>
     * <p>副作用：写一条 adjust 流水、更新余额缓存、写 operation_log。
     * 负向调账时余额不足会被拒绝。</p>
     *
     * @param request        调账请求
     * @param idempotencyKey 幂等键（HTTP 头，header 缺失时直接拒绝）
     * @return 调账结果
     * @throws BusinessException 405006 金额非法 / 401002 缺幂等键 / 405001 扣减时余额不足
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    @OperationLog(module = MODULE_TRADE, action = "MANUAL_ADJUST", targetType = "USER_BALANCE")
    public RechargeResultVO adjust(AdjustBO request, String idempotencyKey) {
        if (request.getDeltaCents() == null || request.getDeltaCents() == 0L) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID);
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            // 调账没有天然唯一的业务号，缺幂等键就必须拒绝（否则无法防重放）
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING);
        }
        long bizId = stableBizId(idempotencyKey);
        String detail = "{\"reason\":\"" + escape(request.getReason())
                + "\",\"deltaCents\":" + request.getDeltaCents() + "}";
        return applyDelta(request.getUserId(), BIZ_ADJUST, bizId, request.getDeltaCents(),
                request.getReason(), request.getRemark(), detail);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 统一的入账/扣减实现（正负都走这里）。
     *
     * @param userId   目标用户
     * @param bizType  recharge / adjust
     * @param bizId    由幂等键派生的稳定整数
     * @param delta    变动金额（分，正负均可，不能为 0）
     * @param remark   流水备注
     * @param extra    额外备注
     * @param detail   审计明细（before/after 在内部补）
     * @return 结果
     */
    private RechargeResultVO applyDelta(Long userId, String bizType, long bizId, long delta,
                                        String remark, String extra, String detail) {
        // 任务 1：幂等预查（锁外快速路径）。
        BalanceFlow existing = findFlow(userId, bizType, bizId);
        if (existing != null) {
            return duplicated(existing);
        }
        // 任务 2：锁余额行（行不存在且用户存在时补建，见 lockOrCreateBalanceRow）。
        UserBalance cache = lockOrCreateBalanceRow(userId);
        // 任务 3：锁后复查幂等（READ_COMMITTED 下能看到最新提交）。
        BalanceFlow afterLock = findFlow(userId, bizType, bizId);
        if (afterLock != null) {
            return duplicated(afterLock);
        }
        // 任务 4：负向变动要校验余额（调账不能把余额调成负数）。
        long available = cache.getBalanceCents() == null ? 0L : cache.getBalanceCents();
        long frozen = cache.getFrozenCents() == null ? 0L : cache.getFrozenCents();
        long totalBefore = available + frozen;
        if (delta < 0 && available + delta < 0) {
            throw new BusinessException(ErrorCode.TRADE_BALANCE_INSUFFICIENT);
        }
        // 任务 5：插流水。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        BalanceFlow flow = new BalanceFlow();
        flow.setOwnerType(BalanceService.OWNER_USER);
        flow.setOwnerId(userId);
        flow.setBizType(bizType);
        flow.setBizId(bizId);
        flow.setDeltaCents(delta);
        flow.setBalanceAfter(totalBefore + delta);
        flow.setRemark(StringUtils.hasText(extra) ? remark + "；" + extra : remark);
        flow.setCreatedAt(now);
        try {
            balanceFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            BalanceFlow first = findFlow(userId, bizType, bizId);
            if (first == null) {
                throw e;
            }
            return duplicated(first);
        }
        // 任务 6：更新缓存（可用额 = 原可用 + delta）。
        cache.setBalanceCents(available + delta);
        cache.setUpdatedAt(now);
        userBalanceMapper.updateById(cache);
        // 任务 7：审计明细补 before/after（操作人由切面从 AuditContext 取）。
        AuditContext.mark(MODULE_TRADE,
                "recharge".equals(bizType) ? "MANUAL_RECHARGE" : "MANUAL_ADJUST",
                "USER_BALANCE", String.valueOf(userId),
                detail.substring(0, detail.length() - 1)
                        + ",\"before\":" + totalBefore + ",\"after\":" + (totalBefore + delta) + "}");
        log.info("手工入账成功 userId={} bizType={} delta={} totalAfter={}",
                userId, bizType, delta, totalBefore + delta);
        RechargeResultVO vo = new RechargeResultVO();
        vo.setFlowId(flow.getId());
        vo.setBalanceCents(totalBefore + delta);
        vo.setCreatedAt(flow.getCreatedAt());
        vo.setDuplicated(false);
        return vo;
    }

    /**
     * 幂等命中时的返回。
     *
     * <p>**幂等命中也要写审计**：重放请求本身是「有人又点了/又重试了」的事实，
     * 审计里要能看出「这次没入账、因为之前入过」。只记首次入账会让运营看到
     * 「充了 100 但只有一条记录」时无法判断是不是重复提交。</p>
     *
     * @param flow 已存在的流水
     * @return 幂等结果
     */
    private RechargeResultVO duplicated(BalanceFlow flow) {
        log.info("手工入账幂等命中 ownerId={} bizType={} bizId={}",
                flow.getOwnerId(), flow.getBizType(), flow.getBizId());
        AuditContext.mark(MODULE_TRADE,
                BIZ_RECHARGE.equals(flow.getBizType()) ? "MANUAL_RECHARGE" : "MANUAL_ADJUST",
                "USER_BALANCE", String.valueOf(flow.getOwnerId()),
                "{\"duplicated\":true,\"bizId\":" + flow.getBizId()
                        + ",\"balanceAfter\":" + flow.getBalanceAfter() + "}");
        RechargeResultVO vo = new RechargeResultVO();
        vo.setFlowId(flow.getId());
        vo.setBalanceCents(flow.getBalanceAfter());
        vo.setCreatedAt(flow.getCreatedAt());
        vo.setDuplicated(true);
        return vo;
    }

    /**
     * 取幂等键：优先 HTTP 头，回退到凭证号；都空则拒绝。
     *
     * @param headerKey   Idempotency-Key 头
     * @param bizNo       线下凭证号
     * @return 非空幂等键
     */
    private String resolveIdempotencyKey(String headerKey, String bizNo) {
        if (StringUtils.hasText(headerKey)) {
            return headerKey.trim();
        }
        if (StringUtils.hasText(bizNo)) {
            return "bizno:" + bizNo.trim();
        }
        throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING);
    }

    /**
     * 把幂等键映射成稳定的非空整数 biz_id（SHA-256 前 8 字节）。
     *
     * <p>为什么不用 `String.hashCode()`：它只有 32 位且跨 JVM 版本实现不保证一致；
     * SHA-256 前 8 字节是 64 位、稳定、碰撞概率可忽略。</p>
     *
     * @param key 幂等键
     * @return 正整数 biz_id
     */
    private long stableBizId(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFFL);
            }
            return value == Long.MIN_VALUE ? 1L : Math.abs(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 按业务键查流水。
     *
     * @param userId  用户主键
     * @param bizType 业务类型
     * @param bizId   业务主键
     * @return 已存在流水；无则 null
     */
    private BalanceFlow findFlow(Long userId, String bizType, long bizId) {
        return balanceFlowMapper.selectOne(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerType, BalanceService.OWNER_USER)
                .eq(BalanceFlow::getOwnerId, userId)
                .eq(BalanceFlow::getBizType, bizType)
                .eq(BalanceFlow::getBizId, bizId));
    }

    /**
     * 转义 JSON 字符串里的引号与反斜杠（detail 是 JSON 列，写坏会插入失败）。
     *
     * @param raw 原文
     * @return 转义后的文本
     */
    private String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
