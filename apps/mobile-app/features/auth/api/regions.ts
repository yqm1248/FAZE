/**
 * 地区列表接口（注册页「所在地区」选择器）。
 *
 * 职责：读服务端下发的可选地区列表；接口不可用时退回内置默认列表（伊犁州直辖县市 + 其他）。
 * 调用方：`features/auth/pages/RegisterPage.tsx`（挂载时加载一次）。
 * 数据来源：`GET /api/v1/pub/regions`（免登录；后端 2026-11-09 新增，backend-ops 交付）。
 * 注意：
 *   1. **接口形状未冻结**：后端可能返回 `["伊宁市", ...]`，也可能返回 `{ "regions": [...] }`。
 *      本文件两种都容错解析（`parseRegions`），其它形状一律当空列表。
 *   2. **拿不到时降级用 `DEFAULT_REGIONS`，不白屏**：地区是注册必填项，接口挂了也必须能注册；
 *      默认列表是本地常量（伊犁州 11 个直辖县市 + 其他），与后端种子保持一致。
 *   3. 地区名是**中文专有名词，不翻译**：原样展示，不进 i18n 资源；标签与提示才走 `t()`。
 *   4. 只在服务端列表非空时采用它；空数组视为「后端没配」，退回默认列表。
 */

import { get } from '@/shared/api/client';
import type { ApiResult } from '@/shared/api/types';

/**
 * 内置默认地区（伊犁哈萨克自治州直辖的 11 个县市 + 「其他」）。
 *
 * 顺序按行政级别与常见程度排：两个地级市（伊宁、奎屯）、霍尔果斯市、伊宁县，再各县。
 * 后端接口正常时以服务端列表为准，这份常量只是兜底。
 */
export const DEFAULT_REGIONS: readonly string[] = [
  '伊宁市',
  '奎屯市',
  '霍尔果斯市',
  '伊宁县',
  '察布查尔锡伯自治县',
  '霍城县',
  '巩留县',
  '新源县',
  '昭苏县',
  '特克斯县',
  '尼勒克县',
  '其他',
];

/**
 * 把一个 `unknown` 值解析成地区字符串数组。
 *
 * 兼容两种形状（见文件头注释第 1 条）；元素必须是字符串且去空白后非空。
 *
 * @param raw 接口返回的 `data`（形状未知）
 * @returns 地区数组（可能为空）
 */
function parseRegions(raw: unknown): string[] {
  let list: unknown[] = [];
  if (Array.isArray(raw)) {
    list = raw;
  } else if (typeof raw === 'object' && raw !== null) {
    const wrapped = raw as { regions?: unknown };
    if (Array.isArray(wrapped.regions)) {
      list = wrapped.regions;
    }
  }
  const result: string[] = [];
  for (const item of list) {
    if (typeof item !== 'string') {
      continue;
    }
    const name = item.trim();
    if (name !== '') {
      result.push(name);
    }
  }
  return result;
}

/**
 * 拉取可选地区列表。
 *
 * 失败/空列表时不返回错误让页面降级——调用方直接用 `DEFAULT_REGIONS` 即可，
 * 所以本函数把「解析后为空」也归一成 `ok: true` + 空数组，由页面决定兜底。
 *
 * @returns 成功时 `data` 为地区数组（可能为空数组）
 */
export async function getRegions(): Promise<ApiResult<string[]>> {
  const result = await get<unknown>('/api/v1/pub/regions', { auth: false });
  if (!result.ok) {
    return { ok: false, error: result.error };
  }
  return { ok: true, data: parseRegions(result.data) };
}
