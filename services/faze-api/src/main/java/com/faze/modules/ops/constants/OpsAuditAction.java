/*
 * 职责：第 7 段（运营与治理）的审计动作码与目标类型常量，是 `@OperationLog(action = ...)` 的唯一来源。
 * 调用方：modules/ops 下所有标了 @OperationLog 的管理端写接口。
 * 数据来源：《第 7 段工作流》§三 A4 第 3 条（12 个动作码）+ C4 第 4 条（补 TICKET_REPLY，共 13 个）；
 *           §九 第 7 项要求回写《审计与日志规范》的「必审计动作清单」。
 * 注意：
 *   1. **不许在注解里写字符串字面量**：写错一个字（如 CONTENT_REVIEW_WARN 写成 CONTENT_WARN）
 *      审计记录照样落库，但按动作筛选时永远查不到——这类错只有常量能挡住。
 *   2. 动作码是**给人和报表看的稳定标识**，一旦上线不许改名（改了历史审计的筛选口径就断了）。
 *      需要新动作就加新码，不改旧码语义。
 *   3. `LOG_EXPORT` 不在本类：它由《审计与日志规范》§6.3 全局定义，日志中心导出直接复用。
 */
package com.faze.modules.ops.constants;

/**
 * 运营域审计动作码与目标类型。
 *
 * <p>全部为大写蛇形，与 {@code operation_log.action} / {@code operation_log.target_type} 对应。</p>
 */
public final class OpsAuditAction {

    /** 审计日志的 module 值：运营与治理。 */
    public static final String MODULE_OPS = "ops";

    // ── 内容审核处置（A-20）──────────────────────────────────────────────
    /** 忽略（误判）：不改用户状态。 */
    public static final String CONTENT_REVIEW_IGNORE = "CONTENT_REVIEW_IGNORE";
    /** 警告用户：写 user_violation(warning) + 站内信。 */
    public static final String CONTENT_REVIEW_WARN = "CONTENT_REVIEW_WARN";
    /** 封禁：写 user_violation(ban) + app_user.status=banned。 */
    public static final String CONTENT_REVIEW_BAN = "CONTENT_REVIEW_BAN";

    // ── 敏感词库（A-25 左栏）──────────────────────────────────────────────
    /** 新增词条。 */
    public static final String SENSITIVE_WORD_CREATE = "SENSITIVE_WORD_CREATE";
    /** 修改词条（词/分类/级别/语言/启停）。 */
    public static final String SENSITIVE_WORD_UPDATE = "SENSITIVE_WORD_UPDATE";
    /** 删除词条（物理删，before 快照进 detail）。 */
    public static final String SENSITIVE_WORD_DELETE = "SENSITIVE_WORD_DELETE";
    /** 批量导入 CSV。 */
    public static final String SENSITIVE_WORD_IMPORT = "SENSITIVE_WORD_IMPORT";

    // ── 申诉复核（A-25 右栏）──────────────────────────────────────────────
    /** 处置申诉：通过（accepted）/ 维持原判（rejected）。 */
    public static final String APPEAL_HANDLE = "APPEAL_HANDLE";

    // ── 公告（A-23）──────────────────────────────────────────────────────
    /** 新建公告（草稿或定时）。 */
    public static final String ANNOUNCEMENT_CREATE = "ANNOUNCEMENT_CREATE";
    /** 发送公告（立即发送 / 重试补发）。 */
    public static final String ANNOUNCEMENT_PUSH = "ANNOUNCEMENT_PUSH";

    // ── 工单（A-21 / A-22）────────────────────────────────────────────────
    /** 分配工单（含批量指派给当前管理员）。 */
    public static final String TICKET_ASSIGN = "TICKET_ASSIGN";
    /** 关闭工单（必填 close_reason）。 */
    public static final String TICKET_CLOSE = "TICKET_CLOSE";
    /** 回复工单（发站内信 + 推送）。 */
    public static final String TICKET_REPLY = "TICKET_REPLY";

    // ── 目标类型（operation_log.target_type）──────────────────────────────
    /** 敏感词条。 */
    public static final String TARGET_SENSITIVE_WORD = "SENSITIVE_WORD";
    /** 内容审核命中记录。 */
    public static final String TARGET_CONTENT_REVIEW = "CONTENT_REVIEW";
    /** 敏感词申诉单。 */
    public static final String TARGET_APPEAL = "SENSITIVE_WORD_APPEAL";
    /** 客服工单。 */
    public static final String TARGET_TICKET = "SERVICE_TICKET";
    /** 公告批次。 */
    public static final String TARGET_ANNOUNCEMENT = "ANNOUNCEMENT";
    /** 通知模板。 */
    public static final String TARGET_NOTIFY_TEMPLATE = "NOTIFY_TEMPLATE";
    /** 违规/处罚记录。 */
    public static final String TARGET_USER_VIOLATION = "USER_VIOLATION";

    /** 工具类，禁止实例化。 */
    private OpsAuditAction() {
    }
}
