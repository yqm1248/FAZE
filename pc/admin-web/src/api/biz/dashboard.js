/*
 * 职责：法泽业务后台「总览页自检」接口（第 1 段 F1）。
 * 调用方：src/views/biz/dashboard/index.vue。
 * 数据来源：faze-api `GET /api/v1/admin/dashboard/ping`。
 * 注意：
 *   1. 返回的是 R<T>：`{code, msg, data, traceId}`，业务字段在 `data` 里。
 *      列表接口才是 RuoYi 的 TableDataInfo（顶层 rows/total），两者不要混。
 *   2. 本接口存在的唯一目的是**证明管理端鉴权桥通了**：它走到 AdminAuthenticationFilter，
 *      能返回 adminId 就说明 RuoYi 的 token 被 faze-api 认了。
 */
import request from '@/api/biz/request'

/**
 * 管理端自检。
 *
 * @returns {Promise<{code:number,msg:string,data:object}>} 服务端时间与当前管理员
 */
export function getDashboardPing() {
  return request({
    url: '/api/v1/admin/dashboard/ping',
    method: 'get'
  })
}
