/*
 * 职责：C5 配额操作的结果——C2 预扣时用来判断「是否超限」，并把「预扣日期」带给 C3 做精确回退。
 * 调用方：modules/ai/service/AiQuestionService（C2 第 ② 步）、AiStreamService（从 PendingAsk 取 day 回退）。
 * 数据来源：AiQuotaService.reserve 的返回值（Redis INCR 结果 + sys_config.ai_daily_quota）。
 * 注意：
 *   1. `allowed=false` 时**不要再调 refund**：超限时 Lua 脚本已把 INCR 原子回退掉。
 *   2. `retryAfterSeconds` 是到次日 0 点（Asia/Shanghai）的秒数；放响应头 `Retry-After`，
 *      **不进 body 的 msg**（接口规范 §6.5 裁决 11：动态数字走 header，稳定文案走 code）。
 *   3. `day` 是预扣当天（yyyyMMdd）：C3 首字节超时/上游失败回退时**必须用它**，不能用「今天」，
 *      否则跨日边界会回退到新一天的 key。
 */
package com.faze.modules.ai.dto;

/**
 * 配额预扣结果。
 *
 * @param allowed           是否允许本次提问
 * @param used              预扣后的已用轮次（超限时为回退后的值）
 * @param limit             每日上限
 * @param retryAfterSeconds 距离配额重置的秒数（仅超限时有意义）
 * @param day               预扣当天的日期（yyyyMMdd，Asia/Shanghai）
 */
public record QuotaReservation(boolean allowed, int used, int limit, long retryAfterSeconds, String day) {
}
