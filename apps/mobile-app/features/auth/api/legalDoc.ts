/**
 * 法务文本（用户服务协议 / 隐私政策）接口。
 *
 * 职责：从服务端配置读取协议正文，页面只渲染、**不写死正文**（G1 裁决 #12）。
 * 调用方：`features/auth/pages/AgreementPage.tsx`、`PrivacyPage.tsx`。
 * 数据来源：`GET /api/v1/pub/config/{configKey}`（免登录，对应 `sys_config` 的两条键：
 *           `user_agreement_content` / `privacy_policy_content`）。
 * 注意：
 *   1. **这是与后端的缺口**：截至 2026-11-05，`services/faze-api` 没有公开的配置读取接口，
 *      `database/seed/seg1_config.sql` 里也没有这两条键。本文件按《第 1 段工作流》§5.5
 *      列出的键名与《接口规范》§3 的 `/pub/**` 公开前缀约定**先定契约**；
 *      后端补齐后页面无需改动。缺口已写进交付报告。
 *   2. 正文以纯文本返回（换行分段渲染）。若将来改为富文本，只需改本文件的解析与页面渲染。
 *   3. `updatedAt` 可选：没有时页面不显示「更新日期」。
 */

import { get } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 支持的文本类型 → `sys_config` 键。 */
export const LEGAL_DOC_KEYS = {
  agreement: 'user_agreement_content',
  privacy: 'privacy_policy_content',
} as const;

/** 法务文本类型。 */
export type LegalDocKind = keyof typeof LEGAL_DOC_KEYS;

/** 服务端返回的配置项（与 `sys_config` 一行对应）。 */
export type SysConfigItem = {
  /** 配置键。 */
  configKey: string;
  /** 配置值（法务文本正文）。 */
  configValue: string;
  /** 最后更新时间（UTC，可空）。 */
  updatedAt: string | null;
};

/**
 * 读取一条公开配置。
 *
 * 副作用：无。
 *
 * @param configKey 配置键（只允许本文件登记的两个键）
 * @returns 成功时 `data` 为配置项
 */
export function getSysConfig(configKey: string): Promise<ApiResult<SysConfigItem>> {
  return get<SysConfigItem>(`/api/v1/pub/config/${configKey}`, { auth: false });
}

/**
 * 读取法务文本。
 *
 * @param kind 协议类型
 * @returns 成功时 `data` 为配置项；正文为空由页面走空态
 */
export function getLegalDoc(kind: LegalDocKind): Promise<ApiResult<SysConfigItem>> {
  return getSysConfig(LEGAL_DOC_KEYS[kind]);
}
