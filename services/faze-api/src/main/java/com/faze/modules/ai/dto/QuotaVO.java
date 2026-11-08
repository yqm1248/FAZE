/*
 * 职责：`GET /api/v1/app/ai/quota` 的响应体——今日已用轮次 / 上限 / 重置时间（F5 输入框上方展示）。
 * 调用方：modules/ai/controller/AiQuotaController。
 * 数据来源：Redis `faze:ai:quota:<userId>:<yyyyMMdd>` + `sys_config.ai_daily_quota`。
 * 注意：
 *   1. 字段名固定 `used / limit / resetAt`（工作流 C5 与接口规范 §7.1.1 已登记），前端按它渲染。
 *   2. `used` 用原生 `long`：JacksonConfig 只把包装类 `Long` 序列化成字符串。
 *      **不要把它改成 `Long`**——否则前端拿到的会是 `"3"` 而不是 `3`，类型对不上。
 *   3. `resetAt` 用带时区的 ISO-8601 字符串（Asia/Shanghai 的次日 0 点），
 *      由服务端格式化，前端不自己算时区。
 */
package com.faze.modules.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 今日配额。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotaVO {

    /** 今日已用轮次。 */
    private long used;

    /** 每日轮次上限。 */
    private int limit;

    /** 配额重置时间（ISO-8601 带时区，如 2026-11-08T00:00:00+08:00）。 */
    private String resetAt;
}
