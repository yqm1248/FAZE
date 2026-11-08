/*
 * 职责：PC A-13 律师结算（E5）与收入回补（E2）的接口封装。
 * 调用方：src/views/biz/trade/settle/index.vue。
 * 数据来源：faze-api `/api/v1/admin/lawyer-settlements/**`、`/api/v1/admin/settlements/backfill`。
 * 注意：
 *   1. **列表是 RuoYi 口径**（顶层 `rows` / `total`），所以 `listSettlements` 直接返回整个响应体；
 *      其余接口是 `R<T>`，要取 `res.data`。
 *   2. 金额一律整数分，页面用 `formatCents` 展示。
 *   3. ID 一律字符串透传，不做 Number()（BIGINT 超出 JS 安全整数）。
 *   4. **确认结算不动钱**：`confirmSettlement` 只改批次状态，页面二次确认文案必须写清。
 */
import request from '@/api/biz/request'

/**
 * 结算批次列表（A-13 主表）。
 *
 * @param {{lawyerKw?:string,period?:string,status?:string,pageNum:number,pageSize:number}} query 筛选条件
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} RuoYi 分页响应
 */
export function listSettlements(query) {
  return request({
    url: '/api/v1/admin/lawyer-settlements',
    method: 'get',
    params: query
  })
}

/**
 * 结算页三张统计卡。
 *
 * @returns {Promise<{code:number,data:{pendingTotalCents:number,settledThisMonthCents:number,lawyerCount:number}}>} 统计卡
 */
export function getSettlementSummary() {
  return request({
    url: '/api/v1/admin/lawyer-settlements/summary',
    method: 'get'
  })
}

/**
 * 生成结算批次（可批量）。
 *
 * @param {{period:string,lawyerId?:string}} payload 账期 + 可选律师
 * @returns {Promise<{code:number,data:{period:string,generated:number,skipped:number,candidates:number}}>} 生成结果
 */
export function generateSettlements(payload) {
  return request({
    url: '/api/v1/admin/lawyer-settlements/generate',
    method: 'post',
    data: payload
  })
}

/**
 * 结算单详情（批次头 + 逐单明细）。
 *
 * @param {string} id 结算批次主键
 * @returns {Promise<{code:number,data:{settlement:object,items:Array<object>,itemsTotalCents:number,consistent:boolean}}>} 详情
 */
export function getSettlementDetail(id) {
  return request({
    url: `/api/v1/admin/lawyer-settlements/${id}`,
    method: 'get'
  })
}

/**
 * 确认结算（**不动任何资金**）。
 *
 * @param {string} id 结算批次主键
 * @returns {Promise<{code:number}>} 空响应
 */
export function confirmSettlement(id) {
  return request({
    url: `/api/v1/admin/lawyer-settlements/${id}/confirm`,
    method: 'post'
  })
}

/**
 * 收入回补的只读核对（先看数，人工确认后再回补）。
 *
 * @returns {Promise<{code:number,data:{scanned:number,pendingCents:number}}>} 待补数量与净收入合计
 */
export function getBackfillPending() {
  return request({
    url: '/api/v1/admin/settlements/backfill/pending',
    method: 'get'
  })
}

/**
 * 执行收入回补。
 *
 * @param {number} [limit] 单次最多回补笔数
 * @returns {Promise<{code:number,data:{scanned:number,credited:number,skipped:number,creditedCents:number}}>} 回补结果
 */
export function runBackfill(limit) {
  return request({
    url: '/api/v1/admin/settlements/backfill',
    method: 'post',
    params: limit ? { limit } : {}
  })
}
