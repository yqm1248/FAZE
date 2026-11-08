/*
 * 职责：第 6 段的 sys_config 配置键常量（渠道参数 + 充值规则，共 24 个）。
 * 调用方：infra/pay 的三个网关、PaymentGatewayFactory、RechargeService、D 组任务。
 * 数据来源：《第 6 段工作流》§三 A3 第 5 条（21 个键）+ 第 4 条（Mock 的 3 个测试开关）。
 * 注意：
 *   1. **键名统一 `pay.*` / `recharge.*` 点号风格**：与本项目已有的
 *      `lawyer.price.max_cents`、`withdraw.min_amount_cents` 一致。历史遗留的
 *      `pay_pwd_lock_minutes`（下划线）是段 1 的，**不改**——改键名会让线上配置失联。
 *   2. **加密项只放这里登记，取值走 ConfigCipher 解密**：`api_v3_key` 与
 *      `alipay.private_key` 在库里以 `ENC(...)` 包裹；解密后的值只进网关对象，
 *      **不进 DTO / 日志 / 响应**（A3 第 6 条）。
 *   3. **删键前先 grep**：本类每个常量都必须有人读，否则就是「死开关」
 *      （《后端代码规范》§11.4）。
 *   4. 本类只放 key，不放默认值——默认值在迁移 V601 里，改默认值走 PC 系统参数页。
 */
package com.faze.config;

/**
 * 支付与充值配置键。
 */
public final class SysConfigKeys {

    private SysConfigKeys() {
    }

    // ── 渠道开关 ────────────────────────────────────────────────────────────

    /** Mock 通道总开关。**生产必须 false**（ProdMockPayGuard 会在启动时拦）。 */
    public static final String PAY_MOCK_ENABLED = "pay.mock.enabled";

    /** 微信通道开关。 */
    public static final String PAY_WECHAT_ENABLED = "pay.wechat.enabled";

    /** 支付宝通道开关。 */
    public static final String PAY_ALIPAY_ENABLED = "pay.alipay.enabled";

    // ── 微信参数 ────────────────────────────────────────────────────────────

    /** 微信应用 ID。 */
    public static final String PAY_WECHAT_APPID = "pay.wechat.appid";

    /** 微信商户号。 */
    public static final String PAY_WECHAT_MCH_ID = "pay.wechat.mch_id";

    /** 微信 APIv3 密钥（**加密存储**，验签用）。 */
    public static final String PAY_WECHAT_API_V3_KEY = "pay.wechat.api_v3_key";

    /** 微信商户证书路径（文件不进仓库）。 */
    public static final String PAY_WECHAT_CERT_PATH = "pay.wechat.cert_path";

    /** 微信商户证书序列号。 */
    public static final String PAY_WECHAT_CERT_SERIAL = "pay.wechat.cert_serial";

    // ── 支付宝参数 ──────────────────────────────────────────────────────────

    /** 支付宝应用 ID。 */
    public static final String PAY_ALIPAY_APPID = "pay.alipay.appid";

    /** 支付宝商户号。 */
    public static final String PAY_ALIPAY_MCH_ID = "pay.alipay.mch_id";

    /** 支付宝应用私钥（**加密存储**，签名用）。 */
    public static final String PAY_ALIPAY_PRIVATE_KEY = "pay.alipay.private_key";

    /** 支付宝公钥（验签用，非敏感）。 */
    public static final String PAY_ALIPAY_PUBLIC_KEY = "pay.alipay.public_key";

    // ── 回调 ────────────────────────────────────────────────────────────────

    /** 回调域名前缀，拼成 `{base}/api/v1/pub/pay/callback/{channel}`；可留空（Mock 不依赖）。 */
    public static final String PAY_CALLBACK_BASE_URL = "pay.callback_base_url";

    /** 回调来源 IP 白名单（逗号分隔）；空 = 只靠验签。 */
    public static final String PAY_CALLBACK_IPS = "pay.callback_ips";

    // ── 充值规则 ────────────────────────────────────────────────────────────

    /** 单笔最低（分）。负责人 2026-11-02 定：10 分 = 0.1 元。 */
    public static final String RECHARGE_MIN_CENTS = "recharge.min_cents";

    /** 单笔最高（分）。负责人定：500000 分 = 5000 元。 */
    public static final String RECHARGE_MAX_AMOUNT_CENTS = "recharge.max_amount_cents";

    /** 单日累计上限（分）。负责人定：500000 分 = 5000 元。 */
    public static final String RECHARGE_MAX_DAILY_CENTS = "recharge.max_daily_cents";

    /** 单日笔数上限。 */
    public static final String RECHARGE_MAX_DAILY_COUNT = "recharge.max_daily_count";

    /** 档位（JSON 数组，单位分，如 `[10000,20000,50000,100000,200000]`）。 */
    public static final String RECHARGE_AMOUNT_OPTIONS = "recharge.amount_options";

    /** 充值单超时分钟数（负责人确认 30 分钟是上线值）。 */
    public static final String RECHARGE_EXPIRE_MINUTES = "recharge.expire_minutes";

    /** 未实名用户单日额度（分）。**0 = 完全不可充值**（拦截不是限额）。 */
    public static final String RECHARGE_UNVERIFIED_DAILY_CENTS = "recharge.unverified_daily_cents";

    // ── Mock 通道测试开关（只在 pay.mock.enabled=true 时生效，生产不许出现） ──

    /** 回调延迟毫秒（模拟真实到账时间，验 6-A2 的重放窗口）。 */
    public static final String PAY_MOCK_DELAY_MS = "pay.mock.delay_ms";

    /** Mock 结果：success / fail / callback_lost（下单成功但回调不发，造掉单）。 */
    public static final String PAY_MOCK_RESULT = "pay.mock.result";

    /** 回调金额偏移（分），造对账差异（6-A4）。 */
    public static final String PAY_MOCK_AMOUNT_OFFSET_CENTS = "pay.mock.amount_offset_cents";

    // ── 渠道对账（D3/D4，段 6 追加） ─────────────────────────────────────────

    /**
     * Mock 账单的差异注入规格（JSON，**验收专用，生产必须为空**）。
     *
     * <p>结构见 `infra/pay/reconcile/BillInjection`：`extraRows` 造 channel_only、
     * `dropOrderNos` 造 local_only、`amountShifts` 造 amount_mismatch。
     * 只在 `pay.mock.enabled=true` 时被 `ReconcileBillService` 读取；真实渠道的账单
     * 来自渠道，平台不生成也不改。默认值在 V602 里是**空串**（= 不注入）。</p>
     */
    public static final String RECONCILE_MOCK_INJECT = "reconcile.mock.inject";
}
