/*
 * 职责：task_queue 的数据访问——CAS 领取、租约回收、完成/失败/死信、按业务号取消。
 * 调用方：infra/mq/TaskQueueService。
 * 数据来源：faze.task_queue。
 * 注意：
 *   1. **领取是两步**（`selectCandidateIds` → `claimByIds`），但并发安全由第二步的
 *      `AND status='pending'` 保证：两个消费者可能选中同一批 id，但只有一个能更新成功，
 *      调用方按影响行数判断。**不要把它合成一条 SELECT ... FOR UPDATE**：
 *      任务表是热点表，加行锁会与「扫下一批」互相阻塞。
 *   2. 所有时间参数由调用方传入（UTC），SQL 里**不写 NOW()**：
 *      用数据库时钟还是应用时钟必须只有一处决定，否则多实例时钟漂移会让租约判断不一致。
 *   3. 禁止 `${}`（规范 §7.3）：本文件没有动态列名，全部用 `#{}`。
 */
package com.faze.infra.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.infra.mq.TaskQueue;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 异步任务 Mapper。
 */
public interface TaskQueueMapper extends BaseMapper<TaskQueue> {

    /**
     * 取一批待领取任务的 id（不锁行）。
     *
     * @param now       当前时间（UTC）
     * @param limit     最多取多少条
     * @param taskTypes 只领这些类型；null 或空列表表示不筛（兼容旧调用方）
     * @return 候选任务 id 列表
     */
    List<Long> selectCandidateIds(@Param("now") LocalDateTime now,
                                  @Param("limit") int limit,
                                  @Param("taskTypes") List<String> taskTypes);

    /**
     * CAS 领取：只更新仍为 pending 的行。
     *
     * @param ids          候选 id 列表（非空）
     * @param owner        领取者标识
     * @param lockExpireAt 租约到期时间（UTC）
     * @param now          当前时间（UTC）
     * @return 实际领取到的行数
     */
    int claimByIds(@Param("ids") List<Long> ids,
                   @Param("owner") String owner,
                   @Param("lockExpireAt") LocalDateTime lockExpireAt,
                   @Param("now") LocalDateTime now);

    /**
     * 回收租约过期的已领取任务（放回 pending 并 retry_count+1）。
     *
     * @param now 当前时间（UTC）
     * @return 回收行数
     */
    int releaseExpired(@Param("now") LocalDateTime now);

    /**
     * 标记完成（已投递是状态不是删除）。
     *
     * @param id    任务主键
     * @param owner 领取者（防止别人误标）
     * @param now   当前时间（UTC）
     * @return 影响行数
     */
    int markDone(@Param("id") Long id, @Param("owner") String owner, @Param("now") LocalDateTime now);

    /**
     * 标记失败并安排重试（retry_count+1，next_retry_at = 指数退避后的时间）。
     *
     * @param id          任务主键
     * @param owner       领取者
     * @param error       失败原因（调用方先截断到 500）
     * @param nextRetryAt 下次可执行时间（UTC）
     * @param now         当前时间（UTC）
     * @return 影响行数
     */
    int markFailed(@Param("id") Long id,
                   @Param("owner") String owner,
                   @Param("error") String error,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("now") LocalDateTime now);

    /**
     * 标记死信（重试次数用尽）。
     *
     * @param id    任务主键
     * @param owner 领取者
     * @param error 最后一次失败原因
     * @param now   当前时间（UTC）
     * @return 影响行数
     */
    int markDead(@Param("id") Long id,
                 @Param("owner") String owner,
                 @Param("error") String error,
                 @Param("now") LocalDateTime now);

    /**
     * 按业务号取消未执行的任务（例如用户主动取消订单时，那条 15 分钟关单任务就不必再跑了）。
     *
     * <p>只取消 `pending`：已领取或已完成的不动——已完成的是历史事实，
     * 已领取的由执行方自己看订单状态决定「无事可做即标记完成」。</p>
     *
     * @param taskType 任务类型
     * @param bizNo    业务单号
     * @param now      当前时间（UTC）
     * @return 影响行数
     */
    int cancelByBizNo(@Param("taskType") String taskType,
                      @Param("bizNo") String bizNo,
                      @Param("now") LocalDateTime now);
}
