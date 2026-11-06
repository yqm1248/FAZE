/*
 * 职责：ai_conversation 的数据访问。
 * 调用方：modules/ai/service（C2 提交、G1 历史列表、G2 软删）、
 *         modules/ai/admin/service（A-28 会话检索）、modules/ai/controller。
 * 数据来源：faze.ai_conversation（自定义 SQL 见 resources/mapper/ai/AiConversationMapper.xml）。
 * 注意：
 *   1. 用户删除 = `UPDATE ... SET deleted_at = UTC_TIMESTAMP()`，**不物理删除**；C 端列表查询必须带
 *      `deleted_at IS NULL`，后台 A-28 查询**不带**这个条件（要能查已删会话）。
 *   2. 归属校验一律写进 SQL（`WHERE id=? AND user_id=?`），不许先查到再在 Java 里比对——
 *      后者会把「别人的会话」读进内存，属于越权读。
 *   3. G1 的列表 + 摘要聚合是**一条 SQL**（窗口函数 + `COUNT(*) OVER ()`），
 *      不许拆成「先查会话再逐条查消息」的 N+1；也不许再补一条 count。
 *   4. A-28 列表的 refCount 用 MySQL 8 `JSON_TABLE` 现算，**不建计数列**（与 B4 同一口径：
 *      计数列会有两个真相）。
 *   5. 本 Mapper 只放确实需要的自定义 SQL，其余走 MyBatis-Plus 的 BaseMapper。
 */
package com.faze.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.ai.entity.AiConversation;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AI 会话 Mapper。
 */
@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    // ── G1：APP 对话历史列表 ────────────────────────────────────────────────

    /**
     * 分页查当前用户的会话列表（含首条 assistant 摘要，一次 SQL）。
     *
     * <p>`ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY id)` 取每个会话首条 assistant，
     * 外层 `LEFT JOIN` 后由 `COUNT(*) OVER ()` 同一行带出总数——列表 + 总数 + 摘要 = 1 条 SQL。</p>
     *
     * @param userId 当前用户 id（来自 JWT）
     * @param limit  每页条数
     * @param offset 偏移量（从 0 开始）
     * @return 当前页行（每行带同一个 total）；无数据返回空列表
     */
    List<AiConversationListRow> selectHistoryPage(@Param("userId") Long userId,
                                                  @Param("limit") int limit,
                                                  @Param("offset") int offset);

    // ── G2：用户软删会话 ────────────────────────────────────────────────────

    /**
     * 软删一个属于当前用户的会话（幂等：已删过的行不会被更新，返回 0）。
     *
     * <p>只写 `deleted_at`，不动物理行、不删 `ai_message`（《AI对话留存策略》§三）。</p>
     *
     * @param id     会话主键
     * @param userId 当前用户 id（归属条件进 SQL，避免越权写）
     * @return 实际更新行数：1=本次删除成功；0=已删过或不存在/不属于该用户
     */
    int softDelete(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 统计「该 id 是否属于该用户」（含已软删行），用于区分「重复删除」与「无权删除」。
     *
     * <p>只在 {@link #softDelete} 返回 0 后调用一次；不返回行内容，避免读别人的会话数据。</p>
     *
     * @param id     会话主键
     * @param userId 当前用户 id
     * @return 命中行数（0 或 1）
     */
    long countOwned(@Param("id") Long id, @Param("userId") Long userId);

    // ── H2 / A-28：管理端会话检索 ───────────────────────────────────────────

    /**
     * 管理端分页检索会话（**含已软删**，带用户展示名、问题摘要、引用条数、总数）。
     *
     * <p>时间参数由 service 解析成 UTC 后传入（接口层收 ISO-8601 字符串，见 AdminAiConversationQueryBO），
     * SQL 里直接与 `created_at`（UTC）比较，不做时区换算。</p>
     *
     * @param userId    归属用户 id（可空）
     * @param beginTime 创建时间下界（含，UTC；可空）
     * @param endTime   创建时间上界（含，UTC；可空）
     * @param limit     每页条数
     * @param offset    偏移量（从 0 开始）
     * @return 当前页行（每行带同一个 total）；无数据返回空列表
     */
    List<AdminAiConversationRow> selectAdminPage(@Param("userId") Long userId,
                                                 @Param("beginTime") LocalDateTime beginTime,
                                                 @Param("endTime") LocalDateTime endTime,
                                                 @Param("limit") int limit,
                                                 @Param("offset") int offset);

    /**
     * 取用户展示名（昵称优先，昵称空回退手机号），供 A-28 详情面板显示「谁问的」。
     *
     * <p>只读 `app_user` 的两个展示字段，不返回手机号给调用方之外的用途；用户不存在时返回 null
     * （`ai_conversation.user_id` 是逻辑引用，历史数据可能指向已注销用户）。</p>
     *
     * @param userId 用户 id
     * @return 展示名；用户不存在或昵称/手机号都为 null 时返回 null
     */
    String selectUserName(@Param("userId") Long userId);
}
