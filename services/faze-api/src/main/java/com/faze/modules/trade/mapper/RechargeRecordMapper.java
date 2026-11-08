/*
 * 职责：recharge_record 的数据访问（充值单的增查改状态）。
 * 调用方：RechargeService / RechargeCallbackService / D 组关单与补单任务 / PC A-09 Tab2。
 * 数据来源：faze.recharge_record（V600）。
 * 注意：
 *   1. 状态迁移一律用 **CAS**（`UPDATE ... WHERE id=? AND status=?`），禁止「先查再无条件改」——
 *      后者在并发回调下会把 closed 单改成 success，破坏「补偿新单」路径。
 *   2. **关单不许回填 channel_txn_id**：本表的 CAS 用 COALESCE，关单分支传 null 即保留 NULL；
 *      一旦回填，晚到回调的补偿单会撞 uk_recharge_channel_txn 而无法入账（A2 第 4 条）。
 *   3. 本 Mapper 不写 balance_flow / user_balance：入账由 RechargeService 在**同一事务**里
 *      显式调用 DeductService 侧的账务能力，避免「改单」和「动账」分散在两处。
 *   4. `raw_payload` 只允许出现在 CAS 的入参里（落库），查询列表**不要 select 它**。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.RechargeRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 充值单 Mapper。
 */
public interface RechargeRecordMapper extends BaseMapper<RechargeRecord> {

    /**
     * CAS 改状态，成功时一并回填渠道字段。
     *
     * <p>四个成功/失败/关闭分支共用一个 UPDATE：靠 `status = #{fromStatus}` 做 CAS，
     * 影响行数 0 表示状态已被别人改过（并发回调 / 重复通知）。</p>
     *
     * @param id            充值单主键
     * @param fromStatus    期望的当前状态（CAS 条件，恒为 pending）
     * @param toStatus      目标状态（success / failed / closed）
     * @param channelTxnId  渠道交易号；**关单/失败分支传 null**（COALESCE 保留 NULL）
     * @param paidAt        支付成功时间；仅 success 分支传值
     * @param rawPayload    渠道原始报文（已脱敏）；可为空
     * @param updatedAt     更新时间（UTC）
     * @return 影响行数；0 表示 CAS 失败
     */
    @Update("""
            UPDATE recharge_record
               SET status = #{toStatus},
                   channel_txn_id = COALESCE(#{channelTxnId}, channel_txn_id),
                   paid_at = COALESCE(#{paidAt}, paid_at),
                   raw_payload = COALESCE(#{rawPayload}, raw_payload),
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = #{fromStatus}
            """)
    int casTransit(@Param("id") Long id,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus,
                   @Param("channelTxnId") String channelTxnId,
                   @Param("paidAt") LocalDateTime paidAt,
                   @Param("rawPayload") String rawPayload,
                   @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 批量关单：把已过期且仍待支付的单置为 closed。
     *
     * <p>单条 UPDATE 完成，靠 `status='pending' AND expire_at &lt;= now` 做条件保护；
     * 与回调并发时，回调的 CAS 会先成功或本语句影响 0 行，不会互相覆盖。</p>
     *
     * @param now       当前时间（UTC）
     * @param batchSize 单批上限（防大事务，§7.4）
     * @return 实际关闭的行数
     */
    @Update("""
            UPDATE recharge_record
               SET status = 'closed',
                   updated_at = #{now}
             WHERE status = 'pending'
               AND expire_at <= #{now}
             ORDER BY expire_at
             LIMIT #{batchSize}
            """)
    int closeExpired(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);
}
