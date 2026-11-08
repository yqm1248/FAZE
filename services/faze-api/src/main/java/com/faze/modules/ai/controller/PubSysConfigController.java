/*
 * 职责：F4——`GET /api/v1/pub/sys-config?keys=ai_enabled,ai_daily_quota` 公开配置读取接口。
 * 调用方：App AI 页（F4/3-A7 进页时读一次 `ai_enabled` 判断是否降级；F5 读 `ai_daily_quota`）。
 * 数据来源：PubSysConfigService → AiProperties（sys_config + 兜底值），**只读白名单键**。
 * 注意：
 *   1. **路径在 `/api/v1/pub` 下且无鉴权是设计如此**（SecurityConfig 已 permitAll）：
 *      降级判断发生在用户还没登录/未进会话时，必须匿名可读；这也意味着它必须限死白名单
 *      （`sys_config` 里的短信固定码等敏感项不能漏出去）。
 *   2. **非白名单键静默忽略、不当错误**：`keys=db.password` 返回 `data:{}` 且 HTTP 200
 *      （工作流 F4「算做完」：只返回白名单键，传 `keys=db.password` 返回空、不报 500）。
 *      这里**不做 401004**——报错就等于确认「这个键存在但你不给看」，可被用来枚举配置项。
 *   3. 响应体形状按《接口规范》§7.1.1 冻结：`{ai_enabled: true, ai_daily_quota: 50}`，
 *      值是强类型（布尔/整数），不是字符串。
 *   4. Controller 只收参 + 包 R，白名单与类型在 service，**不注入 Mapper**。
 *   5. 纯查询，不标 `@OperationLog`。
 */
package com.faze.modules.ai.controller;

import com.faze.common.R;
import com.faze.modules.ai.service.PubSysConfigService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开白名单配置接口（F4）。
 */
@RestController
@RequestMapping("/api/v1/pub/sys-config")
@RequiredArgsConstructor
public class PubSysConfigController {

    private final PubSysConfigService pubSysConfigService;

    /**
     * 读取白名单配置。
     *
     * <p>副作用：无（纯读）。非白名单键被静默忽略。</p>
     *
     * @param keys 逗号分隔的配置键；空 = 全部白名单键（当前 `ai_enabled` / `ai_daily_quota`）
     * @return `data` 为「键 → 强类型值」的对象；无白名单键命中时是 `{}`
     */
    @GetMapping
    public R<Map<String, Object>> get(@RequestParam(required = false) String keys) {
        return R.ok(pubSysConfigService.readWhitelisted(keys));
    }
}
