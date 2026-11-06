/**
 * 业务错误码 → i18n 文案 key 的映射。
 *
 * 依据：《错误码规范》§3 与《接口规范》§5.1。
 * 调用方：`shared/api/client.ts`（把非 200 的业务码翻译成 msgKey）。
 * 注意：
 *   1. 段 0 只登记 6 个码；**段 1 G 组按同一张表补齐 18 页用到的码**（其余仍走兜底），
 *      整表填充是段 8 的收口工作。未登记的码由 `client.ts` 兜底成
 *      `error.common.server`，不会显示空文案。
 *   2. key 用**完整 msgKey**（`error.auth.token-expired`），不拼前缀（i18n 规范 §4.4）。
 *   3. 这里只做「码 → key」映射，不做文案——文案在 `shared/i18n/resources/**` 里。
 */

import type { MessageKey } from './types';

/** 段 0 + 段 1 G 组用到的业务码。 */
export const ERROR_MSG_KEYS: Readonly<Record<number, MessageKey>> = {
  401001: 'error.common.param-invalid',
  401002: 'error.common.param-missing',
  401003: 'error.common.param-format',
  401004: 'error.common.not-found',
  401005: 'error.common.already-exists',
  401006: 'error.common.illegal-state',
  401007: 'auth.login.rateLimited',
  401008: 'error.common.forbidden',
  402001: 'auth.login.failed',
  402002: 'error.auth.token-expired',
  402003: 'error.auth.token-invalid',
  402004: 'auth.login.banned',
  402005: 'error.auth.code-invalid',
  402006: 'error.auth.phone-taken',
  402008: 'error.auth.account-cancelled',
  402009: 'error.auth.guardian-required',
  402010: 'error.auth.password-weak',
  404001: 'error.conversation.not-found',
  404002: 'error.conversation.forbidden',
  405001: 'error.trade.balance-insufficient',
  405006: 'error.trade.amount-invalid',
  405011: 'error.trade.refund-not-found',
  405021: 'error.trade.review-not-found',
  405022: 'error.trade.review-duplicate',
  501001: 'error.common.internal',
  501002: 'error.common.third-party-down',
  501003: 'error.common.db-error',
  502001: 'error.auth.sms-send-failed',
  508001: 'error.ai.service-down',
};

/** 未登记业务码与未知异常的兜底 key。 */
export const FALLBACK_ERROR_KEY: MessageKey = 'error.common.server';

/** 断网/超时的 key。 */
export const NETWORK_ERROR_KEY: MessageKey = 'error.common.network';

/**
 * 业务码 → msgKey。
 *
 * @param code 6 位业务码
 * @returns 已登记的 key；未登记时返回兜底 key（**不返回 undefined**）
 */
export function msgKeyOfCode(code: number): MessageKey {
  return ERROR_MSG_KEYS[code] ?? FALLBACK_ERROR_KEY;
}
