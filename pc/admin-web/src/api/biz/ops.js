/*
 * 职责：法泽业务后台「运营与治理」（A-02/A-20~A-26）的全部接口封装。
 * 调用方：src/views/biz/ops/ 下的 6 个页面。
 * 数据来源：faze-api `/api/v1/admin/**`（第 7 段交付）。
 * 注意：
 *   1. **本段接口的列表返回 `R<PageResult>`**（`data.list / data.total / data.hasMore`），
 *      不是 RuoYi 的 TableDataInfo（`rows/total`）。读取时统一 `res.data.list`。
 *      这是第 7 段工作流 A4 的明确口径（与 §十一 的回写项对应）。
 *   2. 导出接口是文件流，**不能走 axios 的 JSON 拦截器**：用 `window.open` + token
 *      拼 query 的方式会泄露 token，所以这里返回 URL 由页面用 `fetch` + 下载 Blob 处理。
 *   3. 时间参数一律传 **UTC ISO**（`yyyy-MM-ddTHH:mm:ss`），页面展示时再 +8。
 */
import request from '@/api/biz/request'

// ── A-25 敏感词库 ──────────────────────────────────────────────────────────

/**
 * 敏感词列表。
 *
 * @param {object} params level/lang/category/kw/enabled/pageNum/pageSize
 * @returns {Promise} R<PageResult<SensitiveWordVO>>
 */
export function listSensitiveWords(params) {
  return request({ url: '/api/v1/admin/sensitive-words', method: 'get', params })
}

/**
 * 新增敏感词。
 *
 * @param {object} data word/category/level/lang/enabled
 * @returns {Promise} R<Long>
 */
export function createSensitiveWord(data) {
  return request({ url: '/api/v1/admin/sensitive-words', method: 'post', data })
}

/**
 * 修改敏感词（全量覆盖）。
 *
 * @param {number} id 词条 id
 * @param {object} data 同新增
 * @returns {Promise} R<Void>
 */
export function updateSensitiveWord(id, data) {
  return request({ url: `/api/v1/admin/sensitive-words/${id}`, method: 'put', data })
}

/**
 * 删除敏感词（物理删）。
 *
 * @param {number} id 词条 id
 * @returns {Promise} R<Void>
 */
export function deleteSensitiveWord(id) {
  return request({ url: `/api/v1/admin/sensitive-words/${id}`, method: 'delete' })
}

/**
 * 批量导入 CSV。
 *
 * @param {FormData} formData 含 file
 * @returns {Promise} R<ImportResultVO>
 */
export function importSensitiveWords(formData) {
  return request({
    url: '/api/v1/admin/sensitive-words/import',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 导出词库（返回可下载的 blob）。
 *
 * @param {object} params 筛选条件
 * @returns {Promise<Blob>}
 */
export function exportSensitiveWords(params) {
  return request({
    url: '/api/v1/admin/sensitive-words/export',
    method: 'get',
    params,
    responseType: 'blob'
  })
}

// ── A-25 右栏 申诉复核 ─────────────────────────────────────────────────────

/**
 * 申诉列表。
 *
 * @param {object} params status/pageNum/pageSize
 * @returns {Promise} R<PageResult<AppealVO>>
 */
export function listAppeals(params) {
  return request({ url: '/api/v1/admin/sensitive-word-appeals', method: 'get', params })
}

/**
 * 处置申诉。
 *
 * @param {number} id 申诉 id
 * @param {object} data action(accepted|rejected)/reason
 * @returns {Promise} R<Void>
 */
export function auditAppeal(id, data) {
  return request({ url: `/api/v1/admin/sensitive-word-appeals/${id}/audit`, method: 'post', data })
}

// ── A-20 内容审核队列 ──────────────────────────────────────────────────────

/**
 * 命中记录列表。
 *
 * @param {object} params status/type/level/bizUserId/beginTime/endTime/pageNum/pageSize
 * @returns {Promise} R<PageResult<ContentReviewVO>>
 */
export function listContentReviews(params) {
  return request({ url: '/api/v1/admin/content-reviews', method: 'get', params })
}

/**
 * 命中记录详情（含降级上下文说明）。
 *
 * @param {number} id 命中记录 id
 * @returns {Promise} R<ContentReviewDetailVO>
 */
export function getContentReview(id) {
  return request({ url: `/api/v1/admin/content-reviews/${id}`, method: 'get' })
}

/**
 * 处置命中记录。
 *
 * @param {number} id 命中记录 id
 * @param {object} data action(ignore|warn|ban)/reason
 * @returns {Promise} R<Void>
 */
export function auditContentReview(id, data) {
  return request({ url: `/api/v1/admin/content-reviews/${id}/audit`, method: 'post', data })
}

// ── A-21 / A-22 工单 ───────────────────────────────────────────────────────

/**
 * 工单列表。
 *
 * @param {object} params tab/keyword/category/agentId/pageNum/pageSize
 * @returns {Promise} R<PageResult<TicketVO>>
 */
export function listTickets(params) {
  return request({ url: '/api/v1/admin/tickets', method: 'get', params })
}

/**
 * 工单详情。
 *
 * @param {number} id 工单 id
 * @returns {Promise} R<TicketDetailVO>
 */
export function getTicket(id) {
  return request({ url: `/api/v1/admin/tickets/${id}`, method: 'get' })
}

/**
 * 回复工单。
 *
 * @param {number} id 工单 id
 * @param {object} data content
 * @returns {Promise} R<Void>
 */
export function replyTicket(id, data) {
  return request({ url: `/api/v1/admin/tickets/${id}/reply`, method: 'post', data })
}

/**
 * 关闭工单。
 *
 * @param {number} id 工单 id
 * @param {object} data reason
 * @returns {Promise} R<Void>
 */
export function closeTicket(id, data) {
  return request({ url: `/api/v1/admin/tickets/${id}/close`, method: 'post', data })
}

/**
 * 分配工单。
 *
 * @param {number} id 工单 id
 * @returns {Promise} R<Void>
 */
export function assignTicket(id) {
  return request({ url: `/api/v1/admin/tickets/${id}/assign`, method: 'post' })
}

/**
 * 批量分配。
 *
 * @param {number[]} ids 工单 id 列表
 * @returns {Promise} R<Integer>
 */
export function batchAssignTickets(ids) {
  return request({ url: '/api/v1/admin/tickets/assign-batch', method: 'post', data: ids })
}

/**
 * 导出工单（Blob）。
 *
 * @param {object} params 筛选条件
 * @returns {Promise<Blob>}
 */
export function exportTickets(params) {
  return request({ url: '/api/v1/admin/tickets/export', method: 'get', params, responseType: 'blob' })
}

// ── A-23 公告 ─────────────────────────────────────────────────────────────

/**
 * 公告列表。
 *
 * @param {object} params status/pageNum/pageSize
 * @returns {Promise} R<PageResult<AnnouncementVO>>
 */
export function listAnnouncements(params) {
  return request({ url: '/api/v1/admin/announcements', method: 'get', params })
}

/**
 * 新建公告。
 *
 * @param {object} data title/content/targetType/targetValue/channels/scheduledAt
 * @returns {Promise} R<Long>
 */
export function createAnnouncement(data) {
  return request({ url: '/api/v1/admin/announcements', method: 'post', data })
}

/**
 * 公告详情。
 *
 * @param {number} id 公告 id
 * @returns {Promise} R<AnnouncementDetailVO>
 */
export function getAnnouncement(id) {
  return request({ url: `/api/v1/admin/announcements/${id}`, method: 'get' })
}

/**
 * 预估触达人数。
 *
 * @param {object} params targetType/targetValue
 * @returns {Promise} R<Long>
 */
export function estimateReach(params) {
  return request({ url: '/api/v1/admin/announcements/estimate', method: 'get', params })
}

/**
 * 发送公告。
 *
 * @param {number} id 公告 id
 * @returns {Promise} R<Void>
 */
export function pushAnnouncement(id) {
  return request({ url: `/api/v1/admin/announcements/${id}/push`, method: 'post' })
}

// ── A-24 通知模板 ─────────────────────────────────────────────────────────

/**
 * 模板列表。
 *
 * @param {object} params channel/status/kw/pageNum/pageSize
 * @returns {Promise} R<PageResult<NotifyTemplateVO>>
 */
export function listTemplates(params) {
  return request({ url: '/api/v1/admin/notify-templates', method: 'get', params })
}

/**
 * 新建模板。
 *
 * @param {object} data templateCode/lang/title/content/channel
 * @returns {Promise} R<Long>
 */
export function createTemplate(data) {
  return request({ url: '/api/v1/admin/notify-templates', method: 'post', data })
}

/**
 * 修改模板。
 *
 * @param {number} id 模板 id
 * @param {object} data 同新建
 * @returns {Promise} R<Void>
 */
export function updateTemplate(id, data) {
  return request({ url: `/api/v1/admin/notify-templates/${id}`, method: 'put', data })
}

/**
 * 软停用模板。
 *
 * @param {number} id 模板 id
 * @returns {Promise} R<Void>
 */
export function disableTemplate(id) {
  return request({ url: `/api/v1/admin/notify-templates/${id}`, method: 'delete' })
}

/**
 * 测试渲染（返回预览文本）。
 *
 * @param {number} id 模板 id
 * @returns {Promise} R<String>
 */
export function testTemplate(id) {
  return request({ url: `/api/v1/admin/notify-templates/${id}/test`, method: 'post' })
}

// ── A-02 看板 ─────────────────────────────────────────────────────────────

/**
 * 核心指标。
 *
 * @param {string} range today|7d|30d
 * @returns {Promise} R<DashboardSummaryVO>
 */
export function getDashboardSummary(range) {
  return request({ url: '/api/v1/admin/dashboard/summary', method: 'get', params: { range } })
}

/**
 * 趋势（14 天）。
 *
 * @param {string} metric orders|revenue
 * @returns {Promise} R<DashboardTrendVO>
 */
export function getDashboardTrends(metric) {
  return request({ url: '/api/v1/admin/dashboard/trends', method: 'get', params: { metric } })
}

/**
 * 地区分布。
 *
 * @returns {Promise} R<DashboardRegionVO>
 */
export function getDashboardRegions() {
  return request({ url: '/api/v1/admin/dashboard/regions', method: 'get' })
}

/**
 * 待我处理。
 *
 * @returns {Promise} R<DashboardTodoVO[]>
 */
export function getDashboardTodos() {
  return request({ url: '/api/v1/admin/dashboard/todos', method: 'get' })
}

/**
 * 导出报表（CSV，UTF-8 with BOM）。
 *
 * 注意：
 *   1. 返回的是**文件流**（`responseType: 'blob'`），不走 R 拦截器的 JSON 分支。
 *   2. 第 7 段 F4 的导出统一规范：文件名 `{type}_{yyyyMMddHHmmss}.csv`，由页面拼装；
 *      后端只保证内容与表头。导出动作本身由服务端写 `LOG_EXPORT` 审计。
 *   3. 该接口由后端「A-02 导出」任务交付（本段新增接口，§五 5.2 第 5 号）。
 *
 * @param {object} params range today|7d|30d
 * @returns {Promise<Blob>} CSV 文件流
 */
export function exportDashboard(params) {
  return request({
    url: '/api/v1/admin/dashboard/export',
    method: 'get',
    params,
    responseType: 'blob'
  })
}

// ── A-26 日志中心 ─────────────────────────────────────────────────────────

/**
 * 操作日志列表。
 *
 * @param {object} params operatorId/module/action/targetType/targetId/ip/beginTime/endTime/pageNum/pageSize
 * @returns {Promise} R<PageResult<OperationLogVO>>
 */
export function listOperationLogs(params) {
  return request({ url: '/api/v1/admin/operation-logs', method: 'get', params })
}

/**
 * 登录日志列表。
 *
 * @param {object} params account/ip/result/beginTime/endTime/pageNum/pageSize
 * @returns {Promise} R<PageResult<LoginLogVO>>
 */
export function listLoginLogs(params) {
  return request({ url: '/api/v1/admin/login-logs', method: 'get', params })
}

/**
 * 导出操作日志（Blob）。
 *
 * @param {object} params 筛选条件
 * @returns {Promise<Blob>}
 */
export function exportOperationLogs(params) {
  return request({ url: '/api/v1/admin/operation-logs/export', method: 'get', params, responseType: 'blob' })
}

/**
 * 导出登录日志（Blob）。
 *
 * @param {object} params 筛选条件
 * @returns {Promise<Blob>}
 */
export function exportLoginLogs(params) {
  return request({ url: '/api/v1/admin/login-logs/export', method: 'get', params, responseType: 'blob' })
}
