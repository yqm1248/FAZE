/**
 * 案件中心数据类型与假数据。
 *
 * 来源：原项目 `pages/case/case.vue:25-67` 的三组案例列表。
 * 原实现把 10 条案例逐条硬编码在模板里，此处抽成数据结构 + 分组渲染，
 * 文案一字未改。
 */

/** 一条案例（目前只有标题，原项目也没有详情页）。 */
export type CaseItem = {
  id: string;
  /** 案例标题，即列表上展示的一行文字。 */
  title: string;
};

/** 一个案例分组。 */
export type CaseGroup = {
  /** 分组标识，用于 key。 */
  id: string;
  /** 分组标题，例如「民事纠纷」。 */
  title: string;
  items: CaseItem[];
};

/** 案件中心页头部文案。 */
export const CASE_BANNER_TITLE = '每日热点案例';

/** 搜索框占位文案。 */
export const CASE_SEARCH_PLACEHOLDER = '请输入关键词';

/** 三组案例。顺序与原页面一致：民事 → 行政 → 刑事。 */
export const CASE_GROUPS: CaseGroup[] = [
  {
    id: 'civil',
    title: '民事纠纷',
    items: [
      { id: 'civil-1', title: '交通事故后一直享有单位工资，还能主张误工费吗？' },
      { id: 'civil-2', title: '搭车途中出事故，车主如何担责？' },
      { id: 'civil-3', title: '年终奖发与不发谁说了算？' },
      { id: 'civil-4', title: '劳务合同与劳动合同的区别要注意了！' },
    ],
  },
  {
    id: 'administrative',
    title: '行政纠纷',
    items: [
      { id: 'admin-1', title: '"告官"能"见官"出庭还是出声' },
      { id: 'admin-2', title: '"面对面"解烦，"心连心"无忧' },
      { id: 'admin-3', title: '因工负伤无处认定怎么办' },
      { id: 'admin-4', title: '向虚假诉讼说不' },
    ],
  },
  {
    id: 'criminal',
    title: '刑事纠纷',
    items: [
      { id: 'criminal-1', title: '莫让情亲变成枷锁' },
      { id: 'criminal-2', title: '网络诈骗新手段揭秘：兼职刷单陷阱' },
    ],
  },
];
