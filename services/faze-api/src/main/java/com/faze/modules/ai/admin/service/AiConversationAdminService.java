/*
 * 职责：PC A-28「AI 对话检索」的后台业务（H2）——会话列表 / 会话详情（全量消息 + refs 快照）/
 *       单条会话导出（JSON，脱敏，回写导出标记，写审计）。
 * 调用方：modules/ai/admin/controller/AdminAiConversationController。
 * 数据来源：faze.ai_conversation / faze.ai_message（只读或导出回写）、faze.app_user（用户展示名，只读）、
 *           faze.law_item（refs 对应条文的「当前」状态，只读）。
 * 注意：
 *   1. **列表与详情是纯查询、不写 operation_log**（工作流 H2 第 2 条 / 负责人 2026-11-01 定：
 *      IM 原文「每次查看都审计」的口径不套用到 AI 对话——查看留痕强度降一档，只审计导出）。
 *   2. **导出必须审计**：`@OperationLog(action = "AI_CONVERSATION_EXPORT")`，detail 只写会话 id、
 *      导出条数、是否截断，**绝不写消息原文**（content 是 L4 级敏感字段）。
 *   3. **脱敏口径**（《AI对话留存策略》§一）：只对 `role='user'` 的消息正文与会话标题脱敏
 *      （手机号 `1[3-9]\d{9}` → `138****1234`；身份证保留前 3 后 2）；
 *      assistant 消息含法条原文**不脱敏**（法条是公开信息，脱敏反而破坏可追溯性）。
 *      **展示不脱敏**（详情接口回原文），只有**导出文件**脱敏——两者不能统一，理由见该文档 §一。
 *   4. **导出上限 200 条消息**（工作流 H2「卡住怎么办」）：超出时截断并提示（JSON 内 `truncated`
 *      + 响应头 `X-Export-Truncated`）。截断保留**时间顺序最早**的 200 条，保证导出文件内部连贯。
 *   5. **回写导出标记**：`ai_message.is_exported=1` + `export_urls` 追加 `{"type":"json","at":"…"}`
 *      （只追加不覆盖，同一会话多次导出会留多条记录）。只回写真正写进文件的那些消息。
 *   6. **refs 原样输出**：用 Jackson 读成 JSON 树（JsonNode）后嵌入响应/导出文件，
 *      不转成固定 DTO 再序列化——快照字段冻结、只读，反序列化成 DTO 会丢字段并改变格式。
 *      仅额外用 `LawRefSnapshot` **只读**提取 lawItemId 去查「当前」状态，不回写快照。
 *   7. 日志禁止出现消息原文、会话标题（L4）：只记 conversationId / 条数 / 是否截断。
 */
package com.faze.modules.ai.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.infra.law.entity.LawItem;
import com.faze.infra.law.mapper.LawItemMapper;
import com.faze.modules.ai.admin.bo.AdminAiConversationQueryBO;
import com.faze.modules.ai.admin.vo.AdminAiConversationDetailVO;
import com.faze.modules.ai.admin.vo.AdminAiConversationRowVO;
import com.faze.modules.ai.admin.vo.AdminAiConversationVO;
import com.faze.modules.ai.admin.vo.AdminAiMessageVO;
import com.faze.modules.ai.admin.vo.AdminLawItemCurrentVO;
import com.faze.modules.ai.dto.LawRefSnapshot;
import com.faze.modules.ai.entity.AiConversation;
import com.faze.modules.ai.entity.AiMessage;
import com.faze.modules.ai.mapper.AiConversationMapper;
import com.faze.modules.ai.mapper.AiMessageMapper;
import com.faze.modules.ai.mapper.AdminAiConversationRow;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PC AI 对话检索服务（A-28 / H2）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiConversationAdminService {

    /** 审计模块名。 */
    private static final String MODULE = "ai";

    /** 审计目标类型。 */
    private static final String TARGET_TYPE = "AI_CONVERSATION";

    /** 列表默认每页条数。 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 列表每页上限。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 单条会话导出上限（工作流 H2）：超过即截断。 */
    private static final int MAX_EXPORT_MESSAGES = 200;

    /** 问题摘要最大字数（与 SQL 的 LEFT(content,30) 对齐）。 */
    private static final int SUMMARY_MAX_CHARS = 30;

    /** 导出文件名时间格式（《AI对话留存策略》§四：`yyyyMMddHHmm`，按 Asia/Shanghai）。 */
    private static final DateTimeFormatter FILENAME_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /** 导出 JSON 内的时间格式（ISO-8601 UTC，与 E3 文档示例一致，带 Z 后缀）。 */
    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /** 兼容 Element Plus 原生格式 `yyyy-MM-dd HH:mm:ss`（《接口规范》§十 登记的不一致项）。 */
    private static final DateTimeFormatter LEGACY_LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 文件名时间用上海时区（负责人定的展示时区，避免服务器时区不同导致文件名漂）。 */
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 手机号正则：只匹配独立数字串（前后不能再接数字，避免误伤长数字中的片段）。 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 18 位身份证（末位可为 X/x）。 */
    private static final Pattern ID_CARD_18_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    /** 15 位老身份证。 */
    private static final Pattern ID_CARD_15_PATTERN = Pattern.compile("(?<!\\d)\\d{15}(?!\\d)");

    /** 身份证脱敏保留的前缀位数。 */
    private static final int ID_KEEP_HEAD = 3;

    /** 身份证脱敏保留的后缀位数。 */
    private static final int ID_KEEP_TAIL = 2;

    private final AiConversationMapper aiConversationMapper;
    private final AiMessageMapper aiMessageMapper;
    private final LawItemMapper lawItemMapper;
    private final JsonMapper jsonMapper;

    // ── 列表 ───────────────────────────────────────────────────────────────

    /**
     * 分页检索会话（A-28，**含已软删**）。
     *
     * <p>副作用：只读；不写审计（见类注释第 1 条）。</p>
     *
     * @param query 查询条件（userId / beginTime / endTime / 分页）
     * @return RuoYi 口径分页响应（rows / total）
     */
    public TableDataInfo<AdminAiConversationRowVO> list(AdminAiConversationQueryBO query) {
        int pageNum = query == null || query.getPageNum() == null || query.getPageNum() < 1
                ? 1 : query.getPageNum();
        int pageSize = query == null || query.getPageSize() == null
                ? DEFAULT_PAGE_SIZE : Math.min(Math.max(query.getPageSize(), 1), MAX_PAGE_SIZE);
        long offsetLong = (long) (pageNum - 1) * pageSize;
        int offset = (int) Math.min(offsetLong, Integer.MAX_VALUE);
        Long userId = query == null ? null : query.getUserId();
        LocalDateTime beginTime = parseQueryTime(query == null ? null : query.getBeginTime(), "beginTime", false);
        LocalDateTime endTime = parseQueryTime(query == null ? null : query.getEndTime(), "endTime", true);
        if (beginTime != null && endTime != null && beginTime.isAfter(endTime)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "beginTime: 不能晚于 endTime");
        }

        List<AdminAiConversationRow> rows = aiConversationMapper.selectAdminPage(
                userId, beginTime, endTime, pageSize, offset);
        long total = rows == null || rows.isEmpty() || rows.get(0).getTotal() == null
                ? 0L : rows.get(0).getTotal();
        List<AdminAiConversationRowVO> list = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (AdminAiConversationRow row : rows) {
                if (row != null) {
                    list.add(toRowVO(row));
                }
            }
        }
        log.debug("A-28 会话检索 page={} size={} rows={} total={}", pageNum, pageSize, list.size(), total);
        return TableDataInfo.of(list, total);
    }

    // ── 详情（全量消息 + refs 快照）────────────────────────────────────────

    /**
     * 查会话详情：会话主体 + 全量消息（含 refs/导出快照）+ refs 对应条文的当前状态。
     *
     * <p>副作用：只读；不写审计。</p>
     *
     * @param id 会话主键
     * @return `{conversation, messages, currentLawItems}`
     * @throws BusinessException 401004 会话不存在
     */
    public AdminAiConversationDetailVO detail(Long id) {
        AiConversation conversation = requireConversation(id);
        List<AiMessage> messages = aiMessageMapper.selectList(Wrappers.<AiMessage>lambdaQuery()
                .eq(AiMessage::getConversationId, conversation.getId())
                .orderByAsc(AiMessage::getId));
        if (messages == null) {
            messages = List.of();
        }
        Set<Long> lawItemIds = new LinkedHashSet<>();
        List<AdminAiMessageVO> messageVOs = new ArrayList<>(messages.size());
        for (AiMessage message : messages) {
            messageVOs.add(toMessageVO(message));
            collectLawItemIds(message.getRefs(), lawItemIds);
        }

        AdminAiConversationVO conversationVO = new AdminAiConversationVO();
        conversationVO.setId(conversation.getId());
        conversationVO.setUserId(conversation.getUserId());
        conversationVO.setUserName(userName(conversation.getUserId()));
        conversationVO.setTitle(conversation.getTitle());
        conversationVO.setLang(conversation.getLang());
        conversationVO.setCreatedAt(conversation.getCreatedAt());
        conversationVO.setUpdatedAt(conversation.getUpdatedAt());
        conversationVO.setDeletedAt(conversation.getDeletedAt());

        AdminAiConversationDetailVO detail = new AdminAiConversationDetailVO();
        detail.setConversation(conversationVO);
        detail.setMessages(messageVOs);
        detail.setCurrentLawItems(loadCurrentLawItems(lawItemIds));
        log.info("A-28 查看会话详情 conversationId={} messages={} currentLawItems={}",
                conversation.getId(), messageVOs.size(), detail.getCurrentLawItems().size());
        return detail;
    }

    // ── 导出 ───────────────────────────────────────────────────────────────

    /**
     * 导出单条会话为 JSON（脱敏），并回写导出标记 + 写审计。
     *
     * <p>副作用：UPDATE `ai_message.is_exported/export_urls/updated_at`（同一事务）+ 一条 operation_log。</p>
     *
     * @param id 会话主键
     * @return 导出内容（字节 + 文件名 + 条数 + 是否截断）
     * @throws BusinessException 401004 会话不存在
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "AI_CONVERSATION_EXPORT", targetType = TARGET_TYPE)
    public ExportPayload export(Long id) {
        AiConversation conversation = requireConversation(id);
        // 多取 1 条用于判断是否超上限（避免额外 count 查询）。
        List<AiMessage> fetched = aiMessageMapper.selectList(Wrappers.<AiMessage>lambdaQuery()
                .eq(AiMessage::getConversationId, conversation.getId())
                .orderByAsc(AiMessage::getId)
                .last("LIMIT " + (MAX_EXPORT_MESSAGES + 1)));
        boolean truncated = fetched != null && fetched.size() > MAX_EXPORT_MESSAGES;
        List<AiMessage> exported = fetched == null ? List.of()
                : (truncated ? new ArrayList<>(fetched.subList(0, MAX_EXPORT_MESSAGES)) : fetched);

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        String exportedAt = formatExportTime(nowUtc);

        // 任务 1：拼导出文档（结构按《AI对话留存策略》§四；additive 字段 exportedMessages/truncated 供页面提示截断）。
        LinkedHashMap<String, Object> document = new LinkedHashMap<>();
        LinkedHashMap<String, Object> conversationNode = new LinkedHashMap<>();
        conversationNode.put("id", conversation.getId());
        conversationNode.put("userId", conversation.getUserId());
        // 标题可能含手机号/身份证，与用户消息同口径脱敏（E3 §四）
        conversationNode.put("title", mask(conversation.getTitle()));
        conversationNode.put("lang", conversation.getLang());
        conversationNode.put("createdAt", formatExportTime(conversation.getCreatedAt()));
        conversationNode.put("deletedAt", formatExportTime(conversation.getDeletedAt()));
        document.put("conversation", conversationNode);

        List<Map<String, Object>> messageNodes = new ArrayList<>(exported.size());
        for (AiMessage message : exported) {
            messageNodes.add(toExportMessage(message));
        }
        document.put("messages", messageNodes);
        document.put("exportedMessages", messageNodes.size());
        document.put("truncated", truncated);
        document.put("exportedAt", exportedAt);

        byte[] content;
        try {
            content = jsonMapper.writeValueAsString(document).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // 序列化失败属服务端问题：抛 501001 语义的稳定错误（不暴露异常原文）
            log.error("A-28 导出序列化失败 conversationId={} error={}",
                    conversation.getId(), e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.COMMON_INTERNAL, "导出文档序列化失败 conversationId=" + id);
        }

        // 任务 2：回写导出标记（只回写真正进文件的消息；空会话跳过 UPDATE）。
        List<Long> exportedIds = exported.stream().map(AiMessage::getId).filter(Objects::nonNull).toList();
        if (!exportedIds.isEmpty()) {
            int updated = aiMessageMapper.markExported(conversation.getId(), exportedIds, exportedAt);
            log.debug("A-28 导出回写 conversationId={} updated={}", conversation.getId(), updated);
        }

        // 任务 3：审计（只记条数与截断标记，绝不写消息原文）。
        LinkedHashMap<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("conversationId", conversation.getId());
        auditDetail.put("exportedMessages", messageNodes.size());
        auditDetail.put("truncated", truncated);
        auditDetail.put("format", "json");
        AuditContext.mark(MODULE, "AI_CONVERSATION_EXPORT", TARGET_TYPE, String.valueOf(id),
                jsonMapper.writeValueAsString(auditDetail));

        String filename = buildFilename(conversation.getId(), LocalDateTime.now(ZONE_SHANGHAI));
        log.info("A-28 导出会话 conversationId={} messages={} truncated={}",
                conversation.getId(), messageNodes.size(), truncated);
        return new ExportPayload(content, filename, messageNodes.size(), truncated);
    }

    // ── 内部：查询 / 转换 ──────────────────────────────────────────────────

    /**
     * 取会话（**不过滤 deleted_at**：后台要能查「用户已删除」的会话）。
     *
     * @param id 会话主键
     * @return 会话
     * @throws BusinessException 401004 不存在
     */
    private AiConversation requireConversation(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "id: 不能为空");
        }
        AiConversation conversation = aiConversationMapper.selectById(id);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "AI 会话不存在 id=" + id);
        }
        return conversation;
    }

    /**
     * 取用户展示名。
     *
     * @param userId 用户 id（可空）
     * @return 昵称或手机号；查不到返回 null
     */
    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        return aiConversationMapper.selectUserName(userId);
    }

    /**
     * 列表行 → 出参（摘要压平空白）。
     *
     * @param row 查询行
     * @return 列表行
     */
    private AdminAiConversationRowVO toRowVO(AdminAiConversationRow row) {
        AdminAiConversationRowVO vo = new AdminAiConversationRowVO();
        vo.setId(row.getId());
        vo.setUserId(row.getUserId());
        vo.setUserName(row.getUserName());
        vo.setTitle(row.getTitle());
        vo.setLang(row.getLang());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setUpdatedAt(row.getUpdatedAt());
        vo.setDeletedAt(row.getDeletedAt());
        vo.setQuestionSummary(flatten(row.getQuestionSummary()));
        vo.setRefCount(row.getRefCount() == null ? 0L : row.getRefCount());
        return vo;
    }

    /**
     * 消息实体 → 出参（refs / exportUrls 解析成 JSON 树，原样返回）。
     *
     * @param message 消息实体
     * @return 消息 VO
     */
    private AdminAiMessageVO toMessageVO(AiMessage message) {
        AdminAiMessageVO vo = new AdminAiMessageVO();
        vo.setId(message.getId());
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setModel(message.getModel());
        vo.setPromptTokens(message.getPromptTokens());
        vo.setCompleteTokens(message.getCompleteTokens());
        vo.setContentId(message.getContentId());
        vo.setIsExported(message.getIsExported() != null && message.getIsExported() == 1);
        vo.setExportUrls(parseJson(message.getExportUrls(), message.getId(), "export_urls"));
        vo.setRefs(parseJson(message.getRefs(), message.getId(), "refs"));
        vo.setLang(message.getLang());
        vo.setCreatedAt(message.getCreatedAt());
        vo.setUpdatedAt(message.getUpdatedAt());
        return vo;
    }

    /**
     * 消息实体 → 导出文件里的一条（脱敏只作用于 user 消息正文）。
     *
     * @param message 消息实体
     * @return 有序 Map（字段顺序与 E3 文档示例一致）
     */
    private Map<String, Object> toExportMessage(AiMessage message) {
        LinkedHashMap<String, Object> node = new LinkedHashMap<>();
        node.put("id", message.getId());
        node.put("role", message.getRole());
        boolean userMessage = "user".equals(message.getRole());
        node.put("content", userMessage ? mask(message.getContent()) : message.getContent());
        if (StringUtils.hasText(message.getModel())) {
            node.put("model", message.getModel());
        }
        if (StringUtils.hasText(message.getContentId())) {
            node.put("contentId", message.getContentId());
        }
        JsonNode refs = parseJson(message.getRefs(), message.getId(), "refs");
        if (refs != null) {
            // refs 原样嵌入（法条是公开信息，不脱敏；也不回写快照）
            node.put("refs", refs);
        }
        node.put("createdAt", formatExportTime(message.getCreatedAt()));
        return node;
    }

    /**
     * 解析 JSON 列（refs / export_urls）。
     *
     * @param raw    库里的 JSON 字符串（可空）
     * @param msgId  消息 id（仅日志定位用）
     * @param column 列名（仅日志定位用）
     * @return JSON 树；空值或非法 JSON 返回 null（不抛异常：一条脏数据不应让整页 500）
     */
    private JsonNode parseJson(String raw, Long msgId, String column) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return jsonMapper.readTree(raw);
        } catch (RuntimeException e) {
            log.warn("ai_message.{} 不是合法 JSON，已按空处理 messageId={}", column, msgId);
            return null;
        }
    }

    /**
     * 从 refs 快照里收集 lawItemId（只读提取，不回写、不改变快照）。
     *
     * @param refsJson   库里的 refs JSON（可空）
     * @param collector  收集器
     */
    private void collectLawItemIds(String refsJson, Set<Long> collector) {
        if (!StringUtils.hasText(refsJson)) {
            return;
        }
        try {
            List<LawRefSnapshot> snapshots = jsonMapper.readValue(refsJson,
                    new TypeReference<List<LawRefSnapshot>>() {
                    });
            for (LawRefSnapshot snapshot : snapshots) {
                if (snapshot != null && snapshot.getLawItemId() != null) {
                    collector.add(snapshot.getLawItemId());
                }
            }
        } catch (RuntimeException e) {
            // 快照是只读历史数据，解析失败只影响「当前状态对比」，不影响消息展示
            log.warn("refs 快照解析失败，跳过当前状态对比（不影响消息展示）");
        }
    }

    /**
     * 批量查 refs 对应条文的当前状态（一次 SQL，不 N+1）。
     *
     * @param lawItemIds 去重后的法条 id 集合
     * @return 当前状态列表；入参为空返回空列表
     */
    private List<AdminLawItemCurrentVO> loadCurrentLawItems(Set<Long> lawItemIds) {
        if (lawItemIds == null || lawItemIds.isEmpty()) {
            return List.of();
        }
        // 不过滤 status / is_enabled：被修正与已失效的行正是「快照 vs 当前」要展示的差异
        List<LawItem> rows = lawItemMapper.selectList(Wrappers.<LawItem>lambdaQuery()
                .in(LawItem::getId, lawItemIds));
        List<AdminLawItemCurrentVO> result = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows == null) {
            return result;
        }
        for (LawItem row : rows) {
            if (row == null) {
                continue;
            }
            AdminLawItemCurrentVO vo = new AdminLawItemCurrentVO();
            vo.setLawItemId(row.getId());
            vo.setLawCode(row.getLawCode());
            vo.setLawName(row.getLawName());
            vo.setArticleNo(row.getArticleNo());
            vo.setStatus(row.getStatus());
            vo.setIsEnabled(row.getIsEnabled());
            vo.setEffectiveDate(row.getEffectiveDate());
            vo.setExpireDate(row.getExpireDate());
            vo.setUpdatedAt(row.getUpdatedAt());
            result.add(vo);
        }
        return result;
    }

    // ── 内部：脱敏 / 时间 / 文件名 ─────────────────────────────────────────

    /**
     * 导出脱敏：身份证（18/15 位）保留前 3 后 2，手机号保留前 3 后 4。
     *
     * <p>**先身份证后手机号**：18 位身份证里可能包含形如 `1[3-9]\d{9}` 的子串，
     * 先处理身份证可避免把其中的片段当手机号再打一次码。</p>
     *
     * @param text 原文（可空）
     * @return 脱敏后的文本；null 原样返回
     */
    private String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = ID_CARD_18_PATTERN.matcher(text)
                .replaceAll(match -> keepHeadTail(match.group(), ID_KEEP_HEAD, ID_KEEP_TAIL));
        masked = ID_CARD_15_PATTERN.matcher(masked)
                .replaceAll(match -> keepHeadTail(match.group(), ID_KEEP_HEAD, ID_KEEP_TAIL));
        return PHONE_PATTERN.matcher(masked)
                .replaceAll(match -> {
                    String phone = match.group();
                    return phone.substring(0, 3) + "****" + phone.substring(7);
                });
    }

    /**
     * 保留前 head 位与后 tail 位，中间用 `*` 等长替换（长度不变，便于人工核对位数）。
     *
     * @param value 原文
     * @param head  保留的前缀位数
     * @param tail  保留的后缀位数
     * @return 脱敏后的文本
     */
    private String keepHeadTail(String value, int head, int tail) {
        if (value == null || value.length() <= head + tail) {
            return value;
        }
        StringBuilder builder = new StringBuilder(value.length());
        builder.append(value, 0, head);
        for (int i = 0; i < value.length() - head - tail; i++) {
            builder.append('*');
        }
        builder.append(value, value.length() - tail, value.length());
        return builder.toString();
    }

    /**
     * 摘要压平空白（问题摘要是一行展示，带换行会出现半行空白）。
     *
     * @param summary SQL 截断后的摘要（可空）
     * @return 单行摘要；空值返回 null
     */
    private String flatten(String summary) {
        if (!StringUtils.hasText(summary)) {
            return null;
        }
        String flat = summary.replaceAll("\\s+", " ").trim();
        if (flat.length() <= SUMMARY_MAX_CHARS) {
            return flat;
        }
        return flat.substring(0, SUMMARY_MAX_CHARS) + "…";
    }

    /**
     * 格式化导出用时间（UTC，带 Z 后缀；空值返回 null）。
     *
     * @param time UTC 时间（可空）
     * @return 形如 2026-11-07T02:10:00Z；null 原样返回
     */
    private String formatExportTime(LocalDateTime time) {
        return time == null ? null : EXPORT_TIME.format(time);
    }

    /**
     * 解析查询时间参数为 UTC 的 LocalDateTime（与库内 `created_at` 同口径）。
     *
     * <p><b>时区口径（lead 2026-11-07 裁决）</b>：《接口规范》要求 ISO-8601 带偏移，
     * 但 PC 后台的日期控件传的是**本地墙钟**（无偏移，`yyyy-MM-ddTHH:mm:ss`）。若按 UTC 解释，
     * 运维选「今天 00:00–24:00」实际筛到的是北京时间今天 08:00 到明天 08:00（A-28 的按时间筛会假通过）。
     * 所以**无偏移的串一律按 Asia/Shanghai 解释成绝对时刻再转 UTC**：</p>
     * <ol>
     *   <li>带偏移 / 带 Z（`2026-11-07T00:00:00+08:00`、`2026-11-06T16:00:00Z`）→ 按偏移换算成 UTC；</li>
     *   <li>纯日期 `yyyy-MM-dd` → 北京时间当天：下界取 00:00:00、上界取 23:59:59（闭区间）；</li>
     *   <li>ISO 本地时间 `yyyy-MM-ddTHH:mm:ss`（无偏移）→ 按北京时间墙钟转 UTC；</li>
     *   <li>`yyyy-MM-dd HH:mm:ss`（Element Plus 原生）→ 同上。</li>
     * </ol>
     *
     * @param raw      原始参数（可空）
     * @param field    字段名（仅用于日志 detail，不进响应体）
     * @param endOfDay 纯日期时是否取当天 23:59:59（endTime=true / beginTime=false）
     * @return UTC 时间；未传返回 null
     * @throws BusinessException 401003 格式无法识别
     */
    private static LocalDateTime parseQueryTime(String raw, String field, boolean endOfDay) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        // ① 带偏移 / 带 Z：按偏移换算（OffsetDateTime 同时接受 'Z' 与 '+08:00'）
        try {
            return LocalDateTime.ofInstant(OffsetDateTime.parse(value).toInstant(), ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // 继续尝试下一种格式
        }
        // ② 纯日期：按北京时间当天边界
        try {
            LocalDate date = LocalDate.parse(value);
            return toUtc(endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay());
        } catch (DateTimeParseException ignored) {
            // 继续尝试下一种格式
        }
        // ③ ISO 本地时间（无偏移）：按北京时间墙钟解释
        try {
            return toUtc(LocalDateTime.parse(value));
        } catch (DateTimeParseException ignored) {
            // 继续尝试下一种格式
        }
        // ④ Element Plus 原生格式
        try {
            return toUtc(LocalDateTime.parse(value, LEGACY_LOCAL_TIME));
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_FORMAT,
                    field + ": 时间格式应为 ISO-8601（可带时区偏移）或 yyyy-MM-dd[ HH:mm:ss]，实际=" + value);
        }
    }

    /**
     * 把「北京时间墙钟」转成 UTC 的 LocalDateTime（库内 `created_at` 存 UTC）。
     *
     * @param shanghaiWallClock 北京时间墙钟（可空）
     * @return UTC 时间；入参 null 返回 null
     */
    private static LocalDateTime toUtc(LocalDateTime shanghaiWallClock) {
        if (shanghaiWallClock == null) {
            return null;
        }
        return LocalDateTime.ofInstant(shanghaiWallClock.atZone(ZONE_SHANGHAI).toInstant(), ZoneOffset.UTC);
    }

    /**
     * 生成导出文件名：`AI-CONV-<conversationId>-<yyyyMMddHHmm>.json`（Asia/Shanghai）。
     *
     * @param conversationId 会话 id
     * @param nowShanghai    上海时区的当前时间
     * @return 文件名
     */
    private String buildFilename(Long conversationId, LocalDateTime nowShanghai) {
        return "AI-CONV-" + conversationId + "-" + FILENAME_TS.format(nowShanghai) + ".json";
    }

    /**
     * 导出结果（字节 + 文件名 + 条数 + 截断标记）。
     *
     * @param content       JSON 文件内容（UTF-8）
     * @param filename      Content-Disposition 里的文件名
     * @param exportedMessages 实际导出条数
     * @param truncated     是否因超过 200 条被截断
     */
    public record ExportPayload(byte[] content, String filename, int exportedMessages, boolean truncated) {
    }
}
