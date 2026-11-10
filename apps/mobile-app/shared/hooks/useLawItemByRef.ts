/**
 * 按主键回查法条原文（含全文）。
 *
 * 职责：把「`law_refs` 快照里的 `lawItemId` → 法条正文」这件事收敛成唯一入口。
 * 调用方：`features/content/pages/CaseDetailPage.tsx`（案例详情「相关法条」点击）、
 *         以后任何要展示引用法条原文的页面。
 * 数据来源：`GET /api/v1/pub/law-items/{id}`（2026-11-11 新增；此前 App 端只有列表接口）。
 * 注意：
 *   1. **不许用关键词搜索代替**。旧实现是 `searchLawItems(\`${lawName} ${articleNo}\`)` 再
 *      `find(...) ?? list[0]` 兜底——检索结果一变就显示另一条法条，那是**内容错误**。
 *      ESLint 规则 `no-search-fallback` 会拦 `?? list[0]` 这类写法，但拦不住「用搜索代替精确查」，
 *      所以这条语境写在这里。
 *   2. 被引用的法条可能是已修正 / 已废止的旧行，接口**故意不过滤**，由页面按 `status` 标注。
 *   3. `lawItemId` 为空时**不发请求**（`enabled: false`），state 停在初始态，调用方不该渲染弹层。
 */

import { getLawItemById, type LawItemDetail } from '@/features/content/api/search';
import { useAsyncResource, type AsyncResource } from '@/shared/hooks/useAsyncResource';

/**
 * 按法条主键取原文。
 *
 * @param lawItemId 法条主键（字符串形式的 Long）；为 null 时不请求
 * @returns 四态 + 重试（`reload`）
 */
export function useLawItemByRef(lawItemId: string | null): AsyncResource<LawItemDetail> {
  return useAsyncResource<LawItemDetail>(
    () => {
      if (lawItemId === null) {
        // 理论到不了（enabled=false），但保留以满足返回类型；调用方看到 loading 也不会渲染。
        return Promise.resolve({ ok: true as const, data: EMPTY_DETAIL });
      }
      return getLawItemById(lawItemId);
    },
    { enabled: lawItemId !== null, deps: [lawItemId] },
  );
}

/** 占位详情：只在 `lawItemId` 为空时出现，不会被渲染。 */
const EMPTY_DETAIL: LawItemDetail = {
  id: '',
  lawCode: null,
  lawName: null,
  articleNo: null,
  content: null,
  status: null,
  effectiveDate: null,
  expireDate: null,
};
