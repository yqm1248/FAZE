/*
 * 职责：充值下单的风控策略与充值页参数快照——单笔区间、未实名拦截、单日金额/笔数限额、档位与可用渠道。
 * 调用方：modules/trade/service/RechargeOrderService#create（按 B1 规定的校验顺序调用）、
 *         modules/trade/service/BalanceService#getBalanceWithRechargePanel（B2 页面参数）。
 * 数据来源：sys_config（阈值/档位，走 SysConfigQueryService）+ recharge_record（当日占用汇总）
 *           + app_user.realname_status（走 AccountQueryService）+ PaymentGatewayFactory（启用渠道）。
 * 注意：
 *   1. **阈值一律读 `sys_config`，不写死在代码里**：负责人 2026-11-02 定的 0.1~5000 元、
 *      单日 5000 元 / 10 笔是对外承诺的运营参数，运营改参数不该发一次版。
 *      代码里只留「配置读不到时的兜底值」，且兜底值必须与 V601 迁移的种子一致。
 *   2. **`pending` 计入额度**：只算 `success` 的话用户可以连开多张未支付单把额度占满后
 *      只付其中一张，等于限额形同虚设（B1「卡住怎么办」原话）。`closed`/`failed` 不计，
 *      所以 D2 关单后额度必须自动恢复（B3 的验收点）。
 *   3. **未实名是「拦截」不是「限额」**：`recharge.unverified_daily_cents <= 0` 时下单直接抛
 *      405020，不与单日限额混成一个错误码——App 要据此引导去实名页，不是提示「额度不足」。
 *      若负责人将来把该值调成 > 0，则它作为未实名用户的单日上限参与限额判断，不抛 405020。
 *   4. **「单日」按东八区自然日**：用户体感的一天是 0 点~24 点（+8），不是 UTC 的 8 点换日。
 *      库里存 UTC，所以比较前先把当日 0 点换算成 UTC 时刻。
 *   5. 本类在**下单路径**只做判断，不写库；在**页面路径**（buildPanel）只读。两者共用同一套
 *      口径常量与 SQL，避免「页面显示的剩余额度」和「服务端校验的剩余额度」算出两个数。
 */
package com.faze.modules.trade.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.SysConfigKeys;
import com.faze.infra.pay.PaymentGatewayFactory;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.vo.RechargePanelVO;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 充值风控策略与页面参数。
 */
@Service
@Slf4j
public class RechargeQuotaPolicy {

    /** 实名已通过的状态值（`app_user.realname_status`）。 */
    public static final String REALNAME_APPROVED = "approved";

    /** 业务时区：东八区（「单日」的换日点，见类注释第 4 条）。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    /** UTC 时区（库内时间口径）。 */
    private static final ZoneId UTC_ZONE = ZoneId.of("UTC");

    // ── 配置读不到时的兜底值；必须与 V601__seg6_pay_config.sql 的种子一致 ──────────

    /** 单笔最低 10 分 = 0.1 元。 */
    private static final int DEFAULT_MIN_CENTS = 10;

    /** 单笔最高 500000 分 = 5000 元。 */
    private static final int DEFAULT_MAX_AMOUNT_CENTS = 500_000;

    /** 单日累计 500000 分 = 5000 元。 */
    private static final int DEFAULT_MAX_DAILY_CENTS = 500_000;

    /** 单日笔数上限。 */
    private static final int DEFAULT_MAX_DAILY_COUNT = 10;

    /** 未实名单日额度：0 = 完全不可充值（拦截）。 */
    private static final int DEFAULT_UNVERIFIED_DAILY_CENTS = 0;

    /** 充值单超时分钟数（负责人确认 30 分钟是上线值）。 */
    private static final int DEFAULT_EXPIRE_MINUTES = 30;

    /** 档位配置读不到时的兜底（与 V601 种子一致，单位分）。 */
    private static final long[] DEFAULT_AMOUNT_OPTIONS = {10_000L, 20_000L, 50_000L, 100_000L, 200_000L};

    private final SysConfigQueryService sysConfig;

    private final RechargeRecordMapper rechargeRecordMapper;

    private final AccountQueryService accountQueryService;

    private final PaymentGatewayFactory paymentGatewayFactory;

    private final JsonMapper jsonMapper;

    private final Clock clock;

    /**
     * Spring 注入用的构造器（系统时钟）。
     *
     * @param sysConfig            平台参数只读查询
     * @param rechargeRecordMapper 充值单查询
     * @param accountQueryService  实名状态查询
     * @param paymentGatewayFactory 渠道开关查询
     * @param jsonMapper           JSON 解析（档位数组）
     */
    @Autowired
    public RechargeQuotaPolicy(SysConfigQueryService sysConfig,
                               RechargeRecordMapper rechargeRecordMapper,
                               AccountQueryService accountQueryService,
                               PaymentGatewayFactory paymentGatewayFactory,
                               JsonMapper jsonMapper) {
        this(sysConfig, rechargeRecordMapper, accountQueryService, paymentGatewayFactory,
                jsonMapper, Clock.system(BIZ_ZONE));
    }

    /**
     * 可注入时钟的构造器，仅供单测固定「当前是哪一天」。
     *
     * @param sysConfig             平台参数只读查询
     * @param rechargeRecordMapper  充值单查询
     * @param accountQueryService   实名状态查询
     * @param paymentGatewayFactory 渠道开关查询
     * @param jsonMapper            JSON 解析
     * @param clock                 时钟（时区需为东八区）
     */
    RechargeQuotaPolicy(SysConfigQueryService sysConfig,
                        RechargeRecordMapper rechargeRecordMapper,
                        AccountQueryService accountQueryService,
                        PaymentGatewayFactory paymentGatewayFactory,
                        JsonMapper jsonMapper,
                        Clock clock) {
        this.sysConfig = sysConfig;
        this.rechargeRecordMapper = rechargeRecordMapper;
        this.accountQueryService = accountQueryService;
        this.paymentGatewayFactory = paymentGatewayFactory;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * 校验单笔金额落在允许区间内（B1 校验第 ② 步）。
     *
     * <p>同时拒「非正整数」：0 元单、负数、超大数都在这里拦掉，不让它们走到渠道。</p>
     *
     * @param amountCents 充值金额（分）
     * @throws BusinessException 405014 金额非法 / 超出区间
     */
    public void ensureAmountAllowed(long amountCents) {
        if (amountCents <= 0) {
            throw new BusinessException(ErrorCode.TRADE_RECHARGE_AMOUNT_INVALID);
        }
        int minCents = minCents();
        int maxCents = maxAmountCents();
        if (amountCents < minCents || amountCents > maxCents) {
            throw new BusinessException(ErrorCode.TRADE_RECHARGE_AMOUNT_INVALID,
                    "单笔充值区间 " + minCents + "~" + maxCents + " 分");
        }
    }

    /**
     * 校验未实名用户能否充值（B3 第 4 条：0 额度 = 完全不可充值）。
     *
     * <p>`pending`（审核中）与 `none` / `rejected` 同样按未实名处理：
     * 审核没通过之前钱不该进来，进来就成了「无实名账户的资金」，合规上说不清。</p>
     *
     * @param userId 下单用户
     * @return 该用户的单日额度上限（分）：已实名返回 null 表示「用常规单日上限」，
     *         未实名但额度 > 0 时返回该额度
     * @throws BusinessException 405020 未实名，不能充值
     */
    public Long ensureRealnameAndGetDailyLimit(Long userId) {
        String realnameStatus = resolveRealnameStatus(userId);
        if (REALNAME_APPROVED.equals(realnameStatus)) {
            return null;
        }
        int unverifiedDaily = unverifiedDailyCents();
        if (unverifiedDaily <= 0) {
            log.info("充值被拦截：未实名 userId={} realnameStatus={}", userId, realnameStatus);
            throw new BusinessException(ErrorCode.TRADE_RECHARGE_REALNAME_REQUIRED);
        }
        return (long) unverifiedDaily;
    }

    /**
     * 校验单日金额与笔数限额（B1 校验第 ④ 步）。
     *
     * @param userId      下单用户
     * @param amountCents 本次充值金额（分）
     * @param dailyLimit  单日额度上限（分）；未实名时由
     *                    {@link #ensureRealnameAndGetDailyLimit} 给出，已实名传 null 走常规上限
     * @throws BusinessException 405016 超出单日充值限额
     */
    public void ensureDailyQuota(Long userId, long amountCents, Long dailyLimit) {
        long limitCents = dailyLimit != null ? dailyLimit : maxDailyCents();
        int limitCount = maxDailyCount();
        LocalDateTime sinceUtc = todayStartUtc();

        long usedCents = rechargeRecordMapper.sumDailyCents(userId, sinceUtc);
        if (usedCents + amountCents > limitCents) {
            log.info("充值超出单日金额限额 userId={} used={} amount={} limit={}",
                    userId, usedCents, amountCents, limitCents);
            throw new BusinessException(ErrorCode.TRADE_RECHARGE_DAILY_LIMIT);
        }
        long usedCount = rechargeRecordMapper.countDaily(userId, sinceUtc);
        if (usedCount + 1 > limitCount) {
            log.info("充值超出单日笔数限额 userId={} used={} limit={}", userId, usedCount, limitCount);
            throw new BusinessException(ErrorCode.TRADE_RECHARGE_DAILY_LIMIT);
        }
    }

    /**
     * 组装充值页参数快照（B2 页面一次拿全，不新开接口）。
     *
     * <p>与下单校验共用同一套阈值与 SQL 口径（类注释第 5 条），保证「页面显示的剩余额度」
     * 与「服务端真正拦的额度」是同一个数。</p>
     *
     * @param userId 当前用户
     * @return 页面参数
     */
    public RechargePanelVO buildPanel(Long userId) {
        RechargePanelVO panel = new RechargePanelVO();
        String realnameStatus = resolveRealnameStatus(userId);
        boolean approved = REALNAME_APPROVED.equals(realnameStatus);
        int unverifiedDaily = unverifiedDailyCents();
        long limitCents = approved ? maxDailyCents() : Math.max(unverifiedDaily, 0);
        LocalDateTime sinceUtc = todayStartUtc();
        long usedCents = rechargeRecordMapper.sumDailyCents(userId, sinceUtc);
        long usedCount = rechargeRecordMapper.countDaily(userId, sinceUtc);

        panel.setMinCents(minCents());
        panel.setMaxCents(maxAmountCents());
        panel.setDailyLimitCents(limitCents);
        panel.setUsedDailyCents(usedCents);
        panel.setRemainingDailyCents(Math.max(limitCents - usedCents, 0L));
        panel.setDailyCountLimit(maxDailyCount());
        panel.setUsedDailyCount(usedCount);
        panel.setAmountOptions(amountOptions());
        panel.setChannels(enabledChannels());
        panel.setRealnameStatus(realnameStatus);
        // 未实名且额度为 0 = 拦截：页面禁用 CTA 并给实名入口，而不是显示「还可充值 ¥0.00」
        panel.setRealnameRequired(!approved && unverifiedDaily <= 0);
        return panel;
    }

    /**
     * 取充值单超时时间（下单时算 expireAt）。
     *
     * <p>用 `Instant + 分钟` 再换 UTC，不经过「先取本地时间再 plus」——后者在跨日边界
     * 容易算错一天（本项目业务时区无夏令时，但算法本身不依赖这个前提）。</p>
     *
     * @return 过期时刻（UTC）
     */
    public LocalDateTime resolveExpireAt() {
        int minutes = sysConfig.getInt(SysConfigKeys.RECHARGE_EXPIRE_MINUTES, DEFAULT_EXPIRE_MINUTES);
        if (minutes <= 0) {
            minutes = DEFAULT_EXPIRE_MINUTES;
        }
        Instant expireInstant = clock.instant().plusSeconds(minutes * 60L);
        return LocalDateTime.ofInstant(expireInstant, UTC_ZONE);
    }

    // ── 内部：阈值读取（下单与页面共用） ──────────────────────────────────────

    /**
     * 单笔最低（分）。
     *
     * @return 配置值；缺失走兜底
     */
    private int minCents() {
        return sysConfig.getInt(SysConfigKeys.RECHARGE_MIN_CENTS, DEFAULT_MIN_CENTS);
    }

    /**
     * 单笔最高（分）。
     *
     * @return 配置值；缺失走兜底
     */
    private int maxAmountCents() {
        return sysConfig.getInt(SysConfigKeys.RECHARGE_MAX_AMOUNT_CENTS, DEFAULT_MAX_AMOUNT_CENTS);
    }

    /**
     * 单日累计上限（分）。
     *
     * @return 配置值；缺失走兜底
     */
    private int maxDailyCents() {
        return sysConfig.getInt(SysConfigKeys.RECHARGE_MAX_DAILY_CENTS, DEFAULT_MAX_DAILY_CENTS);
    }

    /**
     * 单日笔数上限。
     *
     * @return 配置值；缺失走兜底
     */
    private int maxDailyCount() {
        return sysConfig.getInt(SysConfigKeys.RECHARGE_MAX_DAILY_COUNT, DEFAULT_MAX_DAILY_COUNT);
    }

    /**
     * 未实名用户单日额度（分）；0 表示拦截。
     *
     * @return 配置值；缺失走兜底
     */
    private int unverifiedDailyCents() {
        return sysConfig.getInt(SysConfigKeys.RECHARGE_UNVERIFIED_DAILY_CENTS,
                DEFAULT_UNVERIFIED_DAILY_CENTS);
    }

    /**
     * 取实名状态（用户不存在/已软删按 none 处理）。
     *
     * @param userId 用户主键
     * @return 状态值，永不为 null
     */
    private String resolveRealnameStatus(Long userId) {
        return accountQueryService.findRealnameStatus(userId).orElse("none");
    }

    /**
     * 解析金额档位（分）。
     *
     * <p>配置是 JSON 数组字符串；解析失败时**回退默认档位并告警**——档位是展示项，
     * 不能因为运营写错一个括号就让整个充值页白屏。</p>
     *
     * @return 档位数组（分），保证非空
     */
    private long[] amountOptions() {
        String raw = sysConfig.getString(SysConfigKeys.RECHARGE_AMOUNT_OPTIONS, null);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_AMOUNT_OPTIONS.clone();
        }
        try {
            long[] parsed = jsonMapper.readValue(raw, long[].class);
            return parsed == null || parsed.length == 0 ? DEFAULT_AMOUNT_OPTIONS.clone() : parsed;
        } catch (RuntimeException e) {
            log.warn("档位配置格式非法，回退默认档位 raw={}", raw, e);
            return DEFAULT_AMOUNT_OPTIONS.clone();
        }
    }

    /**
     * 取当前启用的渠道。
     *
     * @return 渠道清单（未启用的不返回）
     */
    private List<RechargePanelVO.ChannelOption> enabledChannels() {
        List<RechargePanelVO.ChannelOption> channels = new ArrayList<>();
        for (PayChannel channel : paymentGatewayFactory.enabledChannels()) {
            RechargePanelVO.ChannelOption option = new RechargePanelVO.ChannelOption();
            option.setCode(channel.getCode());
            option.setI18nKey(channel.i18nKey());
            channels.add(option);
        }
        return channels;
    }

    /**
     * 当日 0 点（东八区）换算成 UTC 时刻，供 SQL 比较 `created_at`。
     *
     * @return UTC 的当日零点
     */
    private LocalDateTime todayStartUtc() {
        return LocalDate.now(clock).atStartOfDay(BIZ_ZONE)
                .withZoneSameInstant(UTC_ZONE).toLocalDateTime();
    }
}
