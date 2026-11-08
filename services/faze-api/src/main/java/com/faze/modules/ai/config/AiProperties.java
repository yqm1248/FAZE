/*
 * 职责：AI 域的业务配置读取器——**从 `sys_config` 实时读取，不绑 yml**（工作流 C1 第 4 条）。
 * 调用方：infra/ai/impl/DoubaoClientImpl（模型/超时/地址）、modules/ai/service/{AiQuestionService,
 *         AiStreamService,AiQuotaService}（开关/配额/检索条数/首字节超时）。
 * 数据来源：`sys_config` 的 8 个 AI 键（《第 3 段工作流》§五.5）+ 环境变量（API Key / 地址兜底）。
 * 注意：
 *   1. **代码里不留业务默认值**：`ai_enabled` / `ai_model` / `ai_daily_quota` / `ai_search_topn` /
 *      `ai_timeout_seconds` / `ai_first_byte_timeout_ms` 全部从库里读；本类只保留「读不到时」的兜底值
 *      （DEFAULT_* 常量，逐一注明依据）。兜底值不是配置来源，运营改配置永远以库为准。
 *   2. **API Key 不走 sys_config**：走环境变量 `FAZE_ARK_API_KEY`（本地在 deploy/.env，已被 .gitignore），
 *      兼容旧键名 `FAZE_DOUBAO_API_KEY`。技术路线 §11.2 的「Jasypt 加密」是**备选方案**——
 *      Jasypt 是为「密钥必须落配置文件」准备的，本项目用环境变量更短，**仓库里永远不会出现明文 API Key**。
 *   3. **缓存策略：实例字段 + 5 秒 TTL**（不是 static，不用 Spring Cache）。
 *      原因：一次 SSE 请求里会读到「开关/模型/超时/首字节/检索条数」5~8 次，每次都查 sys_config
 *      是 5~8 次库往返；5 秒缓存把重复查询压到 1 次。**不学 ops 域的「完全缓存」**——但也不做长缓存：
 *      `3-A7` 要求 PC 关掉开关后重进 AI 页立即降级，5 秒上限对这个操作不可感知。
 *      缓存字段用 `volatile` + 双检锁；`refresh()` 供运维/测试在改配置后立即生效（不依赖等 5 秒）。
 *   4. 读库异常（sys_config 抖动）**回退兜底值并记 warn**，不让 AI 整条链路 500——与
 *      `SysConfigQueryServiceImpl` 的「配置错误应降级而不是打挂业务」口径一致。
 */
package com.faze.modules.ai.config;

import com.faze.modules.ops.service.SysConfigQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AI 域配置读取器（sys_config + 环境变量，5 秒缓存）。
 */
@Component
@Slf4j
public class AiProperties {

    // ── sys_config 键名（与 database/seed/seg3_config.sql 逐字一致，改名等于配置失效）──
    /** AI 总开关（false 时 App 降级为纯法条检索）。 */
    public static final String KEY_ENABLED = "ai_enabled";

    /** 模型 id（必须带上下文后缀）。 */
    public static final String KEY_MODEL = "ai_model";

    /** 每用户每日轮次上限。 */
    public static final String KEY_DAILY_QUOTA = "ai_daily_quota";

    /** 检索带进 Prompt 的法条条数。 */
    public static final String KEY_SEARCH_TOPN = "ai_search_topn";

    /** 单次流上限（秒）。 */
    public static final String KEY_TIMEOUT_SECONDS = "ai_timeout_seconds";

    /** 首字节超时（毫秒）。 */
    public static final String KEY_FIRST_BYTE_TIMEOUT_MS = "ai_first_byte_timeout_ms";

    /**
     * 上游方舟地址的**排障覆盖口子**（不在 §五.5 的 8 个键里）。
     *
     * <p><b>本段不往 `database/seed/seg3_config.sql` 种这个键</b>（lead 2026-11-07 定）：
     * 上游地址是「部署环境参数」，不是「运营可调参数」——运营不该能改模型服务地址。
     * 正常运行走环境变量 `FAZE_ARK_BASE_URL` → 兜底 {@link #DEFAULT_ARK_BASE_URL}；
     * 这里读 sys_config 只是给排障留一个不用重启就能切地址的临时口子
     * （例如 C1 验收「把 baseUrl 改成错的 → 508001」，UPDATE 这个键即可，5 秒缓存生效）。</p>
     */
    public static final String KEY_ARK_BASE_URL = "ai_ark_base_url";

    // ── 兜底默认值：**只在 sys_config 读不到/非法时生效**，不是配置来源 ──
    /** 兜底：AI 默认开启（与 seed 的 'true' 一致；正式上线若灰度关闭由 A-08 改库）。 */
    private static final boolean DEFAULT_ENABLED = true;

    /** 兜底：模型 id（§5.5 默认值；**必须带上下文后缀**，否则长对话会中途截断且不报错）。 */
    private static final String DEFAULT_MODEL = "doubao-seed-1.6-32k";

    /** 兜底：每日 50 轮（§5.5）。 */
    private static final int DEFAULT_DAILY_QUOTA = 50;

    /** 兜底：检索 top 5（§5.5；代码上限 10，防止运营把 topN 改成 100 撑爆 Prompt）。 */
    private static final int DEFAULT_SEARCH_TOPN = 5;

    /** 兜底：单次流 120 秒（§5.5）。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** 兜底：首字节 5000 毫秒（§5.5）；C4 验收会临时改成 1，所以下限必须允许 1。 */
    private static final int DEFAULT_FIRST_BYTE_TIMEOUT_MS = 5_000;

    /** 兜底：火山方舟北京区地址（技术路线 §11.2 的开放平台域名）。 */
    private static final String DEFAULT_ARK_BASE_URL = "https://ark.cn-beijing.volces.com";

    /** 缓存 TTL：5 秒（见类注释第 3 条）。 */
    private static final long CACHE_TTL_MILLIS = 5_000L;

    /** 检索条数上限（防止运营手滑把 Prompt 撑爆）。 */
    private static final int MAX_SEARCH_TOPN = 10;

    /** 单次流上限的硬边界（秒）：允许 C4 验收改成 3，不允许配成 0 或负数。 */
    private static final int MIN_TIMEOUT_SECONDS = 1;

    /** 单次流上限的硬边界（秒）。 */
    private static final int MAX_TIMEOUT_SECONDS = 600;

    /** 首字节超时的硬边界（毫秒）：允许改成 1（C4 验收），最大 1 分钟。 */
    private static final int MIN_FIRST_BYTE_TIMEOUT_MS = 1;

    /** 首字节超时的硬边界（毫秒）。 */
    private static final int MAX_FIRST_BYTE_TIMEOUT_MS = 60_000;

    private final SysConfigQueryService sysConfigQueryService;

    /** API Key（进程启动时读环境变量；环境变量不会在运行期变化）。 */
    private final String apiKey;

    /** 配置快照缓存。 */
    private volatile Snapshot snapshot;

    /** 快照生成时间（毫秒）。 */
    private volatile long snapshotAtMillis;

    /**
     * 构造。
     *
     * @param sysConfigQueryService sys_config 只读查询服务（跨域只读，符合老规范 §2.3）
     */
    public AiProperties(SysConfigQueryService sysConfigQueryService) {
        this.sysConfigQueryService = sysConfigQueryService;
        this.apiKey = readApiKeyFromEnv();
    }

    /**
     * 配置快照（一次读库取全 7 项，避免多次往返）。
     *
     * @param enabled          AI 总开关
     * @param model            模型 id
     * @param dailyQuota       每日轮次上限
     * @param searchTopN       检索条数
     * @param timeoutSeconds   单次流上限（秒）
     * @param firstByteTimeoutMs 首字节超时（毫秒）
     * @param arkBaseUrl       上游地址（含兜底）
     */
    public record Snapshot(boolean enabled,
                           String model,
                           int dailyQuota,
                           int searchTopN,
                           int timeoutSeconds,
                           int firstByteTimeoutMs,
                           String arkBaseUrl) {
    }

    /**
     * AI 总开关（`3-A7` 降级依据）。
     *
     * @return true 表示允许调用豆包
     */
    public boolean enabled() {
        return current().enabled();
    }

    /**
     * 模型 id（含上下文后缀）。
     *
     * @return 非空模型 id
     */
    public String model() {
        return current().model();
    }

    /**
     * 每用户每日轮次上限。
     *
     * @return 非负整数（0 表示当日不允许提问）
     */
    public int dailyQuota() {
        return current().dailyQuota();
    }

    /**
     * 检索带进 Prompt 的法条条数。
     *
     * @return 1~10
     */
    public int searchTopN() {
        return current().searchTopN();
    }

    /**
     * 单次流上限（秒）。
     *
     * @return 1~600
     */
    public int timeoutSeconds() {
        return current().timeoutSeconds();
    }

    /**
     * 首字节超时（毫秒）。
     *
     * @return 1~60000
     */
    public int firstByteTimeoutMs() {
        return current().firstByteTimeoutMs();
    }

    /**
     * 上游方舟地址（不带尾部斜杠）。
     *
     * @return 形如 https://ark.cn-beijing.volces.com
     */
    public String arkBaseUrl() {
        return current().arkBaseUrl();
    }

    /**
     * API Key（环境变量）。
     *
     * @return Key；未配置时为空串（由 DoubaoClientImpl 决定报 508001）
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * 显式刷新缓存（改配置后立刻生效，不等 5 秒）。
     *
     * <p>副作用：清空本实例的配置快照；下一次 getter 会重新查库。</p>
     */
    public void refresh() {
        snapshot = null;
        snapshotAtMillis = 0L;
    }

    /**
     * 取当前快照（5 秒缓存 + 双检锁）。
     *
     * @return 配置快照
     */
    private Snapshot current() {
        Snapshot local = snapshot;
        long now = System.currentTimeMillis();
        if (local != null && now - snapshotAtMillis < CACHE_TTL_MILLIS) {
            return local;
        }
        synchronized (this) {
            Snapshot locked = snapshot;
            long lockedNow = System.currentTimeMillis();
            if (locked != null && lockedNow - snapshotAtMillis < CACHE_TTL_MILLIS) {
                return locked;
            }
            Snapshot loaded = load();
            snapshot = loaded;
            snapshotAtMillis = System.currentTimeMillis();
            return loaded;
        }
    }

    /**
     * 从 sys_config 读一次全量配置；读失败回退兜底值。
     *
     * @return 配置快照
     */
    private Snapshot load() {
        try {
            boolean enabled = sysConfigQueryService.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
            String model = sysConfigQueryService.getString(KEY_MODEL, DEFAULT_MODEL);
            int quota = sysConfigQueryService.getInt(KEY_DAILY_QUOTA, DEFAULT_DAILY_QUOTA);
            int topN = sysConfigQueryService.getInt(KEY_SEARCH_TOPN, DEFAULT_SEARCH_TOPN);
            int timeoutSeconds = sysConfigQueryService.getInt(
                    KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
            int firstByteTimeoutMs = sysConfigQueryService.getInt(
                    KEY_FIRST_BYTE_TIMEOUT_MS, DEFAULT_FIRST_BYTE_TIMEOUT_MS);
            String baseUrl = resolveArkBaseUrl();
            Snapshot loaded = new Snapshot(
                    enabled,
                    StringUtils.hasText(model) ? model.trim() : DEFAULT_MODEL,
                    clamp(quota, 0, 10_000),
                    clamp(topN, 1, MAX_SEARCH_TOPN),
                    clamp(timeoutSeconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
                    clamp(firstByteTimeoutMs, MIN_FIRST_BYTE_TIMEOUT_MS, MAX_FIRST_BYTE_TIMEOUT_MS),
                    baseUrl);
            log.debug("AI 配置已加载 enabled={} model={} quota={} topN={} timeout={}s firstByte={}ms",
                    loaded.enabled(), loaded.model(), loaded.dailyQuota(), loaded.searchTopN(),
                    loaded.timeoutSeconds(), loaded.firstByteTimeoutMs());
            return loaded;
        } catch (RuntimeException e) {
            // 配置表抖动不能把 AI 整条链路打挂：回退兜底值继续服务，日志 warn 便于巡检
            log.warn("读取 AI 配置失败，已回退兜底值（不影响服务，但请检查 sys_config）：{}",
                    e.getClass().getSimpleName());
            return new Snapshot(DEFAULT_ENABLED, DEFAULT_MODEL, DEFAULT_DAILY_QUOTA,
                    DEFAULT_SEARCH_TOPN, DEFAULT_TIMEOUT_SECONDS, DEFAULT_FIRST_BYTE_TIMEOUT_MS,
                    resolveArkBaseUrl());
        }
    }

    /**
     * 解析上游地址：sys_config 覆盖（排障口子，本段不种）→ 环境变量 `FAZE_ARK_BASE_URL` → 兜底。
     *
     * <p>注意来源优先级与用途（lead 2026-11-07 定）：</p>
     * <ol>
     *   <li>**正常路径是环境变量**：上游地址属部署环境参数，由运维在 `.env` 里配；</li>
     *   <li>**sys_config 只是排障口子**：不种种子数据，需要临时切地址/验 508001 时才 UPDATE；</li>
     *   <li>两者都没有才用官方默认域名。</li>
     * </ol>
     *
     * @return 去尾部斜杠的地址
     */
    private String resolveArkBaseUrl() {
        String fromDb = null;
        try {
            fromDb = sysConfigQueryService.getString(KEY_ARK_BASE_URL, null);
        } catch (RuntimeException e) {
            // 交给外层 load() 的兜底路径处理即可；这里单独吞掉会让地址回退不透传
            log.debug("读取 {} 失败：{}", KEY_ARK_BASE_URL, e.getClass().getSimpleName());
        }
        String candidate = StringUtils.hasText(fromDb) ? fromDb.trim() : System.getenv("FAZE_ARK_BASE_URL");
        String baseUrl = StringUtils.hasText(candidate) ? candidate.trim() : DEFAULT_ARK_BASE_URL;
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    /**
     * 读环境变量里的 API Key（`FAZE_ARK_API_KEY`，兼容旧键名 `FAZE_DOUBAO_API_KEY`）。
     *
     * @return Key；都没有时为空串
     */
    private String readApiKeyFromEnv() {
        String key = System.getenv("FAZE_ARK_API_KEY");
        if (StringUtils.hasText(key)) {
            return key.trim();
        }
        String legacy = System.getenv("FAZE_DOUBAO_API_KEY");
        return StringUtils.hasText(legacy) ? legacy.trim() : "";
    }

    /**
     * 夹取整数到区间。
     *
     * @param value 原始值
     * @param min   下限
     * @param max   上限
     * @return 夹取后的值
     */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
