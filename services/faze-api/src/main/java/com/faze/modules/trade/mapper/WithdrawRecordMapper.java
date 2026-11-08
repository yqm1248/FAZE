/*
 * 职责：withdraw_record 的数据访问（提现单的增查改状态）。
 * 调用方：modules/trade 的 WithdrawService、AdminWithdrawController、AdminSettlementController。
 * 数据来源：faze.withdraw_record（V505）。
 * 注意：
 *   1. 状态迁移一律用 **CAS**（`UPDATE ... WHERE status = ?`），禁止「先查再无条件改」——
 *      后者在并发审核下会把 rejected / paid 的单改回去，等于重复打款。
 *   2. 查询必须自己带 `deleted_at IS NULL`（段 1/2 未配 MP 逻辑删除拦截器）。
 *   3. 本 Mapper 不写 balance_flow / lawyer_balance：动钱统一由 WithdrawService
 *      在**同一事务**里显式调用，避免「改单」和「动账」分散在两处。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.WithdrawRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 提现单 Mapper。
 */
public interface WithdrawRecordMapper extends BaseMapper<WithdrawRecord> {

    /**
     * CAS 改状态并写入审核/出款字段。
     *
     * <p>三个分支共用一个 UPDATE：靠 `status = #{fromStatus}` 做 CAS，
     * 影响行数 0 表示状态已被别人改过（并发审核 / 重复点击）。</p>
     *
     * @param id            提现单主键
     * @param fromStatus    期望的当前状态（CAS 条件）
     * @param toStatus      目标状态
     * @param auditedBy     审核人；驳回与通过都要写（出款分支传 null 保留原值）
     * @param auditedAt     审核时间；出款分支传 null 保留原值
     * @param paidAt        出款完成时间；审核分支传 null
     * @param rejectReason  驳回理由；通过/出款分支传 null
     * @param transactionId 渠道出款流水号（选填）
     * @return 影响行数；0 表示 CAS 失败
     */
    @Update("""
            UPDATE withdraw_record
               SET status = #{toStatus},
                   audited_by = COALESCE(#{auditedBy}, audited_by),
                   audited_at = COALESCE(#{auditedAt}, audited_at),
                   paid_at = COALESCE(#{paidAt}, paid_at),
                   reject_reason = COALESCE(#{rejectReason}, reject_reason),
                   transaction_id = COALESCE(#{transactionId}, transaction_id),
                   updated_at = #{updatedAt}
             WHERE id = #{id}
               AND status = #{fromStatus}
               AND deleted_at IS NULL
            """)
    int casTransit(@Param("id") Long id,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus,
                   @Param("auditedBy") Long auditedBy,
                   @Param("auditedAt") java.time.LocalDateTime auditedAt,
                   @Param("paidAt") java.time.LocalDateTime paidAt,
                   @Param("rejectReason") String rejectReason,
                   @Param("transactionId") String transactionId,
                   @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
