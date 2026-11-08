/*
 * 职责：balance_flow 的数据访问（只插入 + 按 owner 查询/聚合）。
 * 调用方：modules/trade（扣费引擎、充值、调账、对账）、modules/trade/admin（钱包、对账报表）。
 * 数据来源：faze.balance_flow。
 * 注意：
 *   1. **不提供 update / delete**：这张表是只追加的证据链，改一条流水等于改账。
 *   2. 「余额 = SUM(delta_cents)」走 XML（规范 §8.1：聚合用 XML，不用 Wrapper 拼）。
 *      调用点读 `sumDeltaByOwner` 时能一眼看出是「流水汇总」而不是读缓存。
 *   3. 幂等查询用 `(owner_type, owner_id, biz_type, biz_id)` 走 uk_flow_biz。
 *   4. `sumDeltaByOwnerAndBizType` / `selectLastFlowTime` / `selectOwnerSums` 是第 1 段
 *      F3 为准入的 A-07（钱包）与 A-09（对账）新增的聚合查询，一律用 `#{}` 参数化。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.BalanceFlow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 资金流水 Mapper。
 */
public interface BalanceFlowMapper extends BaseMapper<BalanceFlow> {

    /**
     * 按归属方汇总流水净额（分）。
     *
     * <p>**这是「余额」的定义**：返回 0 表示没有流水（新用户），不是异常。</p>
     *
     * @param ownerType user / lawyer
     * @param ownerId   归属主键
     * @return 净额（分），无流水时为 0
     */
    Long sumDeltaByOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    /**
     * 按归属方 + 业务类型汇总流水净额（分）。
     *
     * <p>A-07 钱包页的「累计充值」用 biz_type=recharge，「累计消费」用 biz_type=consume
     * （消费流水是负数，展示时取绝对值）。</p>
     *
     * @param ownerType user / lawyer
     * @param ownerId   归属主键
     * @param bizType   业务类型：recharge / consume / adjust / …
     * @return 净额（分），无流水时为 0
     */
    Long sumDeltaByOwnerAndBizType(@Param("ownerType") String ownerType,
                                   @Param("ownerId") Long ownerId,
                                   @Param("bizType") String bizType);

    /**
     * 取某个归属方最近一条流水的时间。
     *
     * @param ownerType user / lawyer
     * @param ownerId   归属主键
     * @return 最近流水时间（UTC）；无流水时为 null
     */
    LocalDateTime selectLastFlowTime(@Param("ownerType") String ownerType,
                                     @Param("ownerId") Long ownerId);

    /**
     * 按 (owner_type, owner_id) 汇总全部流水净额（对账用，一次查询搞定）。
     *
     * @return 每个归属方一行；无流水返回空列表
     */
    List<OwnerFlowSum> selectOwnerSums();

    /**
     * 汇总某律师在给定时间区间内的 `income` 流水净额（段 5 E4 结算批次用）。
     *
     * <p>区间是**半开区间** `[beginAt, endAt)`，入参由调用方按东八区账期换算成 UTC，
     * 本 SQL 不做时区换算（SQL 里换时区会隐式丢索引）。</p>
     *
     * @param ownerId lawyer.id
     * @param beginAt 区间起（含，UTC）
     * @param endAt   区间止（不含，UTC）
     * @return 净额（分）；无流水时为 0
     */
    Long sumIncomeByOwnerAndRange(@Param("ownerId") Long ownerId,
                                  @Param("beginAt") LocalDateTime beginAt,
                                  @Param("endAt") LocalDateTime endAt);

    /**
     * 扫出「已过冻结期且还没解冻过」的 income 流水（段 5 E3 解冻任务）。
     *
     * <p>为什么用 `NOT EXISTS` 而不是给 balance_flow 加「已解冻」标记列：
     * 本表是只追加的证据链，加标记列就意味着要 UPDATE 历史流水（规范禁止）。
     * 用 `biz_id` 关联判断是唯一不破坏「只追加」原则的做法。</p>
     *
     * @param now   当前时间（UTC）
     * @param limit 单批上限（防一次扫爆内存）
     * @return 待解冻的 income 流水；没有则空列表
     */
    List<BalanceFlow> selectDueUnfreezeIncomes(@Param("now") LocalDateTime now,
                                               @Param("limit") int limit);
}
