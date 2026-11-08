/**
 * 起诉状表单页（段 4 E2）。
 *
 * 职责：按 `LAWSUIT-*` 模板的 `field_schema` 渲染起诉状表单——多原告 / 多被告 / 诉讼请求 /
 *       事实与理由全部由 schema 驱动，不写死字段；5 秒防抖自动存草稿、离开页面立即存一次、
 *       点「立即生成」投递后跳预览页。
 * 调用方：路由壳 `app/doc/lawsuit.tsx`；入口是文书模板列表页的卡片
 *        （`/doc/lawsuit?templateId=`）与预览页「返回表单修改」（`/doc/lawsuit?documentId=`）。
 * 数据来源：`DocumentFormScreen`（`features/document/components/DynamicForm.tsx`）→
 *          `features/document/api/client.ts`（`fetchTemplates` / `fetchDocument` /
 *          `saveDraft` / `generateDocument`）。
 * 注意：
 *   1. **表单本体只有一份实现**（`DynamicForm.tsx`）；本页只传文案与 testID 前缀，
 *      E3（`DocFormPage`）复用同一套，不许复制第二份表单代码。
 *   2. 带 `documentId` 进来时（预览页返回修改）顶部会显示
 *      「修改后需重新生成，预览才会更新」，由共用页壳渲染。
 *   3. 文案全部走 `t()`；视觉数值在共用组件内取 `tokens`。
 *
 * testID 清单（前缀 `lawsuit-`，全部真实挂载在 `DocumentFormScreen` / `DynamicForm` 里）：
 *   lawsuit-screen / lawsuit-loading / lawsuit-error / lawsuit-retry /
 *   lawsuit-empty / lawsuit-back-templates / lawsuit-form / lawsuit-title / lawsuit-notice /
 *   lawsuit-regenerate-hint / lawsuit-draft-state / lawsuit-save-draft / lawsuit-generate /
 *   lawsuit-submit-error / lawsuit-required-hint / lawsuit-scroll /
 *   lawsuit-field-{key} / lawsuit-input-{key} / lawsuit-field-{key}-error /
 *   lawsuit-add-{key} / lawsuit-remove-{key}-{index} / lawsuit-array-{key}
 */

import { useMemo } from 'react';

import { DocumentFormScreen } from '@/features/document/components/DynamicForm';
import type { DocumentFormConfig } from '@/features/document/components/DynamicForm';
import { useI18n } from '@/shared/i18n';

/**
 * 起诉状表单页。
 *
 * 副作用：无（数据加载、自动保存、生成跳转都在共用页壳里）。
 *
 * @returns 起诉状表单页
 */
export function DocLawsuitFormPage() {
  const { t } = useI18n();
  const config = useMemo<DocumentFormConfig>(
    () => ({
      navbarTitle: t('document.lawsuit.title'),
      pageSub: t('document.lawsuit.pageSub'),
      notice: t('document.lawsuit.notice'),
      generateLabel: t('document.form.generateNow'),
      testIDPrefix: 'lawsuit',
    }),
    [t],
  );
  return <DocumentFormScreen config={config} />;
}
