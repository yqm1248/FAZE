/*
 * 职责：PC A-28「AI 对话检索」的接口封装（第 3 段 H2）。
 * 调用方：src/views/biz/ai/ai-retrieval/index.vue。
 * 数据来源：faze-api（/biz-api 前缀经 vite 代理到 127.0.0.1:8081）
 *   GET /api/v1/admin/ai-conversations                 会话列表（含已软删，RuoYi 口径 rows/total）
 *   GET /api/v1/admin/ai-conversations/{id}/messages   全量消息 + refs 快照 + 当前法条状态
 *   GET /api/v1/admin/ai-conversations/{id}/export     导出单条会话 JSON（文件流，写审计）
 * 注意：
 *   1. **只读**：本文件没有任何写原文的接口。复核接口（POST /{id}/review）按 §零.3 第 9 项不做。
 *   2. 列表行字段（后端定稿）：`id/userId/userName/title/lang/createdAt/updatedAt/deletedAt/
 *      questionSummary/refCount`；`userName` 后端已 join app_user 算好，页面不再查用户。
 *   3. messages 的 data 是 `{conversation, messages, currentLawItems}`：
 *      `currentLawItems[]` 是「快照 vs 当前」的数据源（`lawItemId/status/isEnabled/effectiveDate/
 *      expireDate/updatedAt`），页面**不要逐条回查 /law-items/{id}**（那是 N+1）。
 *   4. refs 是**解析后的数组**（不是 JSON 字符串），元素：
 *      `lawItemId/lawCode/lawName/articleNo/content/effectiveDate/status`（D4 冻结 schema）。
 *   5. 导出是文件流：`responseType: 'blob'`。业务 axios 拦截器对非 R 结构原样返回 Blob；
 *      但**响应头（Content-Disposition / X-Export-Truncated）拿不到**（拦截器只回 body），
 *      所以下载文件名按 E3 规范在前端按同一格式拼（AI-CONV-<id>-<yyyyMMddHHmm>.json），
 *      截断标记从 JSON 内容的 `truncated` 字段读。
 */
import request from '@/api/biz/request'

/** 接口根路径（request.js 会再拼 /biz-api 前缀）。 */
const BASE = '/api/v1/admin/ai-conversations'

/**
 * 分页查询 AI 会话（含用户已软删的会话）。
 *
 * @param {{userId?:string,beginTime?:string,endTime?:string,pageNum:number,pageSize:number}} query
 *        筛选条件；时间按 `YYYY-MM-DDTHH:mm:ss`（UTC 墙钟，与用户管理页一致）
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} RuoYi 口径分页响应
 */
export function listAiConversations(query) {
  return request({
    url: BASE,
    method: 'get',
    params: query
  })
}

/**
 * 查会话的全量消息、refs 快照与当前法条状态。
 *
 * @param {string|number} conversationId 会话主键
 * @returns {Promise<{code:number,data:{conversation:object,messages:Array<object>,currentLawItems:Array<object>}}>}
 *          消息按时间正序；refs 已解析为数组
 */
export function getAiConversationMessages(conversationId) {
  return request({
    url: `${BASE}/${conversationId}/messages`,
    method: 'get'
  })
}

/**
 * 导出单条会话为 JSON 文件（接口会写 operation_log: AI_CONVERSATION_EXPORT）。
 *
 * @param {string|number} conversationId 会话主键
 * @returns {Promise<Blob>} JSON 文件流（单会话超过 200 条消息会被后端截断，JSON 内含 truncated 标记）
 */
export function exportAiConversation(conversationId) {
  return request({
    url: `${BASE}/${conversationId}/export`,
    method: 'get',
    responseType: 'blob',
    // 导出要拼全量消息，可能比普通查询慢
    timeout: 60000
  })
}
