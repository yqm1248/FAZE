/*
 * 职责：把服务端返回的 UTC 时间格式化成「会话列表用」的相对时间（今天 HH:mm / 昨天 / M-D）。
 * 调用方：`features/conversation/pages/ConversationsPage.tsx`（用户端会话列表）、
 *         `features/workbench/pages/ConsultationsPage.tsx`（律师端客户咨询列表）。
 * 数据来源：服务端 UTC 无时区字符串（如 `2026-11-13T13:16:30`）。
 * 注意：
 *   1. **返回 `'yesterday'` 是特殊值**，不是文案：调用方必须拿它去查 i18n
 *      （两端的「昨天」key 不在同一个 namespace：用户端 `conversation.list.yesterday`、
 *      律师端 `workbench.consult.yesterday`）。直接上屏会显示英文 yesterday。
 *   2. 服务端时间**没有时区后缀**，必须补 `Z` 再 `new Date()`；不补的话浏览器按本地时区解释，
 *      在东八区会整体偏 8 小时（「昨天的消息显示成今天」）。
 *   3. 空串 / null / 非法时间一律返回空串，调用方不用再判——列表行里时间列可以为空，
 *      布局用 `justifyContent: space-between` 不会塌。
 *   4. 从 `ConsultationsPage` 抽出来的（2026-11-14）：两个会话列表要同一套相对时间口径，
 *      复制一份迟早会漂移（其中一个改成「本周内显示星期几」时另一个不会跟着改）。
 */

/**
 * 把 UTC 时间格式化成相对时间。
 *
 * 副作用：无。
 *
 * @param raw 服务端 UTC 无时区字符串，可空
 * @returns 今天返回 `HH:mm`、昨天返回字面量 `'yesterday'`、更早返回 `M-D`；非法输入返回空串
 */
export function formatRelativeMessageTime(raw: string | null): string {
  if (raw === null || raw === '') {
    return '';
  }
  const date = new Date(raw.endsWith('Z') ? raw : `${raw}Z`);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const now = new Date();
  const sameDay =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();
  const pad = (value: number): string => String(value).padStart(2, '0');
  if (sameDay) {
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }
  const yesterday = new Date(now.getTime() - 86_400_000);
  if (
    date.getFullYear() === yesterday.getFullYear() &&
    date.getMonth() === yesterday.getMonth() &&
    date.getDate() === yesterday.getDate()
  ) {
    return 'yesterday';
  }
  return `${date.getMonth() + 1}-${date.getDate()}`;
}
