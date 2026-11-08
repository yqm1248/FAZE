/*
 * 职责：段 5 的业务单号生成器（案件 CS / 提现 WD / 结算 JS / 送达回执 SD / 案例 CL），
 *       第 6 段在此基础上追加充值单前缀 RC（`nextRechargeNo`），机制未变。
 * 调用方：caseflow 的 CaseService、trade 的 WithdrawService / SettlementService、
 *         caseflow 的 DeliveryService、content 的 CaseLibraryService、
 *         第 6 段 trade 的 RechargeCreateExecutor。
 * 数据来源：Redis key `faze:seq:{前缀}:{yyyyMMdd}`；数据库唯一键是最后一道防线。
 * 注意：
 *   1. **禁止用 MAX(单号)+1**（并发必撞、表大了还慢）——《第 5 段工作流》§三 A3。
 *   2. 日期按**东八区**取，不是 UTC：单号是给人看的（用户报单号、客服查单）。
 *      与「库内时间存 UTC」不冲突——单号不是时间字段。
 *   3. key 的 TTL 2 天：跨天后旧 key 自然过期，不需要清理任务。
 *   4. Redis 不可用时**降级**（工作流 §三 A3 第 2 条）：序号 = 毫秒后 4 位 + 随机 2 位，
 *      仍是合法的 6 位序号，唯一索引兜底；**与段 2 的 OrderNoGenerator「硬依赖 Redis」不同**，
 *      这是段 5 的显式裁决，差异已记入段 5 偏差清单。
 *   5. 与段 2 的 OrderNoGenerator 并存而非合并：段 2 已验收，不动它；两套前缀空间不重叠。
 */
package com.faze.infra.seq;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 段 5 业务单号生成器。
 *
 * <p>格式：{@code 前缀2位 + yyyyMMdd + 序号6位}，共 16 位，大写字母 + 数字，**无分隔符**
 * （《第 5 段工作流》§零之二 第 17 项；设计稿里的 {@code WD-2026-0091} 横杠格式作废）。</p>
 */
@Service
@Slf4j
public class BizNoGenerator {

    /** 序号位数；用尽即拒绝（单日 100 万单足够，宁失败不撞号）。 */
    private static final int SEQ_WIDTH = 6;

    /** 当日可用上限（999999）。 */
    private static final long MAX_SEQ = 999_999L;

    /** 序号 key 的存活时间：跨天后自然过期，省掉清理任务。 */
    private static final Duration KEY_TTL = Duration.ofDays(2);

    /** 单号日期用的业务时区：东八区（给人看的单号，见类注释第 2 条）。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Redis key 前缀；`faze:` 是项目统一业务 key 前缀（与段 2 的 `faze:order:seq:` 并列）。 */
    private static final String KEY_PREFIX = "faze:seq:";

    /** 合法的 16 位单号：2 位大写前缀 + 14 位数字。单测与自检用。 */
    private static final Pattern VALID_NO = Pattern.compile("^[A-Z]{2}\\d{14}$");

    /** 降级路径的随机位宽（毫秒后 4 位 + 随机 2 位 = 6 位，仍满足 16 位长度）。 */
    private static final int FALLBACK_RANDOM_WIDTH = 2;

    /** 降级路径里取毫秒的后几位（避免高位每天都在变，低位分布更均匀）。 */
    private static final long FALLBACK_MILLIS_MOD = 10_000L;

    private final StringRedisTemplate redis;

    private final Clock clock;

    /**
     * Spring 注入用的构造器（系统时钟）。
     *
     * @param redis Redis 客户端
     */
    @Autowired
    public BizNoGenerator(StringRedisTemplate redis) {
        this(redis, Clock.system(BIZ_ZONE));
    }

    /**
     * 可注入时钟的构造器，仅供单测模拟跨天与降级。
     *
     * @param redis Redis 客户端
     * @param clock 时钟（时区需为 {@link #BIZ_ZONE}）
     */
    BizNoGenerator(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * 生成案件单号。
     *
     * @return 形如 {@code CS20261108000001}
     */
    public String nextCaseNo() {
        return next(BizPrefix.CASE);
    }

    /**
     * 生成提现单号。
     *
     * @return 形如 {@code WD20261108000001}
     */
    public String nextWithdrawNo() {
        return next(BizPrefix.WITHDRAW);
    }

    /**
     * 生成结算单号。
     *
     * @return 形如 {@code JS20261108000001}
     */
    public String nextSettlementNo() {
        return next(BizPrefix.SETTLEMENT);
    }

    /**
     * 生成送达回执号。
     *
     * @return 形如 {@code SD20261108000001}
     */
    public String nextDeliveryNo() {
        return next(BizPrefix.DELIVERY);
    }

    /**
     * 生成案例编号。
     *
     * @return 形如 {@code CL20261108000001}
     */
    public String nextCaseLibraryNo() {
        return next(BizPrefix.CASE_LIBRARY);
    }

    /**
     * 生成充值单号（第 6 段 B1 新增）。
     *
     * <p>前缀 {@code RC} 由《第 6 段工作流》§三 A1、§零之二 第 17 项指定，与既有五个前缀
     * 不重叠；沿用「Redis INCR + 唯一索引兜底 + 降级」同一套机制，不另写实现。</p>
     *
     * @return 形如 {@code RC20261108000001}
     */
    public String nextRechargeNo() {
        return next(BizPrefix.RECHARGE);
    }

    /**
     * 校验单号格式是否合法（16 位：2 大写前缀 + 14 数字）。
     *
     * @param no 待校验单号
     * @return 合法返回 true
     */
    public static boolean isValid(String no) {
        return no != null && VALID_NO.matcher(no).matches();
    }

    /**
     * 生成单号（内部统一实现）。
     *
     * <p>副作用：Redis INCR 一次；首次命中当天 key 时设置 TTL。Redis 异常时走降级路径并告警。</p>
     *
     * @param prefix 业务前缀
     * @return 完整单号
     * @throws BusinessException 当日号段用尽时抛 501001
     */
    private String next(BizPrefix prefix) {
        String day = LocalDate.now(clock).format(DAY_FORMAT);
        String key = KEY_PREFIX + prefix.code + ":" + day;

        Long seq;
        try {
            seq = redis.opsForValue().increment(key);
        } catch (RuntimeException e) {
            // 降级：不阻塞业务（提现/立案不能因为 Redis 抖动就全挂），靠唯一索引兜底
            log.warn("单号生成走降级路径：Redis INCR 异常 key={}", key, e);
            return build(prefix, day, fallbackSeq());
        }
        if (seq == null) {
            log.warn("单号生成走降级路径：Redis 返回空序号 key={}", key);
            return build(prefix, day, fallbackSeq());
        }
        if (seq == 1L) {
            redis.expire(key, KEY_TTL);
        }
        if (seq > MAX_SEQ) {
            log.error("当日单号已用尽 prefix={} day={} seq={}", prefix.code, day, seq);
            throw new BusinessException(ErrorCode.COMMON_INTERNAL, "当日单号已用尽");
        }
        return build(prefix, day, seq);
    }

    /**
     * 拼装单号。
     *
     * @param prefix 业务前缀
     * @param day    日期（yyyyMMdd）
     * @param seq    6 位以内的序号
     * @return 完整单号
     */
    private String build(BizPrefix prefix, String day, long seq) {
        return prefix.code + day + String.format("%0" + SEQ_WIDTH + "d", seq);
    }

    /**
     * 降级序号：毫秒后 4 位 + 随机 2 位，共 6 位。
     *
     * <p>为什么是 4+2 而不是工作流原话的「6 位时间戳 + 2 位随机」：那样是 8 位，超出 16 位单号长度；
     * 4+2 仍是 6 位，且同毫秒内随机位分散，唯一索引兜底。</p>
     *
     * @return 0~999999 的序号
     */
    private long fallbackSeq() {
        long millisPart = clock.millis() % FALLBACK_MILLIS_MOD;
        long randomPart = ThreadLocalRandom.current().nextLong(
                (long) Math.pow(10, FALLBACK_RANDOM_WIDTH));
        long seq = millisPart * (long) Math.pow(10, FALLBACK_RANDOM_WIDTH) + randomPart;
        return seq % (MAX_SEQ + 1);
    }

    /**
     * 业务前缀。与《数据表规范》§3.11 的单号前缀表一一对应。
     */
    private enum BizPrefix {
        /** 案件。 */
        CASE("CS"),
        /** 提现。 */
        WITHDRAW("WD"),
        /** 结算。 */
        SETTLEMENT("JS"),
        /** 送达回执。 */
        DELIVERY("SD"),
        /** 案例。 */
        CASE_LIBRARY("CL"),
        /** 充值单（第 6 段）。 */
        RECHARGE("RC");

        private final String code;

        BizPrefix(String code) {
            this.code = code;
        }
    }
}
