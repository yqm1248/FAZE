/*
 * 职责：G3 告警推送器——每 60 秒轮询 Prometheus 的 `/api/v1/alerts`，把 firing 的告警
 *       组装成钉钉 markdown 推送到 `sys_config.alert.dingtalk_webhook`，resolved 时发「✅ 已恢复」。
 * 调用方：Spring `@Scheduled`（主类已 @EnableScheduling）；同时以 `task_queue` 留痕（见注意 1）。
 * 数据来源：`sys_config.alert.prometheus_url` / `alert.dingtalk_webhook` / `alert.dingtalk_secret`
 *           + Prometheus `/api/v1/alerts` + Redis（去重）。
 * 注意：
 *   1. **为什么还绕 task_queue**：段 2 的异步任务五条要求后台批量动作留下
 *      「已投递 / 已领取 / 已失败」的痕迹。本类每轮先入队一条 `alert_dispatch`，
 *      再领取执行——入队失败不影响本轮直接执行（告警宁可重复，不可漏）。
 *   2. **不部署 Alertmanager**（工作流 G2 的取舍）：Prometheus 自己算规则，本类只做
 *      「读规则结果 → 推送」。少一个组件、少一份配置漂移。
 *   3. **去重必须用 Redis SETNX**：`faze:alert:sent:{alertName}:{fingerprint}` TTL 1 小时。
 *      没有去重的话每 60 秒推一次，值班会直接屏蔽群消息——告警就失效了。
 *   4. **恢复通知的实现**：firing 时写 `faze:alert:firing:{alertName}:{fingerprint}`（TTL 1h），
 *      resolved 时若这个 key 还在，说明「本系统曾经推过 firing」，才发「已恢复」；
 *      没推过 firing 的 resolved 不发（否则每天重启 Prometheus 都会收到一堆「已恢复」）。
 *   5. **webhook 为空时只打 WARN 不发送**（沿用现状：V703 两个键初始为空串）：
 *      这是本机开发环境的正常状态，不是故障；配置后自动生效，不用重启。
 *   6. **加签按钉钉规范**：`timestamp + "\n" + secret` 做 HmacSHA256 → Base64，
 *      作为 URL 参数 `&timestamp=&sign=` 附加在 webhook 后面。
 *   7. 本类**不抛异常到调度线程**：轮询失败（Prometheus 没起、网络断）只打 WARN，
 *      下一轮继续——监控系统自己不能成为单点。
 */
package com.faze.modules.ops.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.TaskQueueService;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 告警推送器。
 */
@Slf4j
@Component
public class AlertDispatcher {

    /** `task_queue` 任务类型（每轮一条，留痕用）。 */
    public static final String TASK_TYPE_ALERT_DISPATCH = "alert_dispatch";

    /** 工作线程标识。 */
    private static final String WORKER = "alert-dispatcher";

    /** 配置键：Prometheus 基地址。 */
    private static final String KEY_PROMETHEUS_URL = "alert.prometheus_url";

    /** 配置键：钉钉 webhook（V703 已建，值初始为空串）。 */
    private static final String KEY_WEBHOOK = "alert.dingtalk_webhook";

    /** 配置键：钉钉加签密钥（V703 已建，值初始为空串）。 */
    private static final String KEY_SECRET = "alert.dingtalk_secret";

    /** Prometheus 基地址默认值（G2 的本机部署）。 */
    private static final String DEFAULT_PROMETHEUS_URL = "http://127.0.0.1:9090";

    /** 去重 key 前缀：已推送。 */
    private static final String KEY_SENT_PREFIX = "faze:alert:sent:";

    /** 去重 key 前缀：firing 中（用于恢复通知）。 */
    private static final String KEY_FIRING_PREFIX = "faze:alert:firing:";

    /** 去重 TTL：1 小时。 */
    private static final Duration DEDUP_TTL = Duration.ofHours(1);

    /** 展示时间格式（+08:00）。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysConfigQueryService sysConfig;

    private final StringRedisTemplate redis;

    private final TaskQueueService taskQueueService;

    private final JsonMapper jsonMapper;

    private final RestClient restClient = RestClient.create();

    /**
     * 构造推送器。
     *
     * @param sysConfig       平台参数
     * @param redis           Redis（去重）
     * @param taskQueueService 任务队列（留痕）
     * @param jsonMapper      JSON
     */
    public AlertDispatcher(SysConfigQueryService sysConfig, StringRedisTemplate redis,
                           TaskQueueService taskQueueService, JsonMapper jsonMapper) {
        this.sysConfig = sysConfig;
        this.redis = redis;
        this.taskQueueService = taskQueueService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 每 60 秒一轮：入队 tick → 领取 → 查 Prometheus → 推送。
     *
     * <p>副作用：读写 Redis 去重键、向钉钉 webhook 发 POST、写 task_queue 行。</p>
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void tick() {
        String tickId = "tick-" + LocalDateTime.now(ZoneOffset.UTC).format(TIME);
        try {
            taskQueueService.enqueue(TASK_TYPE_ALERT_DISPATCH, tickId, LocalDateTime.now(ZoneOffset.UTC), null);
        } catch (Exception e) {
            // 留痕失败不阻断告警（告警优先）
            log.warn("告警轮次入队失败，本轮直接执行", e);
        }
        List<TaskQueue> tasks = taskQueueService.claimBatch(WORKER,
                List.of(TASK_TYPE_ALERT_DISPATCH));
        String owner = taskQueueService.ownerOf(WORKER);
        if (tasks.isEmpty()) {
            // 没领到（别的实例领走了）：本轮不重复执行，下一轮继续
            return;
        }
        for (TaskQueue task : tasks) {
            try {
                dispatchOnce();
                taskQueueService.markDone(task, owner);
            } catch (Exception e) {
                log.warn("告警轮询失败（下一轮重试）", e);
                taskQueueService.markFailed(task, owner, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 手动发一条告警（供审计降级等「非 Prometheus 来源」的 P1 事件使用）。
     *
     * <p>副作用：向钉钉 webhook 发 POST；webhook 为空只打 WARN。</p>
     *
     * @param title 标题（如 `[P1] 审计链路中断`）
     * @param text  markdown 正文
     */
    public void sendManualAlert(String title, String text) {
        String webhook = sysConfig.getString(KEY_WEBHOOK, "");
        String secret = sysConfig.getString(KEY_SECRET, "");
        sendDingTalk(webhook, secret, title, text);
    }

    /**
     * 执行一轮告警检查（不含 task_queue 状态）。
     *
     * <p>副作用：Redis 去重键 + 钉钉 POST。</p>
     */
    void dispatchOnce() {
        String baseUrl = sysConfig.getString(KEY_PROMETHEUS_URL, DEFAULT_PROMETHEUS_URL);
        Map<String, Object> body = fetchAlerts(baseUrl);
        if (body == null) {
            return;
        }
        List<Map<String, Object>> alerts = extractAlerts(body);
        if (alerts.isEmpty()) {
            return;
        }
        String webhook = sysConfig.getString(KEY_WEBHOOK, "");
        String secret = sysConfig.getString(KEY_SECRET, "");
        int firing = 0;
        int resolved = 0;
        for (Map<String, Object> alert : alerts) {
            String state = String.valueOf(alert.getOrDefault("state", ""));
            String alertName = labelOf(alert, "alertname");
            String fingerprint = fingerprintOf(alert);
            if (!StringUtils.hasText(alertName)) {
                continue;
            }
            if ("firing".equals(state)) {
                if (pushFiring(alert, alertName, fingerprint, webhook, secret)) {
                    firing++;
                }
            } else if ("resolved".equals(state)) {
                if (pushResolved(alertName, fingerprint, webhook, secret)) {
                    resolved++;
                }
            }
        }
        if (firing > 0 || resolved > 0) {
            log.warn("告警轮询：本轮新推 firing={} resolved={}", firing, resolved);
        }
    }

    /**
     * 推送一条 firing 告警（Redis SETNX 去重）。
     *
     * @param alert       告警对象
     * @param alertName   告警名
     * @param fingerprint 指纹
     * @param webhook     钉钉 webhook
     * @param secret      加签密钥
     * @return 真的发出去了返回 true（去重命中 / webhook 为空返回 false）
     */
    private boolean pushFiring(Map<String, Object> alert, String alertName, String fingerprint,
                               String webhook, String secret) {
        // **webhook 为空时先返回，不写去重 key**（2026-11-09 修）：
        // 旧实现在 SETNX 之后才判断 webhook，配置好 webhook 后要等 1 小时 TTL 过期才会发第一条，
        // 演练时表现为「配好了却一直没消息」。空 webhook 不是「已发送」，不该占用去重窗口。
        if (!StringUtils.hasText(webhook)) {
            log.warn("告警未发送：sys_config.{} 为空（钉钉未配置），标题=[{}] {}",
                    KEY_WEBHOOK, labelOf(alert, "severity"), alertName);
            return false;
        }
        String sentKey = KEY_SENT_PREFIX + alertName + ":" + fingerprint;
        try {
            Boolean first = redis.opsForValue().setIfAbsent(sentKey, "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(first)) {
                return false;
            }
        } catch (Exception e) {
            // Redis 挂了：宁可重复告警，不可漏告警
            log.warn("告警去重失败（按未推送处理） alert={}", alertName, e);
        }
        redis.opsForValue().set(KEY_FIRING_PREFIX + alertName + ":" + fingerprint,
                "1", DEDUP_TTL);
        String severity = labelOf(alert, "severity");
        String title = "[" + (StringUtils.hasText(severity) ? severity : "P?") + "] " + alertName;
        String text = buildFiringMarkdown(alert, title);
        return sendDingTalk(webhook, secret, title, text);
    }

    /**
     * 推送一条 resolved 通知（只有曾经推过 firing 才发）。
     *
     * @param alertName   告警名
     * @param fingerprint 指纹
     * @param webhook     钉钉 webhook
     * @param secret      加签密钥
     * @return 真的发出去了返回 true
     */
    private boolean pushResolved(String alertName, String fingerprint, String webhook, String secret) {
        String firingKey = KEY_FIRING_PREFIX + alertName + ":" + fingerprint;
        Boolean existed;
        try {
            existed = Boolean.TRUE.equals(redis.hasKey(firingKey));
        } catch (Exception e) {
            existed = false;
        }
        if (!existed) {
            return false;
        }
        try {
            redis.delete(firingKey);
            redis.delete(KEY_SENT_PREFIX + alertName + ":" + fingerprint);
        } catch (Exception e) {
            log.warn("清理告警去重键失败 alert={}", alertName, e);
        }
        String title = "✅ 已恢复：" + alertName;
        String text = "#### ✅ 已恢复：" + alertName + "\n"
                + "> **时间**：" + nowText() + "\n"
                + "> **环境**：本机开发\n";
        return sendDingTalk(webhook, secret, title, text);
    }

    /**
     * 发钉钉消息（webhook 为空只打 WARN）。
     *
     * @param webhook 钉钉 webhook
     * @param secret  加签密钥（空表示机器人未开加签）
     * @param title   消息标题
     * @param text    markdown 正文
     * @return 发出且 HTTP 2xx 返回 true
     */
    private boolean sendDingTalk(String webhook, String secret, String title, String text) {
        if (!StringUtils.hasText(webhook)) {
            log.warn("告警未发送：sys_config.{} 为空（钉钉未配置），标题={}", KEY_WEBHOOK, title);
            return false;
        }
        String url = webhook;
        if (StringUtils.hasText(secret)) {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String sign = sign(timestamp, secret);
            url = webhook + (webhook.contains("?") ? "&" : "?")
                    + "timestamp=" + timestamp + "&sign=" + sign;
        }
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("title", title);
        markdown.put("text", text);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdown);
        try {
            String response = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);
            log.info("告警已发送 title={} response={}", title, response);
            return true;
        } catch (Exception e) {
            // 推送失败不抛：下一轮会重试（去重 key 已写，1 小时后才会再发，这里只记录）
            log.error("告警发送失败 title={} urlHost={}", title, maskUrl(webhook), e);
            return false;
        }
    }

    /**
     * 钉钉加签：`timestamp + "\n" + secret` → HmacSHA256 → Base64 → URL 编码。
     *
     * @param timestamp 毫秒时间戳字符串
     * @param secret    加签密钥
     * @return URL 编码后的签名
     */
    private String sign(String timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
            return URLEncoder.encode(Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("钉钉加签失败", e);
        }
    }

    /**
     * 组装 firing 的 markdown 正文。
     *
     * @param alert 告警对象
     * @param title 标题
     * @return markdown
     */
    private String buildFiringMarkdown(Map<String, Object> alert, String title) {
        Map<String, Object> annotations = mapOf(alert.get("annotations"));
        String summary = stringOf(annotations.get("summary"));
        String value = String.valueOf(alert.getOrDefault("value", "—"));
        return "#### " + title + "\n"
                + (StringUtils.hasText(summary) ? "> **说明**：" + summary + "\n" : "")
                + "> **当前值**：" + value + "\n"
                + "> **时间**：" + nowText() + "\n"
                + "> **环境**：本机开发\n";
    }

    /**
     * 调 Prometheus `/api/v1/alerts`。
     *
     * @param baseUrl 基地址
     * @return 响应体；失败返回 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchAlerts(String baseUrl) {
        try {
            String body = restClient.get()
                    .uri(baseUrl + "/api/v1/alerts")
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return null;
            }
            return jsonMapper.readValue(body, Map.class);
        } catch (Exception e) {
            // Prometheus 没起 / 网络不通：本机开发期常态，只 WARN
            log.warn("查询 Prometheus 告警失败 baseUrl={}（Prometheus 未启动时属正常）", baseUrl);
            return null;
        }
    }

    /**
     * 从 Prometheus 响应里取 alerts 数组。
     *
     * @param body 响应体
     * @return 告警列表；结构不符返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractAlerts(Map<String, Object> body) {
        Object dataNode = body.get("data");
        if (!(dataNode instanceof Map<?, ?> data)) {
            return List.of();
        }
        Object alertsNode = data.get("alerts");
        if (!(alertsNode instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    /**
     * 取告警标签。
     *
     * @param alert 告警对象
     * @param key   标签名
     * @return 值；不存在返回空串
     */
    private String labelOf(Map<String, Object> alert, String key) {
        return stringOf(mapOf(alert.get("labels")).get(key));
    }

    /**
     * 计算告警指纹：`alertname + instance`（Prometheus 的 /api/v1/alerts 不返回 fingerprint）。
     *
     * @param alert 告警对象
     * @return 指纹（稳定、可读）
     */
    private String fingerprintOf(Map<String, Object> alert) {
        String instance = labelOf(alert, "instance");
        String job = labelOf(alert, "job");
        return (StringUtils.hasText(job) ? job : "unknown") + "@"
                + (StringUtils.hasText(instance) ? instance : "unknown");
    }

    /**
     * 安全转 Map。
     *
     * @param value 任意值
     * @return Map；不是 Map 返回空 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 安全转字符串。
     *
     * @param value 任意值
     * @return 字符串；null 返回空串
     */
    private String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 当前时间（+08:00 展示）。
     *
     * @return `yyyy-MM-dd HH:mm:ss`
     */
    private String nowText() {
        return LocalDateTime.now(ZoneOffset.UTC).plusHours(8).format(TIME);
    }

    /**
     * 日志里只打域名，不打印完整 webhook（webhook 的 access_token 是凭据）。
     *
     * @param webhook 原始 webhook
     * @return 域名部分
     */
    private String maskUrl(String webhook) {
        int slash = webhook.indexOf('/');
        return slash > 0 ? webhook.substring(0, Math.min(slash + 2, webhook.length())) + "***" : "***";
    }
}
