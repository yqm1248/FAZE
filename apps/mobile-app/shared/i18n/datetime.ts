/**
 * 日期与金额格式化（**不用 `Intl`**）。
 *
 * 依据：技术路线 §13.7.1 已裁决不用 `Intl`（Hermes 的 locale 数据覆盖不确定，
 * `kk_Arab`/`ug_Arab` 缺失时会静默回退英文）；此处用自建表实现。
 * 调用方：列表页、详情页、钱包页（段 1 起大量使用）。
 * 注意：
 *   1. **金额一律整数分**（`AGENTS.md` §4.5）：入参是分，展示时除以 100，**禁止浮点累加**。
 *   2. 相对时间只做「刚刚 / n 分钟前 / n 小时前 / 昨天 / 日期」这几档，
 *      不做「上周」「上个月」这类需要完整日历的档位（段 6 若需要再补）。
 *   3. 所有输出都是**已翻译好的字符串或纯数字串**，不在这里查 i18n（保持纯函数可单测）。
 */

/** 一天毫秒数。 */
const MS_PER_MINUTE = 60_000;
/** 一小时毫秒数。 */
const MS_PER_HOUR = 3_600_000;
/** 一天毫秒数。 */
const MS_PER_DAY = 86_400_000;

/**
 * 补零。
 *
 * @param value 数值
 * @returns 两位字符串
 */
function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

/**
 * 格式化日期为 `YYYY-MM-DD`。
 *
 * @param input 时间戳（毫秒）或 Date
 * @returns 形如 `2026-11-03`
 */
export function formatDate(input: number | Date): string {
  const date = input instanceof Date ? input : new Date(input);
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

/**
 * 格式化日期时间为 `YYYY-MM-DD HH:mm`。
 *
 * @param input 时间戳（毫秒）或 Date
 * @returns 形如 `2026-11-03 14:05`
 */
export function formatDateTime(input: number | Date): string {
  const date = input instanceof Date ? input : new Date(input);
  return `${formatDate(date)} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

/**
 * 相对时间（用于列表的「刚刚 / n 分钟前」）。
 *
 * 超过一天直接回日期，避免出现「37 天前」这种不可读的结果。
 *
 * @param input 目标时间戳（毫秒）
 * @param now   当前时间戳（毫秒），默认 `Date.now()`；显式传入便于单测
 * @returns 相对时间描述
 */
export function formatRelative(input: number, now: number = Date.now()): string {
  const diff = now - input;
  if (diff < MS_PER_MINUTE) {
    return '刚刚';
  }
  if (diff < MS_PER_HOUR) {
    return `${Math.floor(diff / MS_PER_MINUTE)} 分钟前`;
  }
  if (diff < MS_PER_DAY) {
    return `${Math.floor(diff / MS_PER_HOUR)} 小时前`;
  }
  if (diff < MS_PER_DAY * 2) {
    return '昨天';
  }
  return formatDate(input);
}

/**
 * 把「分」格式化成「元」的展示串。
 *
 * **整数运算实现**：先取整数部分与两位小数，避免 `cents / 100` 的浮点误差。
 *
 * @param cents 金额（分）
 * @param withSymbol 是否带 `¥` 前缀，默认 true
 * @returns 形如 `¥12,345.67`
 */
export function formatAmount(cents: number, withSymbol = true): string {
  const negative = cents < 0;
  const abs = Math.abs(Math.trunc(cents));
  const yuan = Math.floor(abs / 100);
  const fraction = abs % 100;
  const grouped = String(yuan).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const body = `${grouped}.${pad2(fraction)}`;
  const sign = negative ? '-' : '';
  return `${sign}${withSymbol ? '¥' : ''}${body}`;
}
