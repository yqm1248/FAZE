/*
 * 职责：A-07 余额与充值（钱包概览、资金流水分页、充值 / 调账薄封装）。
 * 调用方：modules/trade/admin/controller/AdminWalletLedgerController（查询）、
 *         AdminWalletOpsController（写）。
 * 数据来源：faze.balance_flow（权威）、faze.user_balance（仅 frozen）、
 *           modules/account/vo/AccountAuthSnapshot（跨域只读，校验用户存在）。
 * 注意：
 *   1. **钱包余额只认流水汇总**（`SUM(delta_cents)`），绝不读 user_balance.available_cents；
 *      frozen_cents 是唯一例外（段 1 没有冻结流水可推）。锚点 1-A6 直接拿它验收。
 *   2. **收 / 调账复用 `RechargeService`，不另写入账链路**：幂等、锁行、负向余额校验、
 *      审计动作（MANUAL_RECHARGE / MANUAL_ADJUST）全部沿用 D4 的实现。
 *      因此本类的写方法**不再标 `@OperationLog`**——否则一次充值会落两条同 action 的审计，
 *      反而让「一次操作一条审计」这条约定失效。写方法只是把路径 userId 装进既有 BO。
 *   3. 跨域只调 account 域的只读接口 `AccountQueryService`（规范 §2.3），不注入它域 Mapper。
 *   4. 金额字段全部原生 long（JSON 数字），ID 全部 Long（序列化为字符串）。
 */
package com.faze.modules.trade.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.util.Pages;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.vo.AccountAuthSnapshot;
import com.faze.modules.trade.admin.bo.AdminAdjustBO;
import com.faze.modules.trade.admin.bo.AdminFlowQueryBO;
import com.faze.modules.trade.admin.bo.AdminRechargeBO;
import com.faze.modules.trade.admin.vo.AdminFlowRowVO;
import com.faze.modules.trade.admin.vo.AdminRechargeResultVO;
import com.faze.modules.trade.admin.vo.AdminWalletVO;
import com.faze.modules.trade.bo.AdjustBO;
import com.faze.modules.trade.bo.RechargeBO;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.service.BalanceService;
import com.faze.modules.trade.service.RechargeService;
import com.faze.modules.trade.vo.RechargeResultVO;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 管理端钱包服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminWalletService {

    private static final String BIZ_RECHARGE = "recharge";
    private static final String BIZ_CONSUME = "consume";

    private final BalanceFlowMapper balanceFlowMapper;
    private final UserBalanceMapper userBalanceMapper;
    private final RechargeService rechargeService;
    private final AccountQueryService accountQueryService;

    /**
     * 查询钱包概览。
     *
     * <p>`balanceCents` = 流水净额；`frozenCents` 读缓存；累计充值 / 消费按 biz_type 汇总。</p>
     *
     * @param userId 用户主键
     * @return 钱包概览
     * @throws BusinessException 401004 用户不存在或已注销
     */
    public AdminWalletVO getWallet(Long userId) {
        requireUser(userId);
        long total = nonNull(balanceFlowMapper.sumDeltaByOwner(BalanceService.OWNER_USER, userId));
        UserBalance cache = userBalanceMapper.selectOne(Wrappers.<UserBalance>lambdaQuery()
                .eq(UserBalance::getUserId, userId));
        long frozen = cache == null ? 0L : nonNull(cache.getFrozenCents());
        long recharge = nonNull(balanceFlowMapper.sumDeltaByOwnerAndBizType(
                BalanceService.OWNER_USER, userId, BIZ_RECHARGE));
        long consume = nonNull(balanceFlowMapper.sumDeltaByOwnerAndBizType(
                BalanceService.OWNER_USER, userId, BIZ_CONSUME));
        AdminWalletVO vo = new AdminWalletVO();
        vo.setUserId(userId);
        vo.setBalanceCents(total);
        vo.setFrozenCents(frozen);
        vo.setTotalRechargeCents(recharge);
        // 消费流水是负数，展示口径取绝对值
        vo.setTotalConsumeCents(Math.abs(consume));
        vo.setLastFlowAt(balanceFlowMapper.selectLastFlowTime(BalanceService.OWNER_USER, userId));
        return vo;
    }

    /**
     * 分页查询某用户的资金流水（created_at DESC, id DESC）。
     *
     * @param query 查询条件（userId 必填）
     * @return 分页结果
     */
    public IPage<AdminFlowRowVO> listFlows(AdminFlowQueryBO query) {
        requireUser(query.getUserId());
        IPage<BalanceFlow> page = balanceFlowMapper.selectPage(
                Pages.of(query.getPageNum(), query.getPageSize()),
                Wrappers.<BalanceFlow>lambdaQuery()
                        .eq(BalanceFlow::getOwnerType, BalanceService.OWNER_USER)
                        .eq(BalanceFlow::getOwnerId, query.getUserId())
                        .eq(StringUtils.hasText(query.getBizType()), BalanceFlow::getBizType, query.getBizType())
                        .ge(query.getBeginTime() != null, BalanceFlow::getCreatedAt, query.getBeginTime())
                        .le(query.getEndTime() != null, BalanceFlow::getCreatedAt, query.getEndTime())
                        .orderByDesc(BalanceFlow::getCreatedAt)
                        .orderByDesc(BalanceFlow::getId));
        return page.convert(this::toFlowRow);
    }

    /**
     * 管理端充值（复用 D4 的入账链路）。
     *
     * <p>副作用：写 balance_flow + 更新 user_balance 缓存 + 写 operation_log
     * （action=MANUAL_RECHARGE，由 RechargeService 的注解完成）。</p>
     *
     * @param userId         目标用户（来自路径）
     * @param request        充值请求（body 不含 userId）
     * @param idempotencyKey 幂等键（HTTP 头）
     * @return 入账结果
     * @throws BusinessException 405006 金额非法 / 401002 缺幂等键 / 401004 用户或余额行不存在
     */
    public AdminRechargeResultVO recharge(Long userId, AdminRechargeBO request, String idempotencyKey) {
        requireUser(userId);
        RechargeBO bo = new RechargeBO();
        bo.setUserId(userId);
        bo.setAmountCents(request.getAmountCents());
        bo.setReason(request.getReason());
        bo.setBizNo(request.getBizNo());
        bo.setRemark(request.getRemark());
        RechargeResultVO result = rechargeService.recharge(bo, idempotencyKey);
        log.info("管理端充值 userId={} amountCents={} duplicated={}",
                userId, request.getAmountCents(), result.getDuplicated());
        return toResultVO(result);
    }

    /**
     * 管理端调账（复用 D4 的调账链路，负向扣减时校验余额）。
     *
     * <p>副作用：写 balance_flow + 更新缓存 + 写 operation_log
     * （action=MANUAL_ADJUST，由 RechargeService 的注解完成）。</p>
     *
     * @param userId         目标用户（来自路径）
     * @param request        调账请求（body 不含 userId）
     * @param idempotencyKey 幂等键（HTTP 头，调账必须带）
     * @return 调账结果（bizNo 为 null）
     * @throws BusinessException 405006 金额非法 / 401002 缺幂等键 / 405001 余额不足
     *                           （幂等命中不抛异常，返回 duplicated=true）
     */
    public AdminRechargeResultVO adjust(Long userId, AdminAdjustBO request, String idempotencyKey) {
        requireUser(userId);
        AdjustBO bo = new AdjustBO();
        bo.setUserId(userId);
        bo.setDeltaCents(request.getDeltaCents());
        bo.setReason(request.getReason());
        bo.setRemark(request.getRemark());
        RechargeResultVO result = rechargeService.adjust(bo, idempotencyKey);
        log.info("管理端调账 userId={} deltaCents={} duplicated={}",
                userId, request.getDeltaCents(), result.getDuplicated());
        return toResultVO(result);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 校验用户存在且未注销（跨域只读快照）。
     *
     * @param userId 用户主键
     * @throws BusinessException 401004 不存在 / 已注销
     */
    private void requireUser(Long userId) {
        Optional<AccountAuthSnapshot> snapshot = accountQueryService.findAuthSnapshot(userId);
        if (snapshot.isEmpty() || snapshot.get().deleted()) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
    }

    /**
     * 流水实体 → 出参。
     *
     * @param flow 流水实体
     * @return 行出参
     */
    private AdminFlowRowVO toFlowRow(BalanceFlow flow) {
        AdminFlowRowVO vo = new AdminFlowRowVO();
        vo.setId(flow.getId());
        vo.setUserId(flow.getOwnerId());
        vo.setBizType(flow.getBizType());
        vo.setDeltaCents(nonNull(flow.getDeltaCents()));
        vo.setBalanceAfterCents(nonNull(flow.getBalanceAfter()));
        vo.setRemark(flow.getRemark());
        vo.setCreatedAt(flow.getCreatedAt());
        return vo;
    }

    /**
     * D4 结果 → 管理端出参（把包装类金额换成原生 long，保证 JSON 是数字）。
     *
     * @param result D4 入账结果
     * @return 管理端结果
     */
    private AdminRechargeResultVO toResultVO(RechargeResultVO result) {
        AdminRechargeResultVO vo = new AdminRechargeResultVO();
        vo.setFlowId(result.getFlowId());
        vo.setBalanceCents(nonNull(result.getBalanceCents()));
        vo.setBizNo(result.getBizNo());
        vo.setCreatedAt(result.getCreatedAt());
        vo.setDuplicated(result.getDuplicated());
        return vo;
    }

    /**
     * Long → long 的安全取值（DB 列 NOT NULL，仍防御性处理）。
     *
     * @param value 包装值
     * @return 0 或原值
     */
    private long nonNull(Long value) {
        return value == null ? 0L : value;
    }
}
