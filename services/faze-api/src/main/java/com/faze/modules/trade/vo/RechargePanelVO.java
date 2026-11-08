/*
 * 职责：充值页所需的全部参数快照（第 6 段 B2/B3）——单笔区间、剩余额度、档位、可用渠道、实名门槛。
 * 调用方：modules/trade/service/BalanceService#getBalanceWithRechargePanel → WalletController#balance。
 * 数据来源：sys_config（阈值与档位）+ recharge_record（当日已占用）+ app_user.realname_status。
 * 注意：
 *   1. **挂在 `GET /app/wallet/balance` 响应里，不新开接口**：B3「卡住怎么办」明确要求
 *      「剩余额度放进 balance 响应，不要为它新开一个接口」，而《第 6 段工作流》§五 5.2 的
 *      接口总数冻结为 8 个（新增 7 + 复用 1），没有留充值配置接口的位置。
 *      页面一次请求拿全，也避免「余额读到了但档位没读到」的中间态。
 *   2. **金额一律原生 `long` / `long[]`**，不是 `Long` / `List<Long>`：
 *      全局 Jackson 只把包装类 Long 转字符串（config/JacksonConfig 第 2 条），
 *      金额一旦变字符串，前端 `Number` 运算会静默出错。
 *   3. **档位不写死在前端**：`amountOptions` 直接来自 `sys_config.recharge.amount_options`，
 *      运营改档位不需要发版。
 *   4. **`realnameRequired` 与「额度为 0」是两回事**：未实名是拦截（405020），
 *      页面要直接禁用 CTA 并给实名入口，而不是显示「今日还可充值 ¥0.00」。
 */
package com.faze.modules.trade.vo;

import java.util.List;
import lombok.Data;

/**
 * 充值页参数快照。
 */
@Data
public class RechargePanelVO {

    /** 单笔最低金额（分）。 */
    private long minCents;

    /** 单笔最高金额（分）。 */
    private long maxCents;

    /** 当日额度上限（分）；未实名用户取 `recharge.unverified_daily_cents`。 */
    private long dailyLimitCents;

    /** 当日已占用（分）；口径为 pending + success（与下单校验一致）。 */
    private long usedDailyCents;

    /** 当日剩余可用（分）= dailyLimitCents - usedDailyCents，不小于 0。 */
    private long remainingDailyCents;

    /** 当日笔数上限。 */
    private int dailyCountLimit;

    /** 当日已用笔数（pending + success）。 */
    private long usedDailyCount;

    /** 可选金额档位（分），来自 `recharge.amount_options`。 */
    private long[] amountOptions;

    /** 当前启用的渠道；未启用的渠道不返回（页面据此决定渲染哪几个）。 */
    private List<ChannelOption> channels;

    /** 实名状态原值：none / pending / approved / rejected。 */
    private String realnameStatus;

    /** 是否必须先完成实名才能充值（未实名且额度配置为 0 时为 true）。 */
    private boolean realnameRequired;

    /**
     * 一个可用的支付渠道。
     */
    @Data
    public static class ChannelOption {

        /** 渠道持久化值：wechat / alipay / mock。 */
        private String code;

        /** 渠道名的 i18n key，如 `trade.pay.channelWechat`。 */
        private String i18nKey;
    }
}
