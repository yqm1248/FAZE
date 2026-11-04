/*
 * 职责：账户余额查询与缓存重算（第 1 段 D1）。**余额的唯一权威是流水汇总**。
 * 调用方：modules/trade/controller/WalletController（查询）、运维与 D5 对账（重算回写）。
 * 数据来源：balance_flow（权威）、user_balance（缓存）。
 * 注意：
 *   1. **绝不能用 `user_balance.balance_cents` 当权威值**。锚点 `1-A6` 的判据是：
 *      手工把缓存改成 99999900 后，接口仍必须返回流水汇总值。
 *      所以本类的每个读余额方法都只有一个来源：`sumDeltaByOwner`。
 *   2. 缓存字段只用于 `frozen_cents`（冻结额目前没有流水可推），以及 recalc 时回写。
 *   3. 首次访问惰性建 `user_balance` 行：新用户没有余额行是正常状态，
 *      但后续扣费要 update 它，所以进钱包页时先建好，避免扣费时才发现没有行。
 *   4. **缓存可能是负的**（数据被手工改坏 / 有流水没回写）：不在这里纠正，
 *      原样返回并让 D5 对账脚本报出来。静默纠正会掩盖问题。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.pojo.PageResult;
import com.faze.modules.trade.bo.FlowQueryBO;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.vo.BalanceVO;
import com.faze.modules.trade.vo.FlowVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 余额服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceService {

    /** 归属类型：C 端用户。 */
    public static final String OWNER_USER = "user";
    /** 归属类型：律师。 */
    public static final String OWNER_LAWYER = "lawyer";

    private final BalanceFlowMapper balanceFlowMapper;
    private final UserBalanceMapper userBalanceMapper;

    /**
     * 查询余额（以流水汇总为准）。
     *
     * <p>副作用：首次访问时惰性插入 user_balance 行（余额 0）。</p>
     *
     * @param userId 用户主键
     * @return 可用 / 冻结 / 总额（分）
     */
    public BalanceVO getBalance(Long userId) {
        // 任务 1：确保余额行存在（后续扣费要 update 它）。
        UserBalance cache = ensureRow(userId);
        // 任务 2：总额只认流水汇总（锚点 1-A6）。
        long total = sumDelta(userId);
        // 任务 3：冻结额读缓存——段 1 没有冻结场景，恒 0；段 2 订单占用后才有值。
        long frozen = cache.getFrozenCents() == null ? 0L : cache.getFrozenCents();
        long available = total - frozen;
        if (available < 0) {
            // 不静默纠正：记录后原样返回，由 D5 对账报差异
            log.warn("余额出现负可用额 userId={} total={} frozen={}", userId, total, frozen);
        }
        BalanceVO vo = new BalanceVO();
        vo.setTotalCents(total);
        vo.setFrozenCents(frozen);
        vo.setAvailableCents(available);
        return vo;
    }

    /**
     * 按流水重算并把结果写回缓存（供 D5 对账与运维使用）。
     *
     * <p>副作用：更新 user_balance.balance_cents，带乐观锁版本。</p>
     *
     * @param userId 用户主键
     * @return 重算后的总额（分）
     */
    public long recalcBalance(Long userId) {
        UserBalance cache = ensureRow(userId);
        long total = sumDelta(userId);
        long frozen = cache.getFrozenCents() == null ? 0L : cache.getFrozenCents();
        UserBalance patch = new UserBalance();
        patch.setId(cache.getId());
        patch.setVersion(cache.getVersion());
        patch.setBalanceCents(total - frozen);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userBalanceMapper.updateById(patch);
        log.info("余额已按流水重算 userId={} total={} 缓存已回写", userId, total);
        return total;
    }

    /**
     * 分页查询资金流水。
     *
     * <p>排序 `created_at DESC, id DESC`：同秒内用 id 兜底，保证翻页不重不漏。</p>
     *
     * @param userId 用户主键
     * @param query  查询条件
     * @return 分页结果
     */
    public PageResult<FlowVO> listFlows(Long userId, FlowQueryBO query) {
        // 任务 1：页码与每页条数 clamp（规范：超 100 服务端夹住，不报错）
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null ? 20 : Math.min(Math.max(query.getPageSize(), 1), 100);
        // 任务 2：条件查询（单表条件 + 排序，规范 §8.1 用 Wrapper 而不是 XML）
        LambdaQueryWrapper<BalanceFlow> wrapper = Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerType, OWNER_USER)
                .eq(BalanceFlow::getOwnerId, userId)
                .eq(StringUtils.hasText(query.getBizType()), BalanceFlow::getBizType, query.getBizType())
                .ge(query.getBeginTime() != null, BalanceFlow::getCreatedAt, query.getBeginTime())
                .le(query.getEndTime() != null, BalanceFlow::getCreatedAt, query.getEndTime())
                .orderByDesc(BalanceFlow::getCreatedAt)
                .orderByDesc(BalanceFlow::getId);
        // 任务 3：分页查询并转出参
        IPage<BalanceFlow> page = balanceFlowMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<FlowVO> list = page.getRecords().stream().map(this::toFlowVO).toList();
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 流水实体 → 出参。
     *
     * @param flow 流水实体
     * @return 流水项；不返回内部 bizId（见 FlowVO 注释第 3 条）
     */
    private FlowVO toFlowVO(BalanceFlow flow) {
        FlowVO vo = new FlowVO();
        vo.setId(flow.getId());
        vo.setBizType(flow.getBizType());
        vo.setDeltaCents(flow.getDeltaCents());
        vo.setBalanceAfterCents(flow.getBalanceAfter());
        vo.setRemark(flow.getRemark());
        vo.setCreatedAt(flow.getCreatedAt());
        return vo;
    }

    /**
     * 流水分汇总（余额的唯一来源）。
     *
     * @param userId 用户主键
     * @return 净额（分）
     */
    private long sumDelta(Long userId) {
        Long sum = balanceFlowMapper.sumDeltaByOwner(OWNER_USER, userId);
        return sum == null ? 0L : sum;
    }

    /**
     * 确保余额行存在（惰性建 + 并发兜底）。
     *
     * <p><b>为什么是 public</b>：余额行是「惰性建」的——只有查过余额的用户才有行。
     * 于是「运营给一个只注册过、还没查过余额的用户手工充值」会因为没有行而失败（401004），
     * 这是真实场景。入账侧（RechargeService）需要先建这一行，所以本方法必须对外开放。</p>
     *
     * <p>**只建 0 余额的空行，不产生任何流水、不改任何金额**——所以它不违反
     * 「余额只能由流水推导」这条铁律（见 V5 迁移注释）。</p>
     *
     * @param userId 用户主键
     * @return 余额缓存行
     */
    public UserBalance ensureRow(Long userId) {
        UserBalance existing = userBalanceMapper.selectOne(Wrappers.<UserBalance>lambdaQuery()
                .eq(UserBalance::getUserId, userId));
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserBalance row = new UserBalance();
        row.setUserId(userId);
        row.setBalanceCents(0L);
        row.setFrozenCents(0L);
        row.setVersion(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            userBalanceMapper.insert(row);
            return row;
        } catch (DuplicateKeyException e) {
            // 并发首访：另一个请求刚插了同一 user_id，重新读即可
            return userBalanceMapper.selectOne(Wrappers.<UserBalance>lambdaQuery()
                    .eq(UserBalance::getUserId, userId));
        }
    }
}
