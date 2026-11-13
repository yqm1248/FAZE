/**
 * 内容域分类标签的单一数据源与本地化映射。
 *
 * 职责：1) 定义文章分类档位（筛选条与卡片 tag 共用同一份「中文原值 → 文案 key」）；
 *       2) 把后端返回的分类原值（如 `借款纠纷`）翻成当前语言文案。
 * 调用方：`ArticleListPage`（筛选 chips + 卡片 tag）、`ArticleDetailPage`（标题下 tag）、
 *         `HomePage`（「每日学法」区块每行）。
 * 数据来源：分类原值来自接口（`GET /app/articles` 等）；译文来自 `content.json` 的
 *          `list.categoryAll / categoryLoan / categoryFamily / categoryLabor / categoryContract`。
 *
 * 注意：
 *   1. **为什么要这层映射**：后端存的是中文分类名（`article.category` 是 varchar），
 *      早先三个页面直接把原值上屏，导致维吾尔文 / 哈萨克文 / 藏文界面里
 *      分类 chip 仍显示中文「借款纠纷」——四语译文其实早就写在 `content.json` 里，
 *      只是没人用。**这是代码漏接，不是缺译。**
 *   2. **筛选条与卡片必须同源**：同一个分类在筛选条显示译文、在卡片显示中文，
 *      会让人以为筛错了。所以两处都从本文件的 `ARTICLE_CATEGORIES` 取。
 *   3. **未知分类按原值兜底**：运营在 PC 后台新加的分类在这里查不到时直接显示原值
 *      （宁可是中文，也不要整块消失）。新增分类时记得回这里补一行。
 *   4. 本文件不依赖 React，可在任何层调用；`t` 由调用方传入已绑定语言的翻译函数。
 */

import type { MessageKey } from '@/shared/i18n';

/** 文章分类档位：显示走 i18n，提交给服务端的是固定中文业务值（`value`）。 */
export const ARTICLE_CATEGORIES: ReadonlyArray<{
  /** 前端内部标识（用于 testID 与 React key）。 */
  key: string;
  /** 显示文案的 i18n key。 */
  labelKey: MessageKey;
  /** 提交给服务端的分类值；空串表示「全部」。 */
  value: string;
}> = [
  { key: 'all', labelKey: 'content.list.categoryAll', value: '' },
  { key: 'loan', labelKey: 'content.list.categoryLoan', value: '借款纠纷' },
  { key: 'family', labelKey: 'content.list.categoryFamily', value: '婚姻家事' },
  { key: 'labor', labelKey: 'content.list.categoryLabor', value: '劳动争议' },
  { key: 'contract', labelKey: 'content.list.categoryContract', value: '合同纠纷' },
];

/**
 * 取分类的已翻译显示文案。
 *
 * @param category 分类原值（可为 null）
 * @param t        已绑定当前语言的翻译函数
 * @returns 已翻译文案；`category` 为 null / 空串时返回 null（调用方不渲染）
 *
 * 未知分类返回原值，保证「宁可中文也不消失」。
 */
export function categoryLabel(
  category: string | null,
  t: (key: MessageKey) => string,
): string | null {
  if (category === null || category.trim() === '') {
    return null;
  }
  const matched = ARTICLE_CATEGORIES.find((item) => item.value === category);
  return matched === undefined ? category : t(matched.labelKey);
}
