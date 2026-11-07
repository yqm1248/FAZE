/**
 * 其它文书表单页（段 4 E3）。
 *
 * 职责：证据清单 / 劳动合同 / 跨境电商合同 / 借条欠条等非起诉状模板的表单页。
 *       与 E2 **共用同一套动态表单**（`DynamicForm.tsx` 的 `DocumentFormScreen`），
 *       本页只在文案与 testID 前缀上有差异——array 条目结构、textarea、日期多这些
 *       都由模板的 `field_schema` 决定，页面不写任何字段。
 * 调用方：路由壳 `app/doc/form.tsx`；入口是文书模板列表页中非 `LAWSUIT-*` 的卡片
 *        （`/doc/form?templateId=`）与预览页「返回表单修改」（`/doc/form?documentId=`）。
 * 数据来源：同 E2（`DocumentFormScreen` → `features/document/api/client.ts`）。
 * 注意：
 *   1. **不许在本页复制表单代码**：E2/E3 的差异全部在 `DocumentFormConfig` 里，
 *      `git diff` 应只有文案与前缀不同。
 *   2. 日期字段在 Web 下是原生日期选择器（`<input type="date">`），不是手输。
 *   3. 文案全部走 `t()`；视觉数值在共用组件内取 `tokens`。
 *
 * testID 清单（前缀 `form-`，全部真实挂载在 `DocumentFormScreen` / `DynamicForm` 里）：
 *   form-screen / form-loading / form-error / form-retry /
 *   form-empty / form-back-templates / form-form / form-title / form-notice /
 *   form-regenerate-hint / form-draft-state / form-save-draft / form-generate /
 *   form-submit-error / form-required-hint / form-scroll /
 *   form-field-{key} / form-input-{key} / form-field-{key}-error /
 *   form-add-{key} / form-remove-{key}-{index} / form-array-{key}
 */

import { useMemo } from 'react';

import { DocumentFormScreen } from '@/features/document/components/DynamicForm';
import type { DocumentFormConfig } from '@/features/document/components/DynamicForm';
import { useI18n } from '@/shared/i18n';

/**
 * 其它文书表单页。
 *
 * 副作用：无（数据加载、自动保存、生成跳转都在共用页壳里）。
 *
 * @returns 非起诉状模板的表单页
 */
export function DocFormPage() {
  const { t } = useI18n();
  const config = useMemo<DocumentFormConfig>(
    () => ({
      navbarTitle: t('document.form.title'),
      pageSub: t('document.form.pageSub'),
      notice: t('document.form.notice'),
      generateLabel: t('document.form.generate'),
      testIDPrefix: 'form',
    }),
    [t],
  );
  return <DocumentFormScreen config={config} />;
}
