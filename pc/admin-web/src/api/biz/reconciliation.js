/*
 * 职责：PC A-09 对账报表的接口封装（第 1 段 F3，契约见《F2+F3 接口冻结契约》§四 A-09）。
 * 调用方：src/views/biz/reconciliation/index.vue。
 * 数据来源：faze-api `/api/v1/admin/reconciliation/**`（只读 + 一次纯计算巡检，不建 reconciliation_diff 表）。
 * 注意：
 *   1. 三个接口都返回 `R<T>`，业务字段在 `res.data`。
 *   2. **口径**：按 (owner_type, owner_id) 汇总 balance_flow.delta_cents，
 *      与 user_balance.balance_cents + frozen_cents 比对（与 deploy/verify-balance.ps1 同一恒等式）。
 *   3. 差异列表是**现算的内存态**，不是落库快照：两次调用之间数据可能变，
 *      页面每次刷新都要把 summary 与 diffs 一起重取，不能只刷一个。
 *   4. summary 里没有「差异总额」字段（只有 totalCheckedCents = 被检查余额合计），
 *      页面上的「差异总额」是 `/diffs` 求和算出来的，见 index.vue 的注释与交付说明。
 */
import request from '@/api/biz/request'

/**
 * 读取恒等式巡检摘要。
 *
 * @returns {Promise<{code:number,msg:string,data:{checkedOwners:number,balancedOwners:number,diffOwners:number,totalCheckedCents:number,checkedAt:string}}>} 摘要
 */
export function getReconciliationSummary() {
  return request({
    url: '/api/v1/admin/reconciliation/summary',
    method: 'get'
  })
}

/**
 * 读取差异列表（现算，内存态）。
 *
 * @returns {Promise<{code:number,msg:string,data:Array<{ownerType:string,ownerId:string,cacheCents:number,flowCents:number,diffCents:number}>}>}> 差异列表
 */
export function getReconciliationDiffs() {
  return request({
    url: '/api/v1/admin/reconciliation/diffs',
    method: 'get'
  })
}

/**
 * 手动触发一次巡检（纯计算，不加锁；服务端写 operation_log，action=RECONCILE_RUN）。
 *
 * @returns {Promise<{code:number,msg:string,data:{checkedOwners:number,balancedOwners:number,diffOwners:number,totalCheckedCents:number,checkedAt:string}}>} 本次巡检摘要
 */
export function runReconciliation() {
  return request({
    url: '/api/v1/admin/reconciliation/run',
    method: 'post'
  })
}
