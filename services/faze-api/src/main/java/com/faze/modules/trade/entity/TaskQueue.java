/*
 * 职责：task_queue 表的实体映射（异步任务表，替代 MQ）。
 * 调用方：modules/trade/mapper/TaskQueueMapper、TaskQueueService、OrderCloseTask。
 * 数据来源：V200__seg2_tables.sql 建的 task_queue（12 字段）。
 * 注意：
 *   1. **「已投递」是状态不是删除**（规范 §9.2 规则 1）：执行完把 status 改成 `done`，
 *      不删行。删了就没法回答「这个任务到底跑没跑过」。
 *   2. 领取必须 CAS（`status='pending'` 进 where），按影响行数判断谁真的领到；
 *      先 SELECT 后 UPDATE 会让两个消费者拿到同一条。
 *   3. `lockExpireAt` 是租约：消费者崩溃后，过期任务会被回收重跑（至少一次语义）。
 *   4. `retryCount >= 5` 进死信（`status='dead'`），PC A-26 可见，必须能被人工处理。
 *   5. `nextRetryAt` 是「下次可执行时间」，指数退避 = now + 2^retryCount 分钟。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 异步任务。
 */
@Data
@TableName("task_queue")
public class TaskQueue {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型：order_close / order_accept_timeout / refund_retry / im_push 等。 */
    private String taskType;

    /** 业务单号（订单号 / 退款单号），用于回查与幂等。 */
    private String bizNo;

    /** 状态：pending / claimed / done / dead。 */
    private String status;

    /** 已重试次数；>=5 进死信。 */
    private Integer retryCount;

    /** 下次可执行时间（UTC）。 */
    private LocalDateTime nextRetryAt;

    /** 最近一次失败原因（截断到 500）。 */
    private String lastError;

    /** 领取者标识（实例 ID + 线程）。 */
    private String lockOwner;

    /** 租约到期时间（UTC）；过期即回收。 */
    private LocalDateTime lockExpireAt;

    /** 来源请求的 traceId。 */
    private String traceId;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
