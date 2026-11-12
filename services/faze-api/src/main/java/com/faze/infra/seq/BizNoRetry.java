/*
 * 职责：单号撞唯一键时的「换号重试」统一封装（DEF-017）。
 * 调用方：8 个单号写入点——caseflow 的 CaseServiceImpl / DeliveryServiceImpl、
 *         content 的 AdminCaseLibraryService、ops 的 AdminAnnouncementService / TicketService、
 *         trade 的 SettlementService / WithdrawService / RechargeCreateExecutor。
 * 数据来源：infra/seq/BizNoGenerator 负责生成单号；本类只负责「撞了就换号再试」。
 * 注意：
 *   1. **只重试单号唯一键**：`DuplicateKeyException` 的 message 里若不含给定的唯一键名，
 *      说明撞的是别的业务唯一键（如 `uk_settlement_lawyer_period`、`uk_recharge_channel_txn`），
 *      必须原样抛给调用方——否则会把「同律师同账期重复」误报成「单号生成失败」，
 *      把幂等跳过变成静默丢单（SettlementService 的既有行为就依赖这个区分）。
 *   2. 重试时**每次重新生成单号**：BizNoGenerator 走 Redis INCR 自然递增，
 *      Redis 序号丢失后要连续跳过若干历史号才能追平，所以不能复用上一次的号。
 *   3. 主防线是 Redis 持久化（deploy/redis-faze.conf 的 RDB + AOF），本类是第二道防线：
 *      覆盖「手动清库 / 序号 key 被误删 / 并发极端」等持久化也挡不住的情况。
 *   4. 次数用尽抛最后一次的原始异常，由调用方按原语义处理（GlobalExceptionHandler 转 401005，
 *      或 SettlementService 按幂等跳过），本类不改变对外错误码。
 *   5. 不会把唯一键名回给客户端——这里只做内部判断，异常本身仍由 GlobalExceptionHandler 脱敏。
 *   6. **已知局限（必须知道，别误以为万能）**：默认 3 次尝试只能消化 **2 个**已占用号。
 *      Redis 序号若被外部清空（FLUSHDB / 手动 del key），而当天已建了 N 单，就需要连续
 *      跳过 N 个号——N ≥ 3 时 3 次用尽仍会抛 401005。**根治手段是 Redis 持久化**
 *      （deploy/redis-faze.conf 的 RDB + AOF，2026-11-13 已开），本重试只兜「持久化也挡不住
 *      的人为误删」。真遇到「当天大量撞号」时：先查 Redis 是否被清过，不要盲调大这里的次数。
 */
package com.faze.infra.seq;

import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;

/**
 * 单号撞唯一键时换号重试的工具类。
 *
 * <p>为什么不让 {@link BizNoGenerator} 自己重试：生成器不知道号要插进哪张表、
 * 撞的是哪个唯一键，重试只能发生在「生成 + 插入」成对出现的地方。</p>
 */
@Slf4j
public final class BizNoRetry {

    /** 默认尝试次数。与段 2 {@code OrderCreateExecutor}、段 6 {@code RechargeCreateExecutor} 保持一致。 */
    public static final int DEFAULT_ATTEMPTS = 3;

    private BizNoRetry() {
    }

    /**
     * 生成单号并插入，撞单号唯一键时换号重试（默认 3 次）。
     *
     * @param noKeyName 单号唯一键名（如 {@code uk_legal_case_no}），用于区分「撞的是单号」还是「撞的是业务唯一键」
     * @param noGen     单号生成函数（每次重试都会重新调用）
     * @param insertFn  插入函数：参数是新单号，函数内部负责把单号回填到实体并落库
     * @param <T>       插入函数的返回值（通常是受影响行数或实体）
     * @return insertFn 第一次成功的返回值
     */
    public static <T> T insertWithNo(String noKeyName, Supplier<String> noGen, Function<String, T> insertFn) {
        return insertWithNo(noKeyName, DEFAULT_ATTEMPTS, noGen, insertFn);
    }

    /**
     * 生成单号并插入，撞单号唯一键时换号重试。
     *
     * @param noKeyName  单号唯一键名
     * @param maxAttempts 最大尝试次数（至少按 1 次执行）
     * @param noGen      单号生成函数
     * @param insertFn   插入函数
     * @param <T>        插入函数的返回值
     * @return insertFn 第一次成功的返回值
     * @throws DuplicateKeyException 撞的不是单号唯一键时**立即**抛出；撞的是单号但次数用尽时抛出最后一次
     */
    public static <T> T insertWithNo(String noKeyName, int maxAttempts,
                                     Supplier<String> noGen, Function<String, T> insertFn) {
        int attempts = Math.max(1, maxAttempts);
        DuplicateKeyException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String no = noGen.get();
            try {
                return insertFn.apply(no);
            } catch (DuplicateKeyException e) {
                if (!isNoConflict(e, noKeyName)) {
                    // 撞的是业务唯一键，不归本类管，原样抛出（调用方的幂等/错误语义依赖它）
                    throw e;
                }
                last = e;
                log.warn("单号撞唯一键，换号重试 attempt={}/{} no={} key={}",
                        attempt, attempts, no, noKeyName);
            }
        }
        throw last;
    }

    /**
     * 判断异常是不是「单号唯一键」冲突。
     *
     * <p>MySQL 的报文形如 {@code Duplicate entry 'CS20261113000008' for key 'legal_case.uk_legal_case_no'}，
     * 因此用唯一键名做子串匹配即可。报文拿不到（极少数驱动/包装）时**按非单号处理**，
     * 宁可让业务唯一键冲突照常暴露，也不误吞。</p>
     *
     * @param e         唯一键冲突异常
     * @param noKeyName 单号唯一键名
     * @return 是单号冲突返回 true
     */
    private static boolean isNoConflict(DuplicateKeyException e, String noKeyName) {
        String msg = e.getMessage();
        return msg != null && msg.contains(noKeyName);
    }
}
