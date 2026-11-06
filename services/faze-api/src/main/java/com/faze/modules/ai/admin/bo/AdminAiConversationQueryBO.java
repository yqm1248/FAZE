/*
 * 职责：A-28「AI 对话检索」会话列表的查询条件。
 * 调用方：modules/ai/admin/controller/AdminAiConversationController#list（@ModelAttribute 绑定 URL query）。
 * 数据来源：URL query 参数。
 * 注意：
 *   1. **只有这五个参数**（lead 2026-11-07 裁定）：`userId / beginTime / endTime / pageNum / pageSize`。
 *      列表级的「导出检索结果」「法条引用」「复核状态」三项在 H2 已裁定不做，所以**不提供对应过滤参数**，
 *      也不为将来的复核字段预留（§零.3 第 9 项：不要复核）。
 *   2. `beginTime` / `endTime` 用 **String 接收**、在 service 里解析：
 *      《接口规范》§5.4 要求「ISO-8601 带偏移」，但 PC 后台日期控件传的是**本地墙钟**（无偏移）。
 *      按 lead 2026-11-07 裁决，`/admin/ai-conversations` 这个新接口要做对：
 *      带偏移的串按偏移换算成 UTC；**不带偏移的 `yyyy-MM-ddTHH:mm:ss` / `yyyy-MM-dd HH:mm:ss`
 *      按 Asia/Shanghai 解释**再转 UTC；纯 `yyyy-MM-dd` 取下界当天 00:00:00、上界当天 23:59:59。
 *      解析失败回 401003（不静默忽略，否则筛选条件写错会拿到「全部数据」）。
 *   3. `userId` 为 Long；前端传空串时 Spring 会转成 null（不加过滤）。
 */
package com.faze.modules.ai.admin.bo;

import lombok.Data;

/**
 * A-28 AI 对话检索查询条件。
 */
@Data
public class AdminAiConversationQueryBO {

    /** 页码，从 1 开始（可空，默认 1）。 */
    private Integer pageNum;

    /** 每页条数（可空，默认 10；服务端 clamp 到 1..100）。 */
    private Integer pageSize;

    /** 归属用户 id（可空，精确匹配）。 */
    private Long userId;

    /** 会话创建时间下界（含；ISO-8601 可带偏移；无偏移按 Asia/Shanghai；纯日期取当天 00:00:00）。 */
    private String beginTime;

    /** 会话创建时间上界（含；ISO-8601 可带偏移；无偏移按 Asia/Shanghai；纯日期取当天 23:59:59）。 */
    private String endTime;
}
