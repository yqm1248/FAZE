/*
 * 职责：PC 业务页导出响应的统一消费工具——区分「同步 CSV 文件流」与「异步 410015 + taskId」。
 * 调用方：src/views/biz/ops/Dashboard.vue（导出报表）、src/views/biz/ops/LogCenter.vue（导出两种日志）。
 * 数据来源：faze-api 的 5 个导出接口；响应形态见 `modules/ops/admin/controller/ExportResponses`。
 * 注意：
 *   1. 导出接口是**文件下载例外**：同步时 Content-Type = text/csv，异步时是 HTTP 200 + JSON
 *      （`{code:410015, data:{taskId}}`）。axios 用 `responseType:'blob'` 时，两种形态都会落成
 *      Blob，**如果不看 Content-Type 就直接存盘，会把 JSON 存成 .csv**（比报错更糟：
 *      用户以为导出了文件，其实是任务提示）。本工具负责先分类再决定动作。
 *   2. 判定依据是 Blob.type（服务端 ExportResponses 明确设置 application/json 与 text/csv），
 *      不看响应体首字符——CSV 字段里也可能以 `{` 开头（防 CSV 注入时还会加单引号）。
 *   3. JSON 解析失败时原样返回文本片段，调用方按「导出失败」显示，不静默吞掉。
 *   4. 下载动作放这里是为了让两个页面共用同一份文件名/Blob URL 清理逻辑，避免漏 revoke。
 */

/**
 * 把导出响应 Blob 分类。
 *
 * 返回值：
 *   - `{kind:'file'}`：同步 CSV，调用方用 {@link downloadBlob} 存盘；
 *   - `{kind:'async', taskId, msg}`：行数超同步上限，服务端已转后台任务；
 *   - `{kind:'json', code, msg}`：服务端返回了 JSON 但不是异步成功（错误/未知）；
 *   - `{kind:'unknown', msg}`：拿到的不是 Blob，且无法识别。
 *
 * @param {Blob|object|null|undefined} payload axios `responseType:'blob'` 的 `res.data`
 * @returns {Promise<{kind:string, taskId?:string, code?:number, msg?:string}>} 分类结果
 */
export async function classifyExportPayload(payload) {
  if (!(payload instanceof Blob)) {
    if (payload && typeof payload === 'object' && payload.code !== undefined) {
      return classifyJson(payload)
    }
    return { kind: 'unknown', msg: '导出接口未返回文件流' }
  }
  const type = String(payload.type || '').toLowerCase()
  if (type.includes('json')) {
    const text = await payload.text()
    try {
      return classifyJson(JSON.parse(text))
    } catch (error) {
      return { kind: 'json', code: null, msg: `导出接口返回了无法解析的 JSON：${text.slice(0, 200)}` }
    }
  }
  return { kind: 'file' }
}

/**
 * 解析 R 响应体（异步导出提示或错误）。
 *
 * @param {object} body R 响应体
 * @returns {{kind:string, taskId?:string, code?:number, msg?:string}} 分类结果
 */
function classifyJson(body) {
  const code = body.code
  const msg = body.msg || ''
  const taskId = body.data && body.data.taskId ? String(body.data.taskId) : ''
  if (code === 200 && taskId) {
    return { kind: 'async', taskId, code, msg }
  }
  if (code === 410015) {
    return { kind: 'async', taskId, code, msg }
  }
  return { kind: 'json', code, msg: msg || '导出失败' }
}

/**
 * 触发浏览器下载。
 *
 * 约束：调用方负责给出 `.csv` 文件名；Blob URL 用完即 revoke，避免内存泄漏。
 *
 * @param {Blob} blob 文件流
 * @param {string} fileName 下载文件名
 * @returns {void}
 */
export function downloadBlob(blob, fileName) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

/**
 * 生成导出文件名：`{type}_{yyyyMMddHHmmss}.csv`（与后端 CsvExporter 同一规则）。
 *
 * @param {string} type 文件类型前缀，如 `dashboard` / `operation_logs` / `login_logs`
 * @param {Date} [date] 时间，默认当前时间
 * @returns {string} 文件名
 */
export function buildExportFileName(type, date) {
  const now = date || new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `${type}_${stamp}.csv`
}
