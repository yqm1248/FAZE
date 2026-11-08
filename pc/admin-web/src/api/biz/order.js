/*
 * 职责：PC A-11 订单列表 / A-12 订单详情 / 状态流转的接口封装（第 2 段 G9.1 / G9.2）。
 * 调用方：src/views/biz/trade/order/index.vue、detail.vue。
 * 数据来源：faze-api `/api/v1/admin/orders/**`（经 /biz-api 代理到 127.0.0.1:8081）。
 * 注意：
 *   1. **列表是 RuoYi 口径**（顶层 rows / total），所以 `listOrders` 直接返回 `res.data`；
 *      详情与流水是 `R<T>`，返回 `res.data`（`R.data` 里的业务对象）。
 *   2. 时间参数按 ISO-8601 本地时间传（`YYYY-MM-DDTHH:mm:ss`），服务端按 UTC 比较——
 *      与段 1 的审核页同一口径（format.js 的注释说明了时区约定）。
 *   3. 金额是整数分，页面用 `formatCents` 展示。
 *   4. ID 一律字符串透传，不做 Number()（BIGINT 超出 JS 安全整数）。
 */
import request from '@/api/biz/request'

/**
 * 分页查订单列表（A-11）。
 *
 * @param {{status?:string,startDate?:string,endDate?:string,lawyerId?:string,userId?:string,pageNum:number,pageSize:number}} query 筛选条件
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} RuoYi 分页响应
 */
export function listOrders(query) {
  // 注意：列表接口是 RuoYi 口径（顶层 rows/total，不包 R.data），
  // request 拦截器已把 body 原样返回，所以**不能再取 res.data**——取 `.data` 会得到 undefined。
  return request({
    url: '/api/v1/admin/orders',
    method: 'get',
    params: query
  })
}

/**
 * 查订单详情（A-12）。
 *
 * @param {string} orderId 订单主键
 * @returns {Promise<{code:number,data:object}>} 详情
 */
export function getOrderDetail(orderId) {
  return request({
    url: `/api/v1/admin/orders/${orderId}`,
    method: 'get'
  })
}

/**
 * 查订单状态流转（A-12 时间线）。
 *
 * @param {string} orderId 订单主键
 * @returns {Promise<{code:number,data:Array<object>}>} 流水（时间正序）
 */
export function getOrderStatusLog(orderId) {
  return request({
    url: `/api/v1/admin/orders/${orderId}/status-log`,
    method: 'get'
  })
}

/**
 * 查订单的退款单（A-12 审批入口；复用 G4.2 的接口）。
 *
 * @param {string} orderId 订单主键
 * @returns {Promise<{code:number,data:Array<object>}>} 退款单列表
 */
export function listOrderRefunds(orderId) {
  return request({
    url: '/api/v1/admin/refunds',
    method: 'get',
    params: { orderId }
  })
}

/**
 * 退款审批（A-12 弹窗；G4.2 接口）。
 *
 * @param {string} refundId 退款单主键
 * @param {{approved:boolean,remark?:string}} data 审批内容
 * @returns {Promise<{code:number,data:object}>} 审批结果
 */
export function auditRefund(refundId, data) {
  return request({
    url: `/api/v1/admin/refunds/${refundId}/audit`,
    method: 'post',
    data
  })
}

/**
 * 查看聊天记录（A-12 抽屉；每次调用服务端写 operation_log）。
 *
 * @param {string} conversationId 会话主键
 * @param {{cursor?:string,limit?:number}} params 游标与条数
 * @returns {Promise<{code:number,data:{list:Array<object>,nextCursor:string|null,hasMore:boolean}}>} 聊天记录
 */
export function listChatMessages(conversationId, params) {
  return request({
    url: `/api/v1/admin/conversations/${conversationId}/messages`,
    method: 'get',
    params
  })
}
