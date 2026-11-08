/*
 * 职责：PC A-19 案件管理（列表 / 抽屉三 Tab / 立案审核 / 进度纠错 / 发起送达）的接口封装。
 * 调用方：src/views/biz/caseflow/case/index.vue、detail-drawer.vue。
 * 数据来源：faze-api `/api/v1/admin/cases/**`（经 /biz-api 代理到 127.0.0.1:8081）。
 * 注意：
 *   1. **列表是 RuoYi 口径**（顶层 rows / total），其余是 `R<T>`（取 `res.data`）。
 *      request 拦截器把响应体原样返回，所以列表接口直接返回 body，**不能再取 `.data`**。
 *   2. 审核 / 纠错 / 发起送达都是写接口，服务端在 Service 上标了 `@OperationLog`
 *      （动作码 CASE_AUDIT / CASE_PROGRESS_CORRECT / DELIVERY_SEND），前端不需要再记审计。
 *   3. ID 一律字符串透传，不做 `Number()`（BIGINT 超出 JS 安全整数）。
 *   4. 时间字段是 UTC 无时区后缀，展示统一走 `shared/format.js` 的 formatDateTime。
 */
import request from '@/api/biz/request'

/**
 * 分页查案件列表（A-19 列表）。
 *
 * @param {{status?:string,type?:string,lawyerId?:string,kw?:string,pageNum:number,pageSize:number}} query 筛选条件
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} RuoYi 分页响应
 */
export function listCases(query) {
  return request({
    url: '/api/v1/admin/cases',
    method: 'get',
    params: query
  })
}

/**
 * 案件详情（抽屉主数据）。
 *
 * @param {string} caseId 案件主键
 * @returns {Promise<{code:number,data:object}>} 详情（含 formSnapshot / evidenceImageUrls）
 */
export function getCaseDetail(caseId) {
  return request({
    url: `/api/v1/admin/cases/${caseId}`,
    method: 'get'
  })
}

/**
 * 案件进度时间轴（抽屉「进度」Tab）。
 *
 * @param {string} caseId 案件主键
 * @returns {Promise<{code:number,data:Array<object>}>} 节点列表（按 step 升序）
 */
export function listCaseProgress(caseId) {
  return request({
    url: `/api/v1/admin/cases/${caseId}/progress`,
    method: 'get'
  })
}

/**
 * 立案审核（通过 → accepted / 驳回 → rejected）。
 *
 * @param {string} caseId 案件主键
 * @param {{action:'approve'|'reject',reason?:string}} data 审核内容（驳回时 reason 必填 ≤500 字）
 * @returns {Promise<{code:number,msg:string}>} 空响应
 */
export function auditCase(caseId, data) {
  return request({
    url: `/api/v1/admin/cases/${caseId}/audit`,
    method: 'post',
    data
  })
}

/**
 * 进度纠错（写 case_progress，必须填原因）。
 *
 * @param {string} caseId 案件主键
 * @param {{nodeCode:string,title?:string,description:string,courtCaseNo?:string}} data 纠错内容
 * @returns {Promise<{code:number,data:object}>} 新写入的节点
 */
export function correctCaseProgress(caseId, data) {
  return request({
    url: `/api/v1/admin/cases/${caseId}/progress`,
    method: 'post',
    data
  })
}

/**
 * 本案送达记录（抽屉「送达」Tab）。
 *
 * @param {string} caseId 案件主键
 * @returns {Promise<{code:number,data:Array<object>}>} 送达记录（按发起时间倒序）
 */
export function listCaseDeliveries(caseId) {
  return request({
    url: `/api/v1/admin/cases/${caseId}/deliveries`,
    method: 'get'
  })
}

/**
 * 本案可送达文书（发起送达弹窗选文书）。
 *
 * @param {string} caseId 案件主键
 * @returns {Promise<{code:number,data:Array<object>}>} 文书摘要（含 hasPdf）
 */
export function listCaseDocuments(caseId) {
  return request({
    url: `/api/v1/admin/cases/${caseId}/documents`,
    method: 'get'
  })
}

/**
 * 发起送达（生成 SD 回执号 + pending 记录）。
 *
 * @param {string} caseId 案件主键
 * @param {{documentId:string,method:'platform'|'sms_pickup'|'mail'}} data 文书与方式
 * @returns {Promise<{code:number,data:object}>} 新建的送达记录
 */
export function sendCaseDelivery(caseId, data) {
  return request({
    url: `/api/v1/admin/cases/${caseId}/deliveries`,
    method: 'post',
    data
  })
}
