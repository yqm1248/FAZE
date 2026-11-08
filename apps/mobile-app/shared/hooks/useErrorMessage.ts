/**
 * 把 `AppError` 翻成一句可读文案（页面内联错误用）。
 *
 * 职责：页面里除了 `ErrorState` 之外，还有「提交按钮下方的一行红字」这种内联错误，
 *       它们也需要同一套归一规则，避免各页各写一份 `msgKey → t()`。
 * 调用方：段 1 G 组所有表单页（注册、忘记密码、改密、换绑、实名、入驻、支付密码等）。
 * 数据来源：`shared/api/types.ts` 的 `AppError`；文案在 `resources/zh-Hans/*.json`。
 * 注意：
 *   1. **绝不展示服务端 `R.msg`**（接口规范 §5.1 约束 2）；只认 `msgKey`。
 *   2. 网络 / 解析错误统一走 `error.common.*`；业务错误走映射表兜底。
 *      未登记的码会落到 `error.common.server`（`errorCodes.ts` 已保证）。
 *   3. 返回函数而不是字符串：一次渲染里可能要翻多条错误（字段级 + 接口级）。
 */

import { useCallback } from 'react';

import type { AppError } from '@/shared/api/types';
import { useI18n } from '@/shared/i18n';
import type { MessageKey } from '@/shared/i18n';

/**
 * 取「错误 → 文案」函数。
 *
 * @returns 接收 `AppError` 返回已翻译文案的函数
 */
export function useErrorMessage(): (error: AppError) => string {
  const { t } = useI18n();
  return useCallback(
    (error: AppError): string => t(error.msgKey as MessageKey),
    [t],
  );
}
