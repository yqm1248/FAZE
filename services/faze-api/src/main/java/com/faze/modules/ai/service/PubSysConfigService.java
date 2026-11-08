/*
 * 职责：F4 的公开配置读取服务——只把**允许匿名阅读**的白名单配置项暴露给未登录客户端
 *       （`GET /api/v1/pub/sys-config?keys=ai_enabled,ai_daily_quota`）。
 * 调用方：modules/ai/controller/PubSysConfigController（App 进入 AI 页时读一次，判断是否降级为纯法条检索）。
 * 数据来源：AiProperties（`sys_config` + 兜底值 + 5 秒实例缓存）；**不直接读表**。
 * 注意：
 *   1. **为什么用 AiProperties 而不是直接查 sys_config**：F4 的判据是「App 是否该降级」，
 *      C2 提交接口（AiQuestionService 第 ① 步）判定降级用的就是 AiProperties.enabled()。
 *      两处必须同源，否则会出现「页面显示 AI 可用、一提交就 degraded」的割裂。
 *      AiProperties 还负责「键缺失/值非法」的兜底，公开接口因此**永远不会因为配置缺失而 500**。
 *   2. **白名单是本类唯一出口**：`sys_config` 同一张表里躺着 `sms_fixed_code` /
 *      `pay_pwd_max_fail` 这类东西，匿名读任意键等于把短信验证链路废掉。
 *      非白名单键**静默忽略**（不报错、不提示键是否存在，防枚举），所以
 *      `keys=db.password` 的结果是空对象 `{}` 而不是 401/500。
 *   3. `keys` 缺省/空白 = 返回**全部**白名单键（当前两个）——App 进页面时不关心具体键名。
 *   4. 返回值的类型是**强类型**（Boolean / Integer），不是字符串：App 直接 `if (data.ai_enabled)`，
 *      给 `"true"` 字符串会让 `!` 判断恒为假、降级永远不触发（前端最易踩的坑）。
 *   5. 纯查询，不写审计（审计规范 §3.2 只对写操作）。
 */
package com.faze.modules.ai.service;

import com.faze.modules.ai.config.AiProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 公开白名单配置读取（F4）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PubSysConfigService {

    /** 白名单键：AI 总开关（false 时 App 降级为纯法条检索）。 */
    public static final String KEY_AI_ENABLED = "ai_enabled";

    /** 白名单键：每用户每日 AI 轮次上限（F5 的「今日还剩 N 次」用同一上限）。 */
    public static final String KEY_AI_DAILY_QUOTA = "ai_daily_quota";

    /**
     * 允许匿名读取的配置键白名单。
     *
     * <p>新增键时必须想清楚：这个值泄露给匿名用户会不会削弱任何一道校验。想不清楚就不加。
     * 新增非 AI 域键时，本服务应改为注入通用配置查询（当前两个键都属于 AI 域，所以走 AiProperties）。</p>
     */
    private static final List<String> WHITELIST = List.of(KEY_AI_ENABLED, KEY_AI_DAILY_QUOTA);

    /** 单次最多接受的请求键数（防 `keys=a,a,a,...` 长串做无意义解析）。 */
    private static final int MAX_REQUESTED_KEYS = 20;

    private final AiProperties aiProperties;

    /**
     * 读取白名单配置。
     *
     * <p>副作用：无（只读 AiProperties 的实例缓存；不写库、不写审计、不打敏感日志）。</p>
     *
     * @param keys 逗号分隔的配置键（可空；空 = 返回全部白名单键）；非白名单键静默忽略
     * @return 有序 Map：键 → 强类型值（Boolean / Integer）；没有任何白名单键命中时返回空 Map
     */
    public Map<String, Object> readWhitelisted(String keys) {
        List<String> requested = parseKeys(keys);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (String key : requested) {
            // 两个键都来自 AiProperties：值与 C2 的降级判断/配额同源，且带兜底值。
            if (KEY_AI_ENABLED.equals(key)) {
                result.put(key, aiProperties.enabled());
            } else if (KEY_AI_DAILY_QUOTA.equals(key)) {
                result.put(key, aiProperties.dailyQuota());
            }
            // 白名单外的键：静默跳过（不提示存在性，见类注释第 2 条）
        }
        log.debug("公开配置读取 requested={} returned={}", requested, result.keySet());
        return result;
    }

    /**
     * 规整请求的键列表。
     *
     * @param keys 逗号分隔字符串（可空、可带空白、可含空段）
     * @return 去重后的键列表；空/空白返回全部白名单键
     */
    private List<String> parseKeys(String keys) {
        if (!StringUtils.hasText(keys)) {
            return WHITELIST;
        }
        List<String> result = new ArrayList<>();
        for (String part : keys.split(",")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            String key = part.trim();
            // 去重：同一键重复请求只回一次（LinkedHashMap 也会去重，这里省掉无谓判断）
            if (!result.contains(key)) {
                result.add(key);
            }
            if (result.size() >= MAX_REQUESTED_KEYS) {
                break;
            }
        }
        return result;
    }
}
