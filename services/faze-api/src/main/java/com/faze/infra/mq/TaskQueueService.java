/*
 * 职责：异步任务的入队、CAS 领取、完成/失败/死信、按业务号取消（《后端代码规范》§9.2 的唯一实现点）。
 * 调用方：各业务域的入队方（trade 下单/退款、conversation 离线推送、document 文书生成）
 *         与各任务的扫描器（OrderCloseTask / PushTask / DocGenerateTask）。
 * 数据来源：faze.task_queue。
 * 注意：
 *   1. **领取 = 先回收租约 → 选候选 → CAS 更新 → 按 owner 回查**。四步缺一不可：
 *      不回收租约会让崩溃实例的任务永久卡住；不做 CAS 会让两个实例领到同一条；
 *      不按 owner 回查就不知道自己到底领到了哪几条（`claimByIds` 只回影响行数）。
 *   2. `owner` 必须**每个工作线程唯一**（实例 ID + 线程名）：同一个 owner 的两条任务
 *      会被 `markDone` 互相干扰（where 只认 owner 不认任务归属线程）。
 *   3. 失败退避用 `2^retryCount` 分钟并封顶 60 分钟；`retryCount` 达到 5 进死信。
 *      退避时间以**当前重试次数**为指数，不是「失败次数」——两者差一次，
 *      差这一次会让首次重试等 2 分钟而不是 1 分钟，测试里很容易看错。
 *   4. 本类所有方法**不自己开事务**：入队要加入调用方事务（下单成功才该有关单任务）；
 *      领取/完成各自独立，由调用方（定时任务）决定边界。
 *   5. 时间一律用 `utcNow()` 生成并显式传参，SQL 里没有 NOW()。
 */
package com.faze.infra.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.mapper.TaskQueueMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 异步任务队列服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskQueueService {

    /** 订单超时关单任务（待支付 15 分钟）。 */
    public static final String TYPE_ORDER_CLOSE = "order_close";

    /** 订单接单超时任务（已支付 30 分钟无人接）。 */
    public static final String TYPE_ORDER_ACCEPT_TIMEOUT = "order_accept_timeout";

    /** 任务类型：离线推送（G7.2，`biz_no` 存 conversation_message.id）。 */
    public static final String TYPE_PUSH = "push";

    /**
     * 任务类型：文书生成（段 4 A4，`biz_no` 存 `document.id`）。
     *
     * <p>工作流 §三 A4 原文写的是 `DOC_GENERATE`（大写）。这里用**小写 `doc_generate`**：
     * 库里已有的两个类型都是小写（`order_close` / `push`），大小写混用会让
     * 「按类型筛任务」的 SQL 和监控面板各写一套。**类型值本身不是契约**，
     * 是内部枚举，统一小写即可。</p>
     */
    public static final String TYPE_DOC_GENERATE = "doc_generate";

    /**
     * 任务类型：律师收入到期解冻（段 5 E3，`biz_no` 存本次 tick 的标记）。
     *
     * <p>与 `doc_generate` 不同，本类型不是「用户触发的单条业务」，而是**周期性批处理**：
     * 由 `LawyerUnfreezeTask` 每 10 分钟入队一条 tick，再自己领取执行。
     * 为什么要绕一下 task_queue 而不是 `@Scheduled` 直连数据库：段 2 的异步任务五条规范
     * 要求所有后台批量动作都留下「已投递 / 已领取 / 已失败」的可观测痕迹，
     * 直连扫描器崩了没有任何记录，只能靠日志猜（工作流 E3 第 3 条）。</p>
     */
    public static final String TYPE_LAWYER_UNFREEZE = "lawyer_unfreeze";

    /** 任务状态：待领取。 */
    public static final String STATUS_PENDING = "pending";

    /** 任务状态：已领取。 */
    public static final String STATUS_CLAIMED = "claimed";

    /** 任务状态：已完成。 */
    public static final String STATUS_DONE = "done";

    /** 任务状态：死信。 */
    public static final String STATUS_DEAD = "dead";

    /** 最大重试次数；达到即进死信。 */
    private static final int MAX_RETRY = 5;

    /** 单次最多领取多少条。 */
    private static final int CLAIM_LIMIT = 20;

    /** 租约时长：超过这个时间还没标完成，任务会被回收（消费者可能崩了）。 */
    private static final Duration LEASE = Duration.ofMinutes(2);

    /** 退避封顶。 */
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(60);

    /** 失败原因字段上限（`last_error VARCHAR(500)`）。 */
    private static final int MAX_ERROR_LENGTH = 500;

    private final TaskQueueMapper taskQueueMapper;

    /** 本实例标识：owner 里带上它，多实例部署时能看出任务被哪台机器领了。 */
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    /**
     * 入队一条任务。
     *
     * <p>副作用：向 task_queue 插入一行（status=pending）。</p>
     *
     * @param taskType 任务类型（见本类常量）
     * @param bizNo    业务单号（订单号）
     * @param runAt    最早可执行时间（UTC）
     * @param traceId  来源链路 id，可空
     */
    public void enqueue(String taskType, String bizNo, LocalDateTime runAt, String traceId) {
        LocalDateTime now = utcNow();
        TaskQueue task = new TaskQueue();
        task.setTaskType(taskType);
        task.setBizNo(bizNo);
        task.setStatus(STATUS_PENDING);
        task.setRetryCount(0);
        task.setNextRetryAt(runAt == null ? now : runAt);
        task.setTraceId(traceId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskQueueMapper.insert(task);
        log.info("任务入队 type={} bizNo={} runAt={}", taskType, bizNo, task.getNextRetryAt());
    }

    /**
     * 领取一批到期任务（不筛类型）。
     *
     * <p>保留本重载是为了兼容段 2 之前的调用方与集成测试；**生产消费者应传类型**，
     * 否则会领走别的消费者的任务（G7.2 起 task_queue 有两个消费者）。</p>
     *
     * @param workerName 工作线程标识（同一次扫描内的 owner 前缀）
     * @return 本次真正领到的任务；没有可领的返回空列表
     */
    public List<TaskQueue> claimBatch(String workerName) {
        return claimBatch(workerName, List.of());
    }

    /**
     * 领取一批指定类型的到期任务。
     *
     * <p>副作用：先回收租约过期任务，再把候选任务置为 claimed。</p>
     *
     * @param workerName 工作线程标识（同一次扫描内的 owner 前缀）
     * @param taskTypes  只领这些类型；空列表表示不筛
     * @return 本次真正领到的任务；没有可领的返回空列表
     */
    public List<TaskQueue> claimBatch(String workerName, List<String> taskTypes) {
        LocalDateTime now = utcNow();
        // 任务 1：回收租约过期任务（消费者崩溃后的兜底）
        int recycled = taskQueueMapper.releaseExpired(now);
        if (recycled > 0) {
            log.warn("回收租约过期任务 {} 条", recycled);
        }
        // 任务 2：选候选 id
        List<Long> ids = taskQueueMapper.selectCandidateIds(now, CLAIM_LIMIT, taskTypes);
        if (ids.isEmpty()) {
            return List.of();
        }
        // 任务 3：CAS 领取（并发安全点：where status='pending'）
        String owner = ownerOf(workerName);
        int claimed = taskQueueMapper.claimByIds(ids, owner, now.plus(LEASE), now);
        if (claimed == 0) {
            // 全部被别人抢走：这是正常竞争结果，不是异常
            return List.of();
        }
        // 任务 4：按 owner 回查自己领到的任务
        List<TaskQueue> tasks = taskQueueMapper.selectList(Wrappers.<TaskQueue>lambdaQuery()
                .eq(TaskQueue::getStatus, STATUS_CLAIMED)
                .eq(TaskQueue::getLockOwner, owner));
        log.info("领取任务 {} 条（候选 {} 条） owner={}", tasks.size(), ids.size(), owner);
        return tasks;
    }

    /**
     * 判断某业务号是否已有**未完成**的同类任务（入队前判重，保证同一消息只推一次）。
     *
     * @param taskType 任务类型
     * @param bizNo    业务号
     * @return 存在 pending / claimed 的同类型任务返回 true
     */
    public boolean hasActiveTask(String taskType, String bizNo) {
        return taskQueueMapper.selectCount(Wrappers.<TaskQueue>lambdaQuery()
                .eq(TaskQueue::getTaskType, taskType)
                .eq(TaskQueue::getBizNo, bizNo)
                .in(TaskQueue::getStatus, STATUS_PENDING, STATUS_CLAIMED)) > 0;
    }

    /**
     * 标记任务完成（已投递是状态不是删除）。
     *
     * @param task  任务
     * @param owner 领取者标识
     */
    public void markDone(TaskQueue task, String owner) {
        int rows = taskQueueMapper.markDone(task.getId(), owner, utcNow());
        if (rows == 0) {
            // 租约可能已被别人回收重领：不影响业务，只是本实例的标注被丢弃
            log.warn("标记任务完成未命中（可能已被回收） id={} owner={}", task.getId(), owner);
        }
    }

    /**
     * 标记任务失败：未到重试上限则退避重试，达到上限进死信。
     *
     * @param task  任务
     * @param owner 领取者标识
     * @param error 失败原因（会被截断到 500 字）
     */
    public void markFailed(TaskQueue task, String owner, String error) {
        LocalDateTime now = utcNow();
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        String reason = truncate(error);
        if (retryCount + 1 >= MAX_RETRY) {
            taskQueueMapper.markDead(task.getId(), owner, reason, now);
            log.error("任务进入死信 id={} type={} bizNo={} retryCount={} error={}",
                    task.getId(), task.getTaskType(), task.getBizNo(), retryCount, reason);
            return;
        }
        LocalDateTime nextRetryAt = now.plus(backoff(retryCount));
        taskQueueMapper.markFailed(task.getId(), owner, reason, nextRetryAt, now);
        log.warn("任务失败待重试 id={} type={} bizNo={} nextRetryAt={} error={}",
                task.getId(), task.getTaskType(), task.getBizNo(), nextRetryAt, reason);
    }

    /**
     * 取消某业务号下未执行的任务（例如用户主动取消订单时，那条关单任务不必再跑）。
     *
     * @param taskType 任务类型
     * @param bizNo    业务单号
     */
    public void cancelPending(String taskType, String bizNo) {
        if (!StringUtils.hasText(bizNo)) {
            return;
        }
        int rows = taskQueueMapper.cancelByBizNo(taskType, bizNo, utcNow());
        if (rows > 0) {
            log.info("取消待执行任务 type={} bizNo={} rows={}", taskType, bizNo, rows);
        }
    }

    /**
     * 把某业务号下**等待重试**的任务提前到「现在」执行。
     *
     * <p>使用场景：文书生成失败后落在指数退避里（第一次 1 分钟、第二次 2 分钟…），
     * 用户改完东西点「重新生成」时 `hasActiveTask` 会认为「已有任务在跑」直接跳过，
     * 表现是「点了没反应」。这里把已排定的那次提前，**不新增任务、不重置重试计数**。</p>
     *
     * @param taskType 任务类型
     * @param bizNo    业务单号
     * @return 是否真的有一条等待中的任务被提前
     */
    public boolean expeditePending(String taskType, String bizNo) {
        if (!StringUtils.hasText(bizNo)) {
            return false;
        }
        int rows = taskQueueMapper.expeditePending(taskType, bizNo, utcNow());
        if (rows > 0) {
            log.info("提前重试待执行任务 type={} bizNo={} rows={}", taskType, bizNo, rows);
        }
        return rows > 0;
    }

    /**
     * 生成工作线程的 owner（定时任务里按任务 id 区分，避免同批任务互相覆盖标注）。
     *
     * @param suffix 线程内唯一后缀
     * @return owner 字符串
     */
    public String ownerOf(String suffix) {
        return instanceId + "-" + suffix;
    }

    /**
     * 计算退避时长：`2^retryCount` 分钟，封顶 60 分钟。
     *
     * @param retryCount 当前重试次数（0 表示第一次失败）
     * @return 退避时长
     */
    Duration backoff(int retryCount) {
        long minutes = 1L << Math.min(Math.max(retryCount, 0), 6);
        Duration backoff = Duration.ofMinutes(minutes);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    /**
     * 截断失败原因，避免超长写入报错。
     *
     * @param error 原始原因
     * @return 不超过 500 字的字符串
     */
    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * 当前 UTC 时间。
     *
     * @return 无时区的 UTC 时间
     */
    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
