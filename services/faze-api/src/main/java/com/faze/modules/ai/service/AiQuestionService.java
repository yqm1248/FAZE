/*
 * 职责：C2 提交问题的编排服务——校验开关 → 预扣配额 → 建/取会话 → 写 user 消息 →
 *       输入拦截 → 法条检索 → 组装 Prompt → 暂存 Redis → 返回 streamUrl。
 *       **本类不等回答**（两段式第一步，工作流 C2 第 1 条）。
 * 调用方：modules/ai/controller/AiConversationController#submit。
 * 数据来源：app_user（JWT 里的 userId）、ai_conversation / ai_message（写）、
 *           B1 LawSearchService、A4 GlossaryService、D1 PromptAssembler、D2 InputGuard、C5 AiQuotaService、
 *           sys_config（AiProperties）。
 * 注意：
 *   1. **顺序不能换**（工作流 C2 第 3 条）：
 *      ① ai_enabled ② 配额预扣 ③ 建/取会话 ④ 写 user 消息 ⑤ 输入拦截 ⑥ 检索 ⑦ 组装 Prompt ⑧ 返回。
 *      尤其 ⑤ 必须在 ④ 之后——D2 要求「原始输入落库备查」，拦截前 user 原文已经写进 ai_message。
 *   2. **拦截命中不扣配额**（D2 第 5 条）：先 `refund` 再抛/返回 408004/408005；
 *      同时写一条 `role='assistant'` 的固定引导文案（`refs` 为 NULL），让历史里能解释「为什么没有回答」。
 *   3. **ai_enabled=false 不报错**：返回 `R.ok` + `{ degraded: true, ... }`（HTTP 200），
 *      不预扣配额、不建会话、不写消息（工作流 C2 第 4 条）。
 *   4. **归属校验进 SQL**：传 conversationId 时 `WHERE id=? AND user_id=? AND deleted_at IS NULL`，
 *      查不到抛 401008（403，**不是 404**，避免枚举别人的会话）。
 *   5. **不加幂等键**（工作流 C2 第 6 条）：连点两次算两次真实提问。
 *   6. **事务**：本方法跨「建会话」「写 user 消息」「Redis 写」「检索只读」多步，且中段有 Redis IO，
 *      按后端规范 §7.3「事务内只做 DB」**不套一个大事务**；两步写各自自动提交。
 *      极端情况下会话建好、user 消息写失败，会留下一条空会话（不影响正确性，G 组列表可软删）。
 *   7. **日志禁止出现 question 原文**（L4/L5）：只记 userId / conversationId / 字符数 / 命中条数 / 规则名。
 *   8. 与 C3 的交接：把 D1 的 messages + B1 的 hits + 模型 id + 配额日期存 Redis
 *      （`faze:ai:ask:<conversationId>`，TTL 10 分钟）。同一会话**连续两次提交且第一次流未开始**时，
 *      后一次会覆盖前一次的载荷——这是已知边界，前端应在收到 streamUrl 后立即订阅。
 */
package com.faze.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.ai.dto.ChatMessage;
import com.faze.modules.ai.config.AiProperties;
import com.faze.modules.ai.config.AiRedisKeys;
import com.faze.modules.ai.dto.GuardResult;
import com.faze.modules.ai.dto.LawHit;
import com.faze.modules.ai.dto.PendingAsk;
import com.faze.modules.ai.dto.PromptPayload;
import com.faze.modules.ai.dto.QuotaReservation;
import com.faze.modules.ai.dto.SubmitOutcome;
import com.faze.modules.ai.dto.SubmitQuestionRequest;
import com.faze.modules.ai.dto.SubmitQuestionResponse;
import com.faze.modules.ai.entity.AiConversation;
import com.faze.modules.ai.entity.AiMessage;
import com.faze.modules.ai.mapper.AiConversationMapper;
import com.faze.modules.ai.mapper.AiMessageMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 提交问题编排服务（C2）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiQuestionService {

    /** 会话语言：段 3 固定简体中文（§零.3 第 4 项），不从 Accept-Language 读。 */
    public static final String LANG_ZH_HANS = "zh-Hans";

    /** 消息角色：用户。 */
    public static final String ROLE_USER = "user";

    /** 消息角色：助手。 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 会话标题最大字数（工作流 C2 第 3 条第 ⑥ 项：前 30 字，超出加「…」）。 */
    private static final int TITLE_MAX_CHARS = 30;

    /** 历史消息查询条数（10 轮 ≈ 20 条；D1 自己再按「轮」截到 10 轮）。 */
    private static final int HISTORY_FETCH_LIMIT = 20;

    /** 会话入口路径（streamUrl 的固定前缀，接口规范 §6.7 已冻结）。 */
    private static final String STREAM_PATH = "/api/v1/app/ai/stream";

    /** 越界拦截（408004）给用户的固定引导文案（D2 第 2 条「用户看到什么」）。 */
    private static final String GUIDE_OUT_OF_SCOPE = "这类问题需要律师结合案情判断，为你推荐执业律师";

    /** 代写可递交文书拦截的引导文案（D2 第 2 条第三行）。 */
    private static final String GUIDE_DOCUMENT_DRAFT = "文书生成在文书中心，AI 只解答法律信息";

    /** 代写文书规则名（InputGuardRules 的 document_court_draft；按规则名给更贴切的文案）。 */
    private static final String RULE_DOCUMENT_DRAFT = "document_court_draft";

    /** 注入拦截（408005）的固定引导文案。 */
    private static final String GUIDE_PROMPT_INJECTED = "无法处理该请求";

    private final AiConversationMapper aiConversationMapper;
    private final AiMessageMapper aiMessageMapper;
    private final AiProperties aiProperties;
    private final AiQuotaService aiQuotaService;
    private final InputGuard inputGuard;
    private final LawSearchService lawSearchService;
    private final GlossaryService glossaryService;
    private final PromptAssembler promptAssembler;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    /**
     * 提交一个问题。
     *
     * <p>副作用：写 ai_conversation（新建时）、写 ai_message（user 一条；拦截时再写 assistant 引导一条）、
     * 写 Redis 待答载荷、可能 INCR/DECR 配额。</p>
     *
     * @param userId  当前用户 id（来自 JWT）
     * @param request 请求体（question 已通过 Bean Validation）
     * @return 编排结果（成功 / 降级 / 配额超限 / 输入拦截）
     */
    public SubmitOutcome submit(Long userId, SubmitQuestionRequest request) {
        String question = request.getQuestion().strip();
        int questionLength = question.codePointCount(0, question.length());

        // ① AI 总开关：关闭时降级返回（HTTP 200 + code 200，不报错；不建会话、不扣配额）
        if (!aiProperties.enabled()) {
            log.info("AI 已关闭，降级返回 userId={} questionLength={}", userId, questionLength);
            SubmitQuestionResponse degraded = new SubmitQuestionResponse();
            degraded.setDegraded(Boolean.TRUE);
            return SubmitOutcome.ok(degraded);
        }

        // ② 配额预扣（C5）：超限直接返回 408002 + Retry-After（脚本已原子回退，不需要再 refund）
        QuotaReservation reservation = aiQuotaService.reserve(userId);
        if (!reservation.allowed()) {
            return SubmitOutcome.quotaExceeded(reservation.retryAfterSeconds());
        }

        boolean quotaRefunded = false;
        try {
            // ③ 建/取会话（传 id 时做归属校验，不匹配 401008）
            AiConversation conversation = resolveConversation(userId, request.getConversationId(), question);

            // ④ 写 user 消息（原文落库，refs 为 NULL）
            AiMessage userMessage = insertUserMessage(conversation.getId(), userId, question);

            // ⑤ 输入拦截（D2）：命中 → 回退配额 + 写固定引导 assistant 文案 + 返回 408004/408005
            GuardResult guard = inputGuard.check(question);
            if (!guard.isPassed()) {
                aiQuotaService.refund(userId, reservation.day());
                quotaRefunded = true;
                insertGuideMessage(conversation.getId(), userId, guideText(guard));
                log.info("AI 输入拦截 conversationId={} userId={} rule={} code={}",
                        conversation.getId(), userId, guard.getMatchedRule(),
                        guard.getErrorCode() == null ? null : guard.getErrorCode().getCode());
                return SubmitOutcome.blocked(guard.getErrorCode());
            }

            // ⑥ 法条检索（B1）
            List<LawHit> hits = lawSearchService.search(question, aiProperties.searchTopN());

            // ⑦ 组装 Prompt（D1）：术语 + 最近 10 轮历史
            List<String> glossaryTerms = glossaryService.matchQuestion(question);
            List<AiMessage> history = loadHistory(conversation.getId(), userMessage.getId());
            PromptPayload payload = promptAssembler.assemble(question, hits, glossaryTerms, history);

            // 暂存给 C3 的载荷（TTL 10 分钟；C3 取出后即删）
            PendingAsk pending = buildPendingAsk(conversation.getId(), userId, payload, hits, reservation.day());
            String askJson = jsonMapper.writeValueAsString(pending);
            redisTemplate.opsForValue().set(AiRedisKeys.ask(conversation.getId()), askJson,
                    Duration.ofSeconds(AiRedisKeys.TTL_SECONDS));

            log.info("AI 提问已受理 conversationId={} userId={} questionLength={} hits={} terms={} history={}",
                    conversation.getId(), userId, questionLength, hits == null ? 0 : hits.size(),
                    glossaryTerms == null ? 0 : glossaryTerms.size(), history == null ? 0 : history.size());

            // ⑧ 返回（messageId 是本次请求追踪 id，不是 assistant 消息 id）
            SubmitQuestionResponse response = new SubmitQuestionResponse();
            response.setConversationId(conversation.getId());
            response.setMessageId(pending.getRequestId());
            response.setStreamUrl(STREAM_PATH + "?conversationId=" + conversation.getId() + "&fromSeq=0");
            response.setDegraded(Boolean.FALSE);
            return SubmitOutcome.ok(response);
        } catch (RuntimeException e) {
            // 任何未预期失败：本次没产生回答，配额退回（拦截分支已退过，不重复退）
            if (!quotaRefunded) {
                aiQuotaService.refund(userId, reservation.day());
            }
            log.warn("AI 提问失败已回退配额 userId={} error={}", userId, e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * 建或取会话（传 conversationId 时做归属校验）。
     *
     * @param userId         当前用户 id
     * @param conversationId 请求里的会话 id（可为 null）
     * @param question       本次问题（新建会话时用于生成标题）
     * @return 会话实体（新建时已回填 id）
     * @throws BusinessException 401008 会话不存在、已删除或不属于当前用户
     */
    private AiConversation resolveConversation(Long userId, Long conversationId, String question) {
        if (conversationId != null) {
            AiConversation existing = aiConversationMapper.selectOne(Wrappers.<AiConversation>lambdaQuery()
                    .eq(AiConversation::getId, conversationId)
                    .eq(AiConversation::getUserId, userId)
                    .isNull(AiConversation::getDeletedAt));
            if (existing == null) {
                // 统一 403：不区分「不存在」与「不是你的」，避免枚举别人的会话（工作流 C2 第 5 条）
                throw new BusinessException(ErrorCode.COMMON_FORBIDDEN,
                        "AI 会话归属校验失败 conversationId=" + conversationId);
            }
            return existing;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setTitle(buildTitle(question));
        conversation.setLang(LANG_ZH_HANS);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        aiConversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 写一条 user 消息（原文落库，一个字不改）。
     *
     * @param conversationId 会话 id
     * @param userId         用户 id
     * @param question       问题原文
     * @return 已回填 id 的消息实体
     */
    private AiMessage insertUserMessage(Long conversationId, Long userId, String question) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(ROLE_USER);
        message.setContent(question);
        message.setLang(LANG_ZH_HANS);
        message.setIsSafetyPassed(1);
        message.setIsExported(0);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        aiMessageMapper.insert(message);
        return message;
    }

    /**
     * 写一条 assistant 固定引导文案（输入拦截时；refs 为 NULL，表示没有做过法条引用）。
     *
     * @param conversationId 会话 id
     * @param userId         用户 id
     * @param content        固定引导文案
     */
    private void insertGuideMessage(Long conversationId, Long userId, String content) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(ROLE_ASSISTANT);
        message.setContent(content);
        message.setModel(null);
        message.setLang(LANG_ZH_HANS);
        message.setIsSafetyPassed(1);
        message.setIsExported(0);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        aiMessageMapper.insert(message);
    }

    /**
     * 按拦截结果选引导文案。
     *
     * @param guard 拦截结果
     * @return 固定文案
     */
    private String guideText(GuardResult guard) {
        if (guard.getErrorCode() == ErrorCode.AI_PROMPT_INJECTED) {
            return GUIDE_PROMPT_INJECTED;
        }
        if (RULE_DOCUMENT_DRAFT.equals(guard.getMatchedRule())) {
            return GUIDE_DOCUMENT_DRAFT;
        }
        return GUIDE_OUT_OF_SCOPE;
    }

    /**
     * 取本会话最近 20 条历史消息（user/assistant），按 id 升序返回。
     *
     * <p>不包含本次刚写入的 user 消息（用 id 严格小于它过滤）；D1 会再按「轮」截到 10 轮。</p>
     *
     * @param conversationId      会话 id
     * @param currentUserMessageId 本次 user 消息 id
     * @return 历史消息（升序；可能为空）
     */
    private List<AiMessage> loadHistory(Long conversationId, Long currentUserMessageId) {
        if (currentUserMessageId == null) {
            return List.of();
        }
        Page<AiMessage> page = new Page<>(1, HISTORY_FETCH_LIMIT);
        List<AiMessage> rows = aiMessageMapper.selectPage(page,
                        Wrappers.<AiMessage>lambdaQuery()
                                .eq(AiMessage::getConversationId, conversationId)
                                .lt(AiMessage::getId, currentUserMessageId)
                                .in(AiMessage::getRole, ROLE_USER, ROLE_ASSISTANT)
                                .orderByDesc(AiMessage::getId))
                .getRecords();
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<AiMessage> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);
        return ordered;
    }

    /**
     * 组 C2→C3 的 Redis 载荷。
     *
     * @param conversationId 会话 id
     * @param userId         用户 id
     * @param payload        D1 组装结果
     * @param hits           B1 命中
     * @param quotaDay       预扣日期
     * @return 待答载荷
     */
    private PendingAsk buildPendingAsk(Long conversationId, Long userId, PromptPayload payload,
                                       List<LawHit> hits, String quotaDay) {
        PendingAsk pending = new PendingAsk();
        pending.setRequestId(UUID.randomUUID().toString());
        pending.setConversationId(conversationId);
        pending.setUserId(userId);
        pending.setModel(StringUtils.hasText(payload.getModel()) ? payload.getModel() : aiProperties.model());
        pending.setMessages(toChatMessages(payload.messages()));
        pending.setHits(hits == null ? List.of() : hits);
        pending.setQuotaDay(quotaDay);
        return pending;
    }

    /**
     * D1 的消息 → 上游消息（本项目的 `ChatMessage`，便于 Redis 序列化/反序列化）。
     *
     * @param messages D1 的 messages（system → 历史 → user）
     * @return 上游格式消息
     */
    private List<ChatMessage> toChatMessages(List<PromptPayload.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> converted = new ArrayList<>(messages.size());
        for (PromptPayload.Message message : messages) {
            if (message == null) {
                continue;
            }
            converted.add(new ChatMessage(message.getRole(), message.getContent()));
        }
        return converted;
    }

    /**
     * 生成会话标题：前 30 字，超出加「…」（按码点算，避免切坏代理对）。
     *
     * @param question 问题原文
     * @return 标题
     */
    private String buildTitle(String question) {
        String text = question == null ? "" : question.strip();
        if (text.codePointCount(0, text.length()) <= TITLE_MAX_CHARS) {
            return text;
        }
        int end = text.offsetByCodePoints(0, TITLE_MAX_CHARS);
        return text.substring(0, end) + "…";
    }
}
