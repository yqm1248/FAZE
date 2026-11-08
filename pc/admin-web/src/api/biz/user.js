/*
 * 职责：PC A-03 用户列表的接口封装（第 1 段 F2，契约见《F2+F3 接口冻结契约》§三 A-03）。
 * 调用方：src/views/biz/user/index.vue。
 * 数据来源：faze-api `/api/v1/admin/users**`（经 vite 代理 /biz-api → http://127.0.0.1:8081）。
 * 注意：
 *   1. 列表接口走 RuoYi 的 TableDataInfo：**顶层就是 rows / total**，页面读 `res.rows` / `res.total`；
 *      详情与写接口走 `R<T>`，业务字段在 `res.data`。两者不要混。
 *   2. **路径里的 id 一律是字符串**：app_user.id 是 BIGINT，服务端序列化成字符串，
 *      这里不做 Number()，否则超过 2^53 的 ID 会静默变成另一个人。
 *   3. 本段不提供完整手机号与身份证号（接口规范附录 A.2）：不要试图加 `?full=true` 之类的参数。
 */
import request from '@/api/biz/request'

/**
 * 分页查询用户列表。
 *
 * @param {object} params 查询条件
 * @param {number} params.pageNum 页码（从 1 开始）
 * @param {number} params.pageSize 每页条数（服务端 clamp 到 1..100）
 * @param {string} [params.nickname] 昵称（模糊）
 * @param {string} [params.phone] 手机号（精确或后 4 位）
 * @param {string} [params.realnameStatus] 实名状态：NONE|PENDING|APPROVED|REJECTED
 * @param {string} [params.accountStatus] 账号状态：NORMAL|BANNED|CANCELLED
 * @param {string} [params.beginTime] 注册时间起（ISO date-time）
 * @param {string} [params.endTime] 注册时间止（ISO date-time）
 * @returns {Promise<{code:number,msg:string,total:number,rows:Array<object>}>} 列表数据
 */
export function listUsers(params) {
  return request({
    url: '/api/v1/admin/users',
    method: 'get',
    params
  })
}

/**
 * 查询用户详情（含设备与实名信息）。
 *
 * @param {string} id 用户 ID（字符串，勿转数字）
 * @returns {Promise<{code:number,msg:string,data:object}>} 详情
 */
export function getUserDetail(id) {
  return request({
    url: `/api/v1/admin/users/${id}`,
    method: 'get'
  })
}

/**
 * 封禁用户。
 *
 * 副作用：服务端把 app_user.status 置 blocked 并 token_version+1，C 端旧 token 立即 401。
 *
 * @param {string} id 用户 ID（字符串）
 * @param {{reason:string, until?:string|null}} data 封禁原因（必填，≤200）与解封时间（可空）
 * @returns {Promise<{code:number,msg:string,data:null}>} 操作结果
 */
export function banUser(id, data) {
  return request({
    url: `/api/v1/admin/users/${id}/ban`,
    method: 'post',
    data
  })
}

/**
 * 解封用户。
 *
 * @param {string} id 用户 ID（字符串）
 * @param {{reason:string}} data 解封原因（必填，≤200）
 * @returns {Promise<{code:number,msg:string,data:null}>} 操作结果
 */
export function unbanUser(id, data) {
  return request({
    url: `/api/v1/admin/users/${id}/unban`,
    method: 'post',
    data
  })
}
