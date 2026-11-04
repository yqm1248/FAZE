/*
 * 职责：PC A-05 律师入驻审核的接口封装（第 1 段 F2，契约见《F2+F3 接口冻结契约》§三 A-05）。
 * 调用方：src/views/biz/lawyerApplication/index.vue。
 * 数据来源：faze-api `/api/v1/admin/lawyer-applications**`（lawyer_application + lawyer_application_log）。
 * 注意：
 *   1. **`status` 传空时服务端只查 `pending`**（draft / pending 都不进待审队列），
 *      所以本页不提供「全部状态」选项——传空不等于全部，这是契约本身的限制，不是页面偷懒。
 *      要查历史必须显式传 APPROVED / REJECTED。
 *   2. 契约里 `applyType` 查询参数本段无对应字段，**不要传**（传了后端也忽略，只会让联调时误判）。
 *   3. `practiceYears`：lawyer_application 表本段没有该字段，服务端恒返 null，
 *      页面显示 `—` 并注明「段 2 补」，不能拿 licenseNumber 之类去猜。
 *   4. licenseImageUrl 是 60 秒签名 URL，只在打开详情抽屉时取。
 *   5. 列表顶层 rows / total；详情与审核是 R<T>（`res.data`）。
 */
import request from '@/api/biz/request'

/**
 * 分页查询律师入驻申请队列。
 *
 * @param {object} params 查询条件
 * @param {number} params.pageNum 页码（从 1 开始）
 * @param {number} params.pageSize 每页条数
 * @param {string} [params.status] 申请状态：PENDING|APPROVED|REJECTED（**空 = 只看 PENDING**）
 * @param {string} [params.beginTime] 提交时间起（ISO date-time）
 * @param {string} [params.endTime] 提交时间止（ISO date-time）
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} 队列数据
 */
export function listLawyerApplications(params) {
  return request({
    url: '/api/v1/admin/lawyer-applications',
    method: 'get',
    params
  })
}

/**
 * 查询入驻申请详情（四块表单 + 审核流水 + 60 秒签名影像 URL）。
 *
 * @param {string} id 申请主键（字符串，勿转数字）
 * @returns {Promise<{code:number,msg:string,data:object}>} 详情（含 form 与 logs）
 */
export function getLawyerApplicationDetail(id) {
  return request({
    url: `/api/v1/admin/lawyer-applications/${id}`,
    method: 'get'
  })
}

/**
 * 审核入驻申请。
 *
 * 副作用（服务端）：approve 会写 lawyer 表、改 app_user.role='lawyer'、
 * 追加 lawyer_application_log；reject 只改状态与流水。
 *
 * @param {string} id 申请主键（字符串）
 * @param {{action:'approve'|'reject', reason?:string}} data action 必填；驳回时 reason 必填（≤200）
 * @returns {Promise<{code:number,msg:string,data:object}>} 审核结果
 */
export function auditLawyerApplication(id, data) {
  return request({
    url: `/api/v1/admin/lawyer-applications/${id}/audit`,
    method: 'post',
    data
  })
}
