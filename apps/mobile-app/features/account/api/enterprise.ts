/**
 * 企业认证接口（状态查询 / 提交）。
 *
 * 职责：企业认证页的唯一数据入口。四态（none / pending / approved / rejected）所需字段都在这里。
 * 调用方：`features/account/pages/EnterpriseAuthPage.tsx`、`features/settings/pages/SettingsPage.tsx`（行值）。
 * 数据来源：
 *   - `GET /api/v1/app/account/enterprise`（段 7 E2 补的查状态接口）；
 *   - `POST /api/v1/app/account/enterprise`（段 1 已交付，入参 `EnterpriseBO`）。
 * 注意：
 *   1. **状态枚举大小写不统一的风险在这里统一收口**：工作流写的是小写
 *      （none/pending/approved/rejected），段 1 的实名接口用的是大写（NONE/PENDING/...）。
 *      后端两种都可能返回，页面一律用 `normalizeEnterpriseStatus()` 归一，**不在页面里散落判断**。
 *   2. 提交入参字段名是 `licenseImage`（不是 `licenseOssKey`）：前者是提交用的 key，
 *      后者是查询出参里的字段名——两边不同名是后端既有契约，不要「统一」掉。
 *   3. 营业执照 key 必须先走 `POST /app/upload/token` 三段式上传（scene = `license`），
 *      **页面不许自己拼 key**。
 *   4. 信用代码只做「18 位 + 字符集」校验，**不做校验位**（工作流明文：避免误杀），
 *      校验正则放在页面里，不放到这一层（api 层不做业务校验）。
 */

import { get, post } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/** 企业认证四种状态（归一小写后）。 */
export type EnterpriseStatus = 'none' | 'pending' | 'approved' | 'rejected';

/** 已提交的企业信息（出参 `submitted`）。 */
export type EnterpriseSubmitted = {
  /** 企业名称。 */
  companyName: string;
  /** 统一社会信用代码。 */
  creditCode: string;
  /** 法定代表人姓名。 */
  legalPerson: string;
  /** 营业执照对象 key（预览要再换短时地址；本页不回显图片）。 */
  licenseOssKey: string | null;
  /** 提交时间（UTC，无时区后缀）。 */
  submittedAt: string | null;
  /** 审核时间（UTC，无时区后缀）。 */
  reviewedAt: string | null;
};

/** 企业认证状态出参。 */
export type EnterpriseData = {
  /** 状态（可能是大写，页面用 `normalizeEnterpriseStatus()` 归一）。 */
  status: string;
  /** 驳回原因（rejected 时非空）。 */
  rejectReason: string | null;
  /** 已提交内容；从未提交时为 null。 */
  submitted: EnterpriseSubmitted | null;
};

/** 提交企业认证入参（对齐段 1 的 `EnterpriseBO`）。 */
export type EnterpriseInput = {
  /** 企业名称，≤100 字。 */
  companyName: string;
  /** 统一社会信用代码，18 位。 */
  creditCode: string;
  /** 法定代表人姓名，≤50 字。 */
  legalPerson: string;
  /** 营业执照对象 key（上传后得到）。 */
  licenseImage: string;
  /** 联系电话（可空）。 */
  contactPhone?: string;
};

/**
 * 把后端返回的状态归一成小写四态。
 *
 * 未知值一律按 `none` 处理：宁可让用户看到「未认证」并重新提交（后端会拦重复提交），
 * 也不要因为一个没见过的枚举值让页面卡在「什么态都不是」。
 *
 * @param raw 后端原始状态
 * @returns 四种状态之一
 */
export function normalizeEnterpriseStatus(raw: string): EnterpriseStatus {
  const value = raw.trim().toLowerCase();
  if (value === 'pending' || value === 'approved' || value === 'rejected') {
    return value;
  }
  return 'none';
}

/**
 * 查询企业认证状态。
 *
 * @returns 成功时 `data` 为状态、驳回原因、已提交内容
 */
export function getEnterpriseAuth(): Promise<ApiResult<EnterpriseData>> {
  return get<EnterpriseData>('/api/v1/app/account/enterprise');
}

/**
 * 提交企业认证（送审）。
 *
 * 副作用：服务端写入企业认证记录并把状态置为待审；**不限提交次数**（驳回后可重提）。
 *
 * @param input 企业名称、信用代码、法定代表人、营业执照 key
 * @returns 成功时 `data` 为 null
 */
export function submitEnterpriseAuth(input: EnterpriseInput): Promise<ApiResult<null>> {
  return post<null>('/api/v1/app/account/enterprise', input);
}
