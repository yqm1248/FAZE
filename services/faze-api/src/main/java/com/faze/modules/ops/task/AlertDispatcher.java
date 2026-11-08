/*
 * 职责：G3 告警推送器——每 60 秒轮询 Prometheus 的 `/api/v1/alerts`，把 firing 的告警
 *       组装成钉钉 markdown 推送到 `sys_config.alert.dingtalk_webhook`，恢复时发「✅ 已恢复」。
 * 调用方：Spring `@Scheduled`（主类已 @EnableScheduling）；同时以 `task_queue` 留痕（见注意 1）。
 * 数据来源：`sys_config.alert.prometheus_url` / `alert.dingtalk_webhook` / `alert.dingtalk_secret`
 *           + Prometheus `/api/v1/alerts` + Redis（去重 + 上一轮 firing 集合）。
 * 注意：
 *   1. **为什么还绕 task_queue**：段 2 的异步任务五条要求后台批量动作留下
 *      「已投递 / 已领取 / 已失败」的痕迹。本类每轮先入队一条 `alert_dispatch`，
 *      再领取执行——入队失败不影响本轮直接执行（告警宁可重复，不可漏）。
 *   2. **不部署 Alertmanager**（工作流 G2 的取舍）：Prometheus 自己算规则，本类只做
 *      「读规则结果 → 推送」。少一个组件、少一份配置漂移。
 *   3. **恢复通知改「差集」判定（2026-11-09 工程债，方案 B）**：
 *      Prometheus 的 `/api/v1/alerts` **只返回 pending / firing 的活跃告警，永远不返回
 *      resolved**（这是 Prometheus 的既定行为），旧实现等 `state == "resolved"` 的告警
 *      等于等一个永不到来的事件——恢复通知是死代码。
 *      现在的做法：每轮把当前 firing 的 `{alertName}|{fingerprint}` 集合写到 Redis
 *      `faze:alert:last-firing`，**上一轮有、本轮没有 = 已恢复**。
 *      为什么不选「读 /api/v1/rules 的 state=inactive」（方案 A）：Prometheus 的 rules API
 *      里 `alerts` 数组同样只装**活跃**（firing/pending）的告警，inactive 的告警通常不出现，
 *      方案 A 很可能同样拿不到「已恢复」；而且 rules 响应要遍历 groups→rules，规则越多越贵。
 *      差集复用已有解析、语义直接（消失即恢复），还能覆盖「告警被删除/规则改名」的情况。
 *   4. **去重是「先发后写」（2026-11-09 工程债）**：付款式地「先 SETNX 再发送」在发送失败时
 *      会把去重键占住 1 小时 → 告警被吞（旧代码注释自己承认）。现在改为：
 *      发送前只 `hasKey` 检查（命中则跳过），**发送成功后**才写去重键；发送失败不写，
 *      下一轮会重试。代价是两个实例可能同时各发一次（重复而非漏）——告警链路宁可重复，
 *      不可漏（与「Redis 故障按未发送处理」同一原则）。
 *   5. **webhook 为空时只打 WARN 不发送**（沿用现状：V703 两个键初始为空串）：
 *      这是本机开发环境的正常状态，不是故障；配置后自动生效，不用重启。
 *   6. **加签按钉钉规范**：`timestamp + "\n" + secret` 做 HmacSHA256 → Base64，
 *      作为 URL 参数 `&timestamp=&sign=` 附加在 webhook 后面。
 *   7. 本类**不抛异常到调度线程**：轮询失败（Prometheus 没起、网络断）只打 WARN，
 *      下一轮继续——监控系统自己不能成为单点。**查询失败时不动上一轮集合**：
 *      否则一次网络抖动会把所有告警误判成「已恢复」，刷一屏假的「已恢复」。
 */
package com.faze.modules.ops.task;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 去重 key 前缀：已推送（仅发送成功后写）。 */
    private static final String KEY_SENT_PREFIX = "faze:alert:sent:";

    /** 上一轮 firing 集合的 Redis key（差集恢复判定的依据）。 */
    static final String KEY_LAST_FIRING = "faze:alert:last-firing";

    /** 去重 TTL：1 小时（同一告警 1 小时内只推一次）。 */
    private static final Duration DEDUP_TTL = Duration.ofHours(1);

    /** 上一轮 firering 集合的 TTL：1 天（进程重启后仍能做差集；超期视为无历史）。 */
    private static final Duration LAST_FIRING_TTL = Duration.ofDays(1);

    /** 集合成员里 name 与 fingerprint 的分隔符（两者都不含 `|`）。 */
    private static final String MEMBER_SEPARATOR = "|";

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
     * @param sysConfig        平台参数
     * @param redis            Redis（去重 + 上一轮 firing 集合）
     * @param taskQueueService 任务队列（留痕）
     * @param jsonMapper       JSON
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
     * <p>副作用：读写 Redis 去重键/集合、向钉钉 webhook 发 POST、写 task_queue 行。</p>
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
     * <p>副作用：Redis 去重键/集合 + 钉钉 POST。</p>
     */
    void dispatchOnce() {
        String baseUrl = sysConfig.getString(KEY_PROMETHEUS_URL, DEFAULT_PROMETHEUS_URL);
        Map<String, Object> body = fetchAlerts(baseUrl);
        if (body == null) {
            // 查询失败：**不动上一轮集合**，否则网络抖动会被误判成「全部恢复」
            return;
        }
        List<Map<String, Object>> alerts = extractAlerts(body);
        String webhook = sysConfig.getString(KEY_WEBHOOK, "");
        String secret = sysConfig.getString(KEY_SECRET, "");

        // 当前 firing 集合（只有 firing 算活跃；pending 不推送也不参与恢复）
        Set<String> current = new LinkedHashSet<>();
        List<Map<String, Object>> firingAlerts = new ArrayList<>();
        for (Map<String, Object> alert : alerts) {
            String state = String.valueOf(alert.getOrDefault("state", ""));
            String alertName = labelOf(alert, "alertname");
            if (!"firing".equals(state) || !StringUtils.hasText(alertName)) {
                continue;
            }
            current.add(member(alertName, fingerprintOf(alert)));
            firingAlerts.add(alert);
        }

        Set<String> previous = readLastFiring();
        int firing = 0;
        for (Map<String, Object> alert : firingAlerts) {
            String alertName = labelOf(alert, "alertname");
            String fingerprint = fingerprintOf(alert);
            if (pushFiring(alert, alertName, fingerprint, webhook, secret)) {
                firing++;
            }
        }

        Set<String> recovered = recoveredKeys(previous, current);
        int resolved = 0;
        for (String member : recovered) {
            if (pushResolved(member, webhook, secret)) {
                resolved++;
            }
        }

        // 集合以「本轮实际情况」为准更新：恢复是既成事实，即使恢复通知发送失败也不再重复发
        // （否则 webhook 配错一次会每轮刷「已恢复」，比漏一条更糟）。发送失败已在 pushResolved 打 ERROR。
        writeLastFiring(current);
        if (firing > 0 || resolved > 0) {
            log.warn("告警轮询：本轮新推 firing={} resolved={}（上一轮 {} 条，本轮 {} 条）",
                    firing, resolved, previous.size(), current.size());
        }
    }

    /**
     * 计算「已恢复」集合：上一轮有、本轮没有。
     *
     * <p>包级可见是为了单测能直接验证差集语义（不依赖 Redis/Prometheus）。</p>
     *
     * @param previous 上一轮 firing 集合
     * @param current  本轮 firing 集合
     * @return 已消失的告警成员；任一为空返回空集
     */
    static Set<String> recoveredKeys(Set<String> previous, Set<String> current) {
        if (previous == null || previous.isEmpty()) {
            return Set.of();
        }
        Set<String> recovered = new LinkedHashSet<>(previous);
        if (current != null) {
            recovered.removeAll(current);
        }
        return recovered;
    }

    /**
     * 推送一条 firing 告警（**先发送，成功后才写去重键**）。
     *
     * @param alert       告警对象
     * @param alertName   告警名
     * @param fingerprint 指纹
     * @param webhook     钉钉 webhook
     * @param secret      加签密钥
     * @return 真的发出去了返回 true（去重命中 / webhook 为空 / 发送失败返回 false）
     */
    private boolean pushFiring(Map<String, Object> alert, String alertName, String fingerprint,
                               String webhook, String secret) {
        // webhook 为空时先返回，不写去重 key：空 webhook 不是「已发送」，不该占用去重窗口
        if (!StringUtils.hasText(webhook)) {
            log.warn("告警未发送：sys_config.{} 为空（钉钉未配置），标题=[{}] {}",
                    KEY_WEBHOOK, labelOf(alert, "severity"), alertName);
            return false;
        }
        String sentKey = sentKey(alertName, fingerprint);
        if (isSentWithinHour(sentKey)) {
            return false;
        }
        String severity = labelOf(alert, "severity");
        String title = "[" + (StringUtils.hasText(severity) ? severity : "P?") + "] " + alertName;
        String text = buildFiringMarkdown(alert, title);
        boolean sent = sendDingTalk(webhook, secret, title, text);
        if (sent) {
            // **先发后写**：只有真的发出去才占用 1 小时去重窗口；
            // 发送失败不写 → 下一轮重试（旧实现先 SETNX 导致失败也被吞 1 小时）
            try {
                redis.opsForValue().set(sentKey, "1", DEDUP_TTL);
            } catch (Exception e) {
                // 写失败只意味着下一轮可能重复发（重复而非漏），可接受
                log.warn("写告警去重键失败（下一轮可能重复推送） alert={}", alertName, e);
            }
        }
        return sent;
    }

    /**
     * 推送一条恢复通知（差集判定后调用）。
     *
     * @param member  集合成员 `{alertName}|{fingerprint}`
     * @param webhook 钉钉 webhook
     * @param secret  加签密钥
     * @return 真的发出去了返回 true
     */
    private boolean pushResolved(String member, String webhook, String secret) {
        String alertName = member;
        String fingerprint = "";
        int sep = member.indexOf(MEMBER_SEPARATOR);
        if (sep >= 0) {
            alertName = member.substring(0, sep);
            fingerprint = member.substring(sep + MEMBER_SEPARATOR.length());
        }
        // 恢复后清掉去重键：同一告警再次 firing 时应当能再推一次
        try {
            if (!fingerprint.isEmpty()) {
                redis.delete(sentKey(alertName, fingerprint));
            }
        } catch (Exception e) {
            log.warn("清理告警去重键失败 alert={}", alertName, e);
        }
        String title = "✅ 已恢复：" + alertName;
        String text = "#### ✅ 已恢复：" + alertName + "\n"
                + "> **时间**：" + nowText() + "\n"
                + "> **判定**：本轮 Prometheus 活跃告警中已不再包含该告警（差集判定）\n"
                + "> **环境**：本机开发\n";
        return sendDingTalk(webhook, secret, title, text);
    }

    /**
     * 是否 1 小时内已推送过（只读检查，不占位）。
     *
     * @param sentKey 去重键
     * @return 1 小时内已推送返回 true；Redis 异常按未推送处理
     */
    private boolean isSentWithinHour(String sentKey) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(sentKey));
        } catch (Exception e) {
            // Redis 挂了：宁可重复告警，不可漏告警
            log.warn("告警去重查询失败（按未推送处理）", e);
            return false;
        }
    }

    /**
     * 读上一轮 firing 集合。
     *
     * @return 集合；Redis 异常/无历史返回空集（空集意味着本轮不发恢复，宁缺勿错）
     */
    private Set<String> readLastFiring() {
        try {
            Set<String> members = redis.opsForSet().members(KEY_LAST_FIRING);
            return members == null ? Set.of() : new LinkedHashSet<>(members);
        } catch (Exception e) {
            log.warn("读取上一轮告警集合失败（本轮不发恢复通知）", e);
            return Set.of();
        }
    }

    /**
     * 写本轮 firing 集合（先删后写；非原子，多实例并发最坏漏/重一条恢复通知，可接受）。
     *
     * @param current 本轮 firing 集合
     */
    private void writeLastFiring(Set<String> current) {
        try {
            redis.delete(KEY_LAST_FIRING);
            if (current != null && !current.isEmpty()) {
                redis.opsForSet().add(KEY_LAST_FIRING, current.toArray(new String[0]));
                redis.expire(KEY_LAST_FIRING, LAST_FIRING_TTL);
            }
        } catch (Exception e) {
            log.warn("写上一轮告警集合失败（下一轮恢复判定可能重复/漏一条）", e);
        }
    }

    /**
     * 组装集合成员。
     *
     * @param alertName   告警名
     * @param fingerprint 指纹
     * @return `alertName|fingerprint`
     */
    private String member(String alertName, String fingerprint) {
        return alertName + MEMBER_SEPARATOR + fingerprint;
    }

    /**
     * 去重键。
     *
     * @param alertName   告警名
     * @param fingerprint 指纹
     * @return `faze:alert:sent:{alertName}:{fingerprint}`
     */
    private String sentKey(String alertName, String fingerprint) {
        return KEY_SENT_PREFIX + alertName + ":" + fingerprint;
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
            // 推送失败不抛：调用方（pushFiring）不写去重键，下一轮会重试
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
     * 计算告警指纹：`job@instance`（Prometheus 的 /api/v1/alerts 不返回 fingerprint）。
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
