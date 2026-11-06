/*
 * 职责：PC A-28「AI 对话检索」的 HTTP 入口（H2，3 个接口：会话列表 / 会话详情 / 单条导出）。
 * 调用方：`pc/admin-web/src/views/biz/ai/ai-retrieval/index.vue`（经 vite `/biz-api` 代理打到 faze-api 8081）。
 * 数据来源：AiConversationAdminService（只读 + 导出回写 + 审计）。
 * 注意：
 *   1. **只有 3 个接口**：列表、详情（全量消息 + refs 快照）、导出。
 *      `POST /{id}/review`（标记已复核）与列表级「导出检索结果」已由 H2 裁定不做，
 *      「复核状态 / 校验状态」相关查询参数与字段也不提供（§零.3 第 9 项：不要复核）。
 *   2. **列表与详情是纯 GET、不写 operation_log**；只有导出写审计（action=`AI_CONVERSATION_EXPORT`），
 *      由 Service 上的 `@OperationLog` 完成——Controller 不标。
 *   3. 列表返回 RuoYi 口径（`TableDataInfo`：顶层 rows / total），详情返回 `R<T>`，导出返回文件流
 *      （`Content-Disposition: attachment`）。三种响应形态都是接口规范 §5.2 允许的分场景写法。
 *   4. 导出额外回 `X-Export-Truncated` / `X-Export-Messages` 两个响应头：单条会话超过 200 条消息时
 *      文件被截断，前端可在下载后提示「已截断」；JSON 文件内也有 `truncated` 字段。
 *   5. 本类不注入 Mapper、不写业务、不开事务（《后端代码规范》§2.1）；
 *      也**不写 `@PreAuthorize`**（faze-api 没有 RuoYi 的 `@ss` bean，管理端只靠 ROLE_ADMIN）。
 */
package com.faze.modules.ai.admin.controller;

import com.faze.common.R;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.ai.admin.bo.AdminAiConversationQueryBO;
import com.faze.modules.ai.admin.service.AiConversationAdminService;
import com.faze.modules.ai.admin.vo.AdminAiConversationDetailVO;
import com.faze.modules.ai.admin.vo.AdminAiConversationRowVO;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC AI 对话检索接口（A-28）。
 */
@RestController
@RequestMapping("/api/v1/admin/ai-conversations")
@RequiredArgsConstructor
@Validated
public class AdminAiConversationController {

    private final AiConversationAdminService aiConversationAdminService;

    /**
     * 会话列表（分页，**含已软删**，带 deletedAt / userName / questionSummary / refCount）。
     *
     * @param query 查询条件（userId / beginTime / endTime / pageNum / pageSize）
     * @return RuoYi 分页响应
     */
    @GetMapping
    public TableDataInfo<AdminAiConversationRowVO> list(AdminAiConversationQueryBO query) {
        return aiConversationAdminService.list(query);
    }

    /**
     * 会话详情：全量消息（每条带 refs 快照）+ refs 对应条文的当前状态。
     *
     * @param id 会话主键
     * @return `{conversation, messages, currentLawItems}`
     */
    @GetMapping("/{id}/messages")
    public R<AdminAiConversationDetailVO> messages(@PathVariable @Positive Long id) {
        return R.ok(aiConversationAdminService.detail(id));
    }

    /**
     * 导出单条会话为 JSON 文件（脱敏；写导出标记与审计）。
     *
     * @param id 会话主键
     * @return JSON 文件流（`Content-Disposition: attachment; filename="AI-CONV-&lt;id&gt;-&lt;yyyyMMddHHmm&gt;.json"`）
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable @Positive Long id) {
        AiConversationAdminService.ExportPayload payload = aiConversationAdminService.export(id);
        MediaType jsonUtf8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(jsonUtf8)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + payload.filename() + "\"")
                .header("X-Export-Truncated", String.valueOf(payload.truncated()))
                .header("X-Export-Messages", String.valueOf(payload.exportedMessages()))
                .body(payload.content());
    }
}
