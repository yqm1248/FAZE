/*
 * 职责：法泽业务接口（faze-api，端口 8081）的 axios 实例——PC 后台 biz 页专用。
 * 调用方：src/api/biz/** 下的所有接口文件。
 * 数据来源：/biz-api 前缀（vite proxy 转到 http://127.0.0.1:8081）。
 * 注意：
 *   1. **单独一个实例，不碰 src/utils/request.js**。AGENTS §3.3 禁止改 RuoYi 框架层；
 *      RuoYi 的 request.js 的 baseURL 是 /dev-api（打 RuoYi 自己），职责不同。
 *   2. **token 复用 RuoYi 的 Cookie `Admin-Token`**（接口规范 §1.3 方案 A：
 *      两端共享 token.secret + 共享 Redis，faze-api 认 RuoYi 签发的 token），
 *      所以用户**只登一次**。这里只是把同一个 token 塞进 Authorization 头，
 *      与 RuoYi 的 request.js 第 32 行做的事完全一样。
 *   3. **管理端鉴权失败是 HTTP 200 + body.code === 401**（接口规范 §6.2 裁决 9）。
 *      不是 HTTP 401。RuoYi 前端只在 code===401 时跳登录，按 HTTP 状态判会卡死。
 *   4. 幂等：写资金接口必须带 `Idempotency-Key` 头。本文件提供 `newIdempotencyKey()`，
 *      调用方在**用户点提交时生成一次**并复用——每次重试都换新键等于没做幂等。
 *   5. 列表接口的契约是 RuoYi 的 TableDataInfo（顶层 rows / total），
 *      不是 R<T> 包一层（接口规范 §5.2），所以列表接口直接返回 res.data。
 */
import axios from 'axios'
import { getToken, removeToken } from '@/utils/auth'
import { ElMessage } from 'element-plus'

/** 业务接口前缀，与 vite.config.js 的 server.proxy 一一对应。 */
const BIZ_BASE_API = '/biz-api'

const service = axios.create({
  baseURL: BIZ_BASE_API,
  // 20 秒：管理端有导出/汇总类查询，给得比默认宽一些，但不能无限等
  timeout: 20000,
  headers: { 'Content-Type': 'application/json;charset=utf-8' }
})

/**
 * 生成幂等键。
 *
 * 约束（接口规范 §6.4）：长度 ≤ 64，客户端生成，重试必须复用同一个键。
 *
 * @param {string} prefix 业务前缀，如 'recharge'
 * @returns {string} 幂等键
 */
export function newIdempotencyKey(prefix) {
  const rand = (globalThis.crypto && globalThis.crypto.randomUUID)
    ? globalThis.crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${prefix}:${rand}`.slice(0, 64)
}

service.interceptors.request.use(
  config => {
    const token = getToken()
    if (token && !config.headers.Authorization) {
      config.headers.Authorization = 'Bearer ' + token
    }
    return config
  },
  error => Promise.reject(error)
)

service.interceptors.response.use(
  res => {
    const body = res.data
    // 非 R 结构（如文件流）原样返回
    if (body === null || body === undefined || typeof body !== 'object' || body.code === undefined) {
      return body
    }
    if (body.code === 200) {
      return body
    }
    // 管理端鉴权失败：HTTP 200 + code 401（裁决 9），必须清 token 回登录页
    if (body.code === 401) {
      removeToken()
      ElMessage.error(body.msg || '登录状态已过期，请重新登录')
      // 用整页跳转而不是 router.push：登录态已失效，路由守卫重算一遍更干净
      window.location.href = '/index'
      return Promise.reject(new Error(body.msg || '登录状态已过期'))
    }
    ElMessage.error(body.msg || '请求失败')
    return Promise.reject(new Error(body.msg || '请求失败'))
  },
  error => {
    const status = error.response && error.response.status
    ElMessage.error(status ? `接口请求失败（HTTP ${status}）` : '网络异常，请检查 faze-api 是否已启动')
    return Promise.reject(error)
  }
)

export default service
