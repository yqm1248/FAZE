/*
 * 职责：PC A-04 实名认证审核的接口封装（第 1 段 F2，契约见《F2+F3 接口冻结契约》§三 A-04）。
 * 调用方：src/views/biz/verification/index.vue。
 * 数据来源：faze-api `/api/v1/admin/verifications**`（realname_auth 与 enterprise_auth 两表合并分页）。
 * 注意：
 *   1. **查询参数的 type 是小写**（`realname` / `enterprise`，空 = 全部），
 *      而**出参里的 type 是大写**（`REALNAME` / `ENTERPRISE`）。页面里必须做映射，不能直接透传。
 *   2. 详情接口必须带 `type`：同一个 id 在两张表里都可能存在，缺 type 服务端无法定位。
 *   3. 影像字段（frontUrl / backUrl / licenseUrl）是**60 秒有效的签名 URL**，
 *      所以只在打开抽屉时逐个取，**不要在列表循环里预取**——列表 20 行会白白签 20 次且很快就过期。
 *      未上传影像时服务端给 null，页面要显示「未上传」而不是坏图。
 *   4. 列表接口顶层是 rows / total；详情与审核是 R<T>（`res.data`）。
 */
import request from '@/api/biz/request'

/**
 * 分页查询认证审核队列（实名 + 企业合并）。
 *
 * @param {object} params 查询条件
 * @param {number} params.pageNum 页码（从 1 开始）
 * @param {number} params.pageSize 每页条数
 * @param {string} [params.type] 认证类型：realname|enterprise（空 = 全部）
 * @param {string} [params.status] 审核状态：PENDING|APPROVED|REJECTED（空 = 全部）
 * @param {string} [params.beginTime] 提交时间起（ISO date-time）
 * @param {string} [params.endTime] 提交时间止（ISO date-time）
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} 队列数据
 */
export function listVerifications(params) {
  return request({
    url: '/api/v1/admin/verifications',
    method: 'get',
    params
  })
}

/**
 * 查询单条认证详情（含 60 秒签名影像 URL）。
 *
 * @param {string} id 认证记录主键（字符串，勿转数字）
 * @param {string} type 认证类型：realname|enterprise（必填，小写）
 * @returns {Promise<{code:number,msg:string,data:object}>} 详情
 */
export function getVerificationDetail(id, type) {
  return request({
    url: `/api/v1/admin/verifications/${id}`,
    method: 'get',
    params: { type }
  })
}

/**
 * 审核认证申请（通过 / 驳回）。
 *
 * @param {string} id 认证记录主键（字符串）
 * @param {{type:string, action:'approve'|'reject', reason?:string}} data
 *        type 为 realname|enterprise；action 为 approve|reject；驳回时 reason 必填（≤200）
 * @returns {Promise<{code:number,msg:string,data:{id:string,status:string}}>} 审核结果
 */
export function auditVerification(id, data) {
  return request({
    url: `/api/v1/admin/verifications/${id}/audit`,
    method: 'post',
    data
  })
}
