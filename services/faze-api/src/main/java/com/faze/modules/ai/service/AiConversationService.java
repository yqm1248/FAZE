/*
 * 职责：G 组「APP 对话历史」的两个业务动作——列表（G1）与用户软删会话（G2）。
 * 调用方：modules/ai/controller/AiConversationController（`GET/DELETE /api/v1/app/ai/conversations`）。
 * 数据来源：ai_conversation（读写，经 AiConversationMapper 的自定义 SQL）、
 *           ai_message（只读，由列表 SQL 在库内聚合首条 assistant 摘要）。
 * 注意：
 *   1. **列表是 1 条 SQL**：会话行 + 首条 assistant 摘要 + 总数由窗口函数一次返回，
 *      不许「先查会话、再按会话逐条查摘要」（N+1），也不许再补一条 count。
 *   2. **删除是软删**：只写 `deleted_at = UTC_TIMESTAMP()`，不物理删行、不删消息——
 *      AI 对话是合规留痕证据，后台 A-28 仍要能查到（《AI对话留存策略》§三）。
 *   3. **归属不符回 401008（HTTP 403）**：不返回 404，避免被用来枚举别人的会话 id
 *      （接口规范 §6.2 与管理端同码）。重复删除**幂等成功**（不报 404）。
 *   4. **删除不写 `operation_log`**：用户删自己的会话不是管理端审计动作
 *      （工作流 G2 第 6 条 / 审计规范 §3.2 判据「改别人能看到的内容」）。
 *   5. 日志禁止出现会话标题、消息原文（L4 敏感）：只记 userId / conversationId / 条数。
 */
package com.faze.modules.ai.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.modules.ai.mapper.AiConversationListRow;
import com.faze.modules.ai.mapper.AiConversationMapper;
import com.faze.modules.ai.vo.AiConversationRowVO;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * APP 对话历史服务（G1 列表 / G2 软删）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiConversationService {

    /** 历史列表默认每页条数（设计稿 18 一屏约 8 条，20 足够一次下拉）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 历史列表每页上限（C 端 list 分页上限按接口规范 §5.2 取 50）。 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 摘要最大字数（与 SQL 里的 `LEFT(content, 30)` 对齐，Java 侧再压平空白）。 */
    private static final int SUMMARY_MAX_CHARS = 30;

    private final AiConversationMapper aiConversationMapper;

    /**
     * 分页查当前用户的对话历史（G1）。
     *
     * <p>副作用：只读；不写库、不写审计。</p>
     *
     * @param userId   当前用户 id（来自 JWT，不能为 null）
     * @param pageNum  页码（可空；&lt;1 按 1）
     * @param pageSize 每页条数（可空；clamp 到 1..50）
     * @return 分页结果（list 元素为 `{id,title,lastMessageAt,summary}`）
     * @throws BusinessException 402003 未认证（userId 为空）
     */
    public PageResult<AiConversationRowVO> list(Long userId, Integer pageNum, Integer pageSize) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long offsetLong = (long) (page - 1) * size;
        // 深页码时 offset 可能超过 int：该用户会话量远达不到这个量级，夹到 int 上限即可（SQL 里不会越界）
        int offset = (int) Math.min(offsetLong, Integer.MAX_VALUE);

        List<AiConversationListRow> rows = aiConversationMapper.selectHistoryPage(userId, size, offset);
        long total = rows == null || rows.isEmpty() || rows.get(0).getTotal() == null
                ? 0L : rows.get(0).getTotal();
        List<AiConversationRowVO> list = rows == null ? List.of()
                : rows.stream().filter(Objects::nonNull).map(this::toVO).toList();
        log.debug("AI 对话历史 userId={} page={} size={} rows={} total={}",
                userId, page, size, list.size(), total);
        return PageResult.of(list, total, page, size);
    }

    /**
     * 软删一个属于当前用户的会话（G2）。
     *
     * <p>副作用：`UPDATE ai_conversation SET deleted_at = UTC_TIMESTAMP()`；
     * 不写 operation_log（用户行为，见类注释第 4 条）。</p>
     *
     * @param userId 当前用户 id（来自 JWT）
     * @param id     会话主键
     * @throws BusinessException 402003 未认证 / 401002 id 缺失 / 401008 会话不属于当前用户
     */
    public void delete(Long userId, Long id) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        if (id == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "id: 不能为空");
        }
        // 任务 1：带上归属条件的软删。命中 1 行 = 本次删除成功；
        // 0 行 = 已经删过（幂等）或不是本人的（401008），由下一步区分。
        int updated = aiConversationMapper.softDelete(id, userId);
        if (updated > 0) {
            log.info("AI 会话软删成功 userId={} conversationId={}", userId, id);
            return;
        }
        // 任务 2：区分「重复删」与「越权」。只查行数不读内容。
        if (aiConversationMapper.countOwned(id, userId) > 0) {
            log.info("AI 会话重复删除，幂等返回成功 userId={} conversationId={}", userId, id);
            return;
        }
        throw new BusinessException(ErrorCode.COMMON_FORBIDDEN, "AI 会话归属校验失败 conversationId=" + id);
    }

    /**
     * 列表行 → 出参（压平摘要空白）。
     *
     * @param row 查询行
     * @return 出参
     */
    private AiConversationRowVO toVO(AiConversationListRow row) {
        AiConversationRowVO vo = new AiConversationRowVO();
        vo.setId(row.getId());
        vo.setTitle(row.getTitle());
        vo.setLastMessageAt(row.getLastMessageAt());
        vo.setSummary(flattenSummary(row.getSummary()));
        return vo;
    }

    /**
     * 摘要压平空白并保证不超 30 字。
     *
     * <p>SQL 已 `LEFT(content,30)`，这里再压平换行/连续空格：设计稿摘要是一行，
     * 直接把带换行的原文塞进列表会出现半行空白。</p>
     *
     * @param summary SQL 截断后的摘要（可空）
     * @return 单行摘要；空值原样返回 null
     */
    private String flattenSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return null;
        }
        String flat = summary.replaceAll("\\s+", " ").trim();
        if (flat.length() <= SUMMARY_MAX_CHARS) {
            return flat;
        }
        return flat.substring(0, SUMMARY_MAX_CHARS) + "…";
    }
}
