/*
 * 职责：PC A-10 提现审核（F2/F3）的接口封装。
 * 调用方：src/views/biz/trade/withdraw/index.vue。
 * 数据来源：faze-api `/api/v1/admin/withdrawals/**`。
 * 注意：
 *   1. **列表是 RuoYi 口径**（顶层 `rows` / `total`），其余是 `R<T>`（取 `res.data`）。
 *   2. 审核通过必须带 `voucherFileId`（服务端会拒），驳回必须带 `reason`。
 *   3. 通过（approve）**不出款**，只到「已通过」；点「已出款」才真的扣减余额。
 *      页面必须把这两步分开，不能合并成一次点击。
 *   4. 凭证预览返回 60 秒签名 URL，**不要缓存**（缓存等于把过期时间钉死）。
 */
import request from '@/api/biz/request'

/**
 * 提现列表（A-10 主表）。
 *
 * @param {{lawyerKw?:string,status?:string,beginDate?:string,endDate?:string,pageNum:number,pageSize:number}} query 筛选条件
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} RuoYi 分页响应
 */
export function listWithdrawals(query) {
  return request({
    url: '/api/v1/admin/withdrawals',
    method: 'get',
    params: query
  })
}

/**
 * 提现页四张统计卡。
 *
 * @returns {Promise<{code:number,data:{pendingCount:number,pendingAmountCents:number,todayAuditedCount:number,monthPaidCents:number}}>} 统计卡
 */
export function getWithdrawSummary() {
  return request({
    url: '/api/v1/admin/withdrawals/summary',
    method: 'get'
  })
}

/**
 * 审核（通过 / 驳回）。
 *
 * @param {string} id 提现单主键
 * @param {{action:'approve'|'reject',reason?:string,voucherFileId?:string,transactionId?:string}} payload 审核入参
 * @returns {Promise<{code:number}>} 空响应
 */
export function auditWithdrawal(id, payload) {
  return request({
    url: `/api/v1/admin/withdrawals/${id}/audit`,
    method: 'post',
    data: payload
  })
}

/**
 * 标记已出款（`approved → paid`，钱在这一步真正离开平台）。
 *
 * @param {string} id 提现单主键
 * @returns {Promise<{code:number}>} 空响应
 */
export function markWithdrawalPaid(id) {
  return request({
    url: `/api/v1/admin/withdrawals/${id}/paid`,
    method: 'post'
  })
}

/**
 * 补传出款凭证（已通过但漏传时用）。
 *
 * @param {string} id 提现单主键
 * @param {{fileName:string,contentType?:string,contentBase64:string}} payload 文件入参
 * @returns {Promise<{code:number,data:{voucherFileId:number}}>} 新凭证 id
 */
export function uploadWithdrawalVoucher(id, payload) {
  return request({
    url: `/api/v1/admin/withdrawals/${id}/voucher`,
    method: 'post',
    data: payload
  })
}

/**
 * 凭证预览：返回 60 秒签名 URL。
 *
 * @param {string} id 提现单主键
 * @returns {Promise<{code:number,data:{url:string}}>} 签名 URL
 */
export function getVoucherPreview(id) {
  return request({
    url: `/api/v1/admin/withdrawals/${id}/voucher`,
    method: 'get'
  })
}
