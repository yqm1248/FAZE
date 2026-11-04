/*
 * 职责：PC 后台「法泽业务」7 个页面（A-03~A-09）共用的展示格式化函数——金额、时间、空值兜底。
 * 调用方：src/views/biz/{user,verification,lawyerApplication,lawyer,wallet,sysConfig,reconciliation}/index.vue。
 * 数据来源：faze-api `/api/v1/admin/**` 的出参。
 * 注意：
 *   1. **金额只在这里换算**：接口给整数分，展示 (cents/100).toFixed(2)。
 *      不做浮点累加——要合计时先用整数分相加再换算，本文件不提供「元 → 元」的加法。
 *      `yuanToCents` 用字符串拆位做整数运算，不用 `Number(x) * 100`，避免 0.1+0.2 那类误差。
 *   2. **时间按 UTC 解析再转本地**：faze-api 的时间字段由 `LocalDateTime.now(ZoneOffset.UTC)`
 *      写入（实测见 services/faze-api/src/main/java/com/faze/modules/trade/service/BalanceService.java:97），
 *      Jackson 3 序列化成不带时区偏移的 ISO-8601（如 `2026-11-04T17:20:25`）。
 *      直接展示会差 8 小时，所以无偏移的串补 `Z` 后按浏览器时区格式化；带 `Z` / `+08:00` 的原样解析。
 *      这也是**推测性约定**：若后端改成存本地时间，只需改本文件一处。
 *   3. **ID 不做数值化**：BIGINT 超过 JS 安全整数（2^53），页面里 ID 一律当字符串透传与展示。
 *      本文件只对金额/计数做数值换算，不接受也不返回 ID。
 *   4. 空值统一回 `—`（U+2014），不要回空串：表格里空串会让列看起来像没渲染。
 */

/** 补两位。 */
const PAD = (n) => String(n).padStart(2, '0')

/**
 * 把接口里的「数字或数字字符串」转成 number。
 *
 * 注意：服务端 JacksonConfig 会把包装类 `Long` 序列化成字符串，
 * 所以金额/计数可能是 `"12345"` 而不是 `12345`。**只用于金额和计数，绝不用于 ID**。
 *
 * @param {number|string|null|undefined} value 接口原值
 * @returns {number} 有限数值；无法解析时返回 0
 */
export function toNumber(value) {
  if (value === null || value === undefined || value === '') {
    return 0
  }
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

/**
 * 整数分 → 元，保留两位小数。
 *
 * @param {number|string|null|undefined} cents 金额（分）
 * @returns {string} 形如 `1280.00`（不带千分位、不带货币符号，模板里自己加 ¥）
 */
export function formatCents(cents) {
  return (toNumber(cents) / 100).toFixed(2)
}

/**
 * 带符号的整数分 → 元（用于流水、调账的增减）。
 *
 * @param {number|string|null|undefined} cents 金额（分）
 * @returns {string} 正数带 `+`，负数带 `-`，形如 `+12.00` / `-5.00`
 */
export function formatSignedCents(cents) {
  const value = toNumber(cents)
  const text = (value / 100).toFixed(2)
  return value > 0 ? `+${text}` : text
}

/**
 * 元 → 整数分（用户输入的是元，接口要的是分）。
 *
 * 用正则拆出「整数位 + 两位小数位」做整数运算，**不做浮点乘法**：
 * `Number('12.34') * 100` 在某些值上会得到 1233.9999999999998。
 *
 * @param {string|number} input 用户输入的元，如 `12`、`12.3`、`12.34`
 * @returns {number|null} 整数分；格式非法（含负号、超过两位小数、非数字）时返回 null
 */
export function yuanToCents(input) {
  const text = String(input === null || input === undefined ? '' : input).trim()
  const matched = /^(\d+)(?:\.(\d{1,2}))?$/.exec(text)
  if (!matched) {
    return null
  }
  const yuan = Number(matched[1])
  const fen = Number((matched[2] || '').padEnd(2, '0'))
  return yuan * 100 + fen
}

/**
 * 解析服务端时间串为 Date。
 *
 * 规则：纯日期（`2026-11-05`）按本地日期解析，不做时区换算；
 * 不含时区偏移的日期时间按 UTC 解析（见文件头注释第 2 条）。
 *
 * @param {string|null|undefined} value 服务端时间串
 * @returns {Date|null} 解析失败返回 null
 */
function parseServerDateTime(value) {
  if (!value) {
    return null
  }
  const text = String(value).trim()
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    const [year, month, day] = text.split('-').map(Number)
    return new Date(year, month - 1, day)
  }
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/.test(text)
  const iso = hasZone ? text : `${text.replace(' ', 'T')}Z`
  const parsed = new Date(iso)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

/**
 * 服务端时间 → 本地 `YYYY-MM-DD HH:mm`。
 *
 * @param {string|null|undefined} value 服务端时间串
 * @returns {string} 格式化结果；空值回 `—`，无法解析时原样回显
 */
export function formatDateTime(value) {
  if (!value) {
    return '—'
  }
  const date = parseServerDateTime(value)
  if (!date) {
    return String(value)
  }
  return (
    `${date.getFullYear()}-${PAD(date.getMonth() + 1)}-${PAD(date.getDate())}` +
    ` ${PAD(date.getHours())}:${PAD(date.getMinutes())}`
  )
}

/**
 * 服务端日期/时间 → 本地 `YYYY-MM-DD`。
 *
 * @param {string|null|undefined} value 服务端时间串
 * @returns {string} 格式化结果；空值回 `—`
 */
export function formatDate(value) {
  if (!value) {
    return '—'
  }
  const text = String(value).trim()
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    return text
  }
  const date = parseServerDateTime(text)
  if (!date) {
    return text
  }
  return `${date.getFullYear()}-${PAD(date.getMonth() + 1)}-${PAD(date.getDate())}`
}

/**
 * 空值兜底：null / undefined / 空串统一回 `—`。
 *
 * @param {*} value 任意展示值
 * @returns {string|number} 非空原值，空值回 `—`
 */
export function textOrDash(value) {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  return value
}

/**
 * 把请求异常翻译成能贴到页面上的中文错误文案。
 *
 * 为什么需要：`api/biz/request.js` 的响应拦截器对 **HTTP 非 2xx** 只做了 toast 并抛出原始
 * axios error，页面里拿到的是 `Request failed with status code 404` 这种英文技术串，
 * 直接贴到页面上对使用者没有意义。业务错误（HTTP 200 + code≠200）拦截器已经抛了 `Error(msg)`，
 * msg 就是后端的英文/中文提示，这里原样透传。
 *
 * @param {Error & {response?: {status?: number}}} error 捕获到的异常
 * @param {string} fallback 兜底文案
 * @returns {string} 可直接展示的中文错误信息
 */
export function describeRequestError(error, fallback) {
  const defaultText = fallback || '请求失败，请稍后重试'
  if (!error) {
    return defaultText
  }
  const status = error.response && error.response.status
  if (status) {
    if (status === 404) {
      return '接口不存在（HTTP 404）：后端该接口可能尚未实现，或 faze-api 未启动'
    }
    return `接口请求失败（HTTP ${status}），请确认 faze-api（8081）状态`
  }
  return error.message || defaultText
}
