/*
 * 职责：A-26 操作日志「目标对象」列的可读名映射（F2 第 3 条：targetType + targetId + targetDisplay）。
 * 调用方：modules/ops/admin/service/AdminOperationLogService#page（列表组装完后统一填充）。
 * 数据来源：操作日志行（目标类型 + id）→ 各域的只读 Service / 本域 Mapper：
 *           orders（trade）→ 订单号；app_user（account）→ 昵称/脱敏手机号；
 *           service_ticket / announcement（本域 ops）→ 工单号 / 公告标题；lawyer（account）→ 律师姓名。
 * 注意：
 *   1. **只做白名单五类**（订单 / 用户 / 工单 / 公告 / 律师），映射不到就 `targetType#id`。
 *      **不做通用反射查表**：通用反射要维护一张「类型 → 表名」的配置，配置写错会查错表，
 *      而且等于把「任意表都能被日志页读」的口子打开。
 *   2. **不跨库 join**：`operation_log` 在 `faze` 库，管理员在 RuoYi 的 `faze_admin.sys_user`，
 *      跨库 join 需要额外授权且 SQL 会随 RuoYi 表结构漂移。`operatorType=admin` 的操作人
 *      就显示 `admin#id`（与 targetDisplay 的降级口径一致）。
 *   3. **批量查询**：一页最多 10 条日志，按 targetType 分组后每类一次 IN 查询，
 *      最坏 5 次查询；逐条查会是 N+1（10 条日志 × 5 表）。
 *   4. 已软删/查不到的目标一律降级成 `targetType#id`，**不显示「未知」**：
 *      「未知」看不出是什么类型，排查时反而要再查一次库。
 */
package com.faze.modules.ops.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.account.vo.LawyerBriefVO;
import com.faze.modules.account.vo.UserBriefVO;
import com.faze.modules.ops.admin.vo.OperationLogVO;
import com.faze.modules.ops.entity.Announcement;
import com.faze.modules.ops.entity.ServiceTicket;
import com.faze.modules.ops.mapper.AnnouncementMapper;
import com.faze.modules.ops.mapper.ServiceTicketMapper;
import com.faze.modules.trade.service.OrderQueryService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 操作日志「目标对象」可读名映射。
 */
@Service
@RequiredArgsConstructor
public class TargetDisplayService {

    /** 目标类型：订单。 */
    public static final String TYPE_ORDER = "ORDER";

    /** 目标类型：用户。 */
    public static final String TYPE_USER = "APP_USER";

    /** 目标类型：工单。 */
    public static final String TYPE_TICKET = "SERVICE_TICKET";

    /** 目标类型：公告。 */
    public static final String TYPE_ANNOUNCEMENT = "ANNOUNCEMENT";

    /** 目标类型：律师。 */
    public static final String TYPE_LAWYER = "LAWYER";

    /**
     * 目标类型 → 中文标签（**手写白名单，不做通用反射**）。
     *
     * <p>为什么需要这张表：库里实际的 target_type 有近 30 种，绝大多数没有可读业务号
     * （余额流水、系统参数、审核记录……）。不给标签的话，A-26 的「目标对象」列会显示
     * `USER_BALANCE#66` 这种裸 code，运营看不懂。**只加标签、不查表**（查不到更可读的值时
     * 退回 `标签#id`），仍然不做「类型 → 表名」的通用反射：那要维护一份会腐烂的映射表，
     * 且等于把任意的表都开放给日志页读。</p>
     *
     * <p>高频类型的实测分布（2026-11-09）：USER_BALANCE / APP_USER / REALNAME_AUTH /
     * LAWYER_APPLICATION / SYS_CONFIG —— 前五个必须能显示中文标签。</p>
     */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(TYPE_ORDER, "订单"),
            Map.entry(TYPE_USER, "用户"),
            Map.entry(TYPE_TICKET, "工单"),
            Map.entry(TYPE_ANNOUNCEMENT, "公告"),
            Map.entry(TYPE_LAWYER, "律师"),
            Map.entry("USER_BALANCE", "用户余额"),
            Map.entry("BALANCE_FLOW", "余额流水"),
            Map.entry("DASHBOARD", "看板报表"),
            Map.entry("REALNAME_AUTH", "实名认证"),
            Map.entry("ENTERPRISE_AUTH", "企业认证"),
            Map.entry("LAWYER_APPLICATION", "入驻申请"),
            Map.entry("SYS_CONFIG", "系统参数"),
            Map.entry("SENSITIVE_WORD", "敏感词"),
            Map.entry("SENSITIVE_WORD_APPEAL", "敏感词申诉"),
            Map.entry("CONTENT_REVIEW", "审核记录"),
            Map.entry("NOTIFY_TEMPLATE", "通知模板"),
            Map.entry("USER_VIOLATION", "违规记录"),
            Map.entry("LAW_ITEM", "法条"),
            Map.entry("AI_CONVERSATION", "AI 会话"),
            Map.entry("RECHARGE_RECORD", "充值单"),
            Map.entry("REFUND_RECORD", "退款单"),
            Map.entry("RECONCILIATION_DIFF", "对账差异"),
            Map.entry("OPERATION_LOG", "操作日志"),
            Map.entry("LOGIN_LOG", "登录日志"));

    private final OrderQueryService orderQueryService;
    private final AccountQueryService accountQueryService;
    private final LawyerQueryService lawyerQueryService;
    private final ServiceTicketMapper serviceTicketMapper;
    private final AnnouncementMapper announcementMapper;

    /**
     * 给一页日志填充 targetDisplay。
     *
     * <p>副作用：直接修改入参 VO 的 `targetDisplay` 字段。</p>
     *
     * @param rows 当前页日志
     */
    public void fill(List<OperationLogVO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> orderIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        Set<Long> ticketIds = new HashSet<>();
        Set<Long> announcementIds = new HashSet<>();
        Set<Long> lawyerIds = new HashSet<>();
        for (OperationLogVO row : rows) {
            Long id = row.getTargetId();
            if (id == null) {
                continue;
            }
            switch (targetTypeOf(row)) {
                case TYPE_ORDER -> orderIds.add(id);
                case TYPE_USER -> userIds.add(id);
                case TYPE_TICKET -> ticketIds.add(id);
                case TYPE_ANNOUNCEMENT -> announcementIds.add(id);
                case TYPE_LAWYER -> lawyerIds.add(id);
                default -> {
                    // 白名单外：不查库，直接降级
                }
            }
        }
        Map<Long, String> orders = orderIds.isEmpty() ? Map.of() : orderQueryService.mapOrderNosByIds(orderIds);
        Map<Long, UserBriefVO> users = userIds.isEmpty() ? Map.of() : accountQueryService.mapBriefs(userIds);
        Map<Long, LawyerBriefVO> lawyers = lawyerIds.isEmpty()
                ? Map.of() : lawyerQueryService.mapBriefsByIds(lawyerIds);
        Map<Long, String> tickets = loadTicketNos(ticketIds);
        Map<Long, String> announcements = loadAnnouncementTitles(announcementIds);

        for (OperationLogVO row : rows) {
            row.setTargetDisplay(display(targetTypeOf(row), row.getTargetId(),
                    orders, users, tickets, announcements, lawyers));
        }
    }

    /**
     * 组装一条展示文本。
     *
     * @param type          目标类型
     * @param id            目标 id
     * @param orders        订单号表
     * @param users         用户快照表
     * @param tickets       工单号表
     * @param announcements 公告标题表
     * @param lawyers       律师表
     * @return 展示文本（如 `订单 OD2026...`）；映射不到返回 `type#id`
     */
    private String display(String type,
                           Long id,
                           Map<Long, String> orders,
                           Map<Long, UserBriefVO> users,
                           Map<Long, String> tickets,
                           Map<Long, String> announcements,
                           Map<Long, LawyerBriefVO> lawyers) {
        if (!StringUtils.hasText(type)) {
            return id == null ? "" : String.valueOf(id);
        }
        if (id == null) {
            // 没有目标 id 的审计行（如「导出」「改配置」这类动作级日志）：
            // 显示中文标签而不是裸 code（T3 实测 `SYS_CONFIG` / `DASHBOARD` 裸显示不可读）
            String label = LABELS.get(type);
            return label == null ? type : label;
        }
        String readable = switch (type) {
            case TYPE_ORDER -> withLabel("订单", orders.get(id));
            case TYPE_USER -> withLabel("用户", userLabel(users.get(id)));
            case TYPE_TICKET -> withLabel("工单", tickets.get(id));
            case TYPE_ANNOUNCEMENT -> withLabel("公告", announcements.get(id));
            case TYPE_LAWYER -> withLabel("律师", lawyerLabel(lawyers.get(id)));
            default -> null;
        };
        if (readable != null) {
            return readable;
        }
        // 白名单内「没有可读业务号」的类型：显示中文标签 + id（比裸 code 可读）
        String label = LABELS.get(type);
        return label == null ? type + "#" + id : label + "#" + id;
    }

    /**
     * 取用户展示名：昵称优先，没有昵称用脱敏手机号。
     *
     * @param brief 用户快照
     * @return 展示名；查不到返回 null
     */
    private String userLabel(UserBriefVO brief) {
        if (brief == null) {
            return null;
        }
        return StringUtils.hasText(brief.nickname()) ? brief.nickname() : brief.phoneMasked();
    }

    /**
     * 取律师展示名。
     *
     * @param brief 律师快照
     * @return 姓名；查不到返回 null
     */
    private String lawyerLabel(LawyerBriefVO brief) {
        return brief == null ? null : brief.realName();
    }

    /**
     * 拼「标签 + 可读值」。
     *
     * @param label    中文标签
     * @param readable 可读值
     * @return 拼好的文本；可读值为空返回 null（调用方降级成 type#id）
     */
    private String withLabel(String label, String readable) {
        return StringUtils.hasText(readable) ? label + " " + readable : null;
    }

    /**
     * 批量取工单号。
     *
     * @param ids 工单主键
     * @return 主键 → 工单号
     */
    private Map<Long, String> loadTicketNos(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(ids.size());
        for (ServiceTicket ticket : serviceTicketMapper.selectList(Wrappers.<ServiceTicket>lambdaQuery()
                .in(ServiceTicket::getId, ids))) {
            if (ticket.getTicketNo() != null) {
                result.put(ticket.getId(), ticket.getTicketNo());
            }
        }
        return result;
    }

    /**
     * 批量取公告标题。
     *
     * @param ids 公告主键
     * @return 主键 → 标题
     */
    private Map<Long, String> loadAnnouncementTitles(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(ids.size());
        for (Announcement announcement : announcementMapper.selectList(Wrappers.<Announcement>lambdaQuery()
                .in(Announcement::getId, ids))) {
            if (announcement.getTitle() != null) {
                result.put(announcement.getId(), announcement.getTitle());
            }
        }
        return result;
    }

    /**
     * 取行里的目标类型（大小写按库内原值，白名单比较前统一大写）。
     *
     * @param row 日志行
     * @return 大写类型
     */
    private String targetTypeOf(OperationLogVO row) {
        return row.getTargetType() == null ? "" : row.getTargetType().toUpperCase();
    }
}
