import type { ImageSourcePropType } from 'react-native';

import { LEGACY_IMAGES } from '@/assets/legacy';

/**
 * 律师域类型与假数据。
 *
 * 数据合并说明（这是复刻时修正的一处原项目缺陷）：
 * 原项目把律师信息写了两份——列表页 `pages/lawyer/lawyer.vue:74-115` 与详情页
 * `pages/lawyer/detail.vue:62-103`，且同一个 `score` 字段在列表页是 5 分制评分、
 * 在详情页是百分制好评率，含义冲突。此处拆成 `rating` 与 `approvalRate` 两个字段，
 * 数据只维护一份。
 */

/** 律师筛选分类。第一项「全部」不是真实分类，仅作筛选开关。 */
export const LAWYER_CATEGORIES = [
  '全部',
  '民事纠纷',
  '刑事辩护',
  '婚姻家事',
  '合同纠纷',
] as const;

export type LawyerCategory = (typeof LAWYER_CATEGORIES)[number];

/** 列表页展示一位律师所需的最小字段集。 */
export type LawyerListItem = {
  /** 稳定标识，用姓名拼音而非数组下标。 */
  id: string;
  /** 展示用全名，带「律师」后缀，例如「张领律师」。 */
  displayName: string;
  /** 详情的路由参数：去掉「律师」后缀的姓名。 */
  routeName: string;
  /** 执业年限，展示为「执业N年」。 */
  years: string;
  /** 所属律所。 */
  firm: string;
  /** 头像，沿用原项目竖版人像图。 */
  avatar: ImageSourcePropType;
  /** 擅长领域标签，列表页最多显示 2 个。 */
  expertise: string[];
  /** 评分，5 分制，例如 `5.0`。对应原列表页的 `score`。 */
  rating: string;
  /** 咨询量展示文案，例如 `2k+`。 */
  consults: string;
  /** 所属分类，用于筛选。 */
  category: Exclude<LawyerCategory, '全部'>;
  /** 是否在线，决定头像底部状态带是否显示。 */
  online: boolean;
};

/** 详情页在列表字段之上追加的字段。 */
export type LawyerDetail = LawyerListItem & {
  /** 好评率，百分制字符串，例如 `98%`。对应原详情页误用 `score` 的那个字段。 */
  approvalRate: string;
  /** 个人简介长文本。 */
  intro: string;
};

/**
 * 律师假数据。
 *
 * 来源：原项目 `lawyer.vue`（列表）与 `detail.vue`（详情）两份数据合并。
 * 擅长领域取详情页的更完整版本。
 */
export const LAWYERS: LawyerDetail[] = [
  {
    id: 'zhangling',
    displayName: '张领律师',
    routeName: '张领',
    years: '15',
    firm: '法泽律师事务所',
    avatar: LEGACY_IMAGES.lawyerPortrait1,
    expertise: ['民商事纠纷', '刑事辩护', '合同审查', '企业法律顾问'],
    rating: '5.0',
    approvalRate: '98%',
    consults: '2k+',
    category: '民事纠纷',
    online: true,
    intro:
      '张领律师毕业于知名法学院，拥有超过15年的执业经验。长期致力于民商事诉讼与仲裁，深谙法庭审理逻辑。',
  },
  {
    id: 'lijianhua',
    displayName: '李建华律师',
    routeName: '李建华',
    years: '12',
    firm: '盈科律师事务所',
    avatar: LEGACY_IMAGES.lawyerPortrait2,
    expertise: ['刑事辩护', '经济犯罪', '刑事诉讼'],
    rating: '4.9',
    approvalRate: '99%',
    consults: '3.2k',
    category: '刑事辩护',
    online: true,
    intro:
      '李建华律师深耕刑事领域多年，擅长复杂经济犯罪辩护，以严谨的逻辑和精准的切入点著称。',
  },
  {
    id: 'wangfang',
    displayName: '王芳律师',
    routeName: '王芳',
    years: '5',
    firm: '君合律师事务所',
    avatar: LEGACY_IMAGES.lawyerPortrait3,
    expertise: ['合同纠纷', '债权债务', '知识产权'],
    rating: '4.8',
    approvalRate: '97%',
    consults: '850',
    category: '合同纠纷',
    online: true,
    intro:
      '王芳律师致力于企业商务法律服务，处理过大量复杂的合同纠纷案件，工作风格细腻且高效。',
  },
  {
    id: 'zhaozihan',
    displayName: '赵子涵律师',
    routeName: '赵子涵',
    years: '15',
    firm: '金杜律师事务所',
    avatar: LEGACY_IMAGES.lawyerPortrait4,
    expertise: ['民事纠纷', '侵权责任', '房产纠纷'],
    rating: '5.0',
    approvalRate: '100%',
    consults: '4.5k',
    category: '民事纠纷',
    online: true,
    intro:
      '赵子涵律师在民事审判领域积累了极高的声望，擅长处理大型民事侵权案件，深受当事人信赖。',
  },
];

/**
 * 按筛选分类过滤律师。
 *
 * @param category 选中的分类；传「全部」返回全部律师
 * @returns 过滤后的律师列表（保持原始顺序）
 */
export function filterLawyersByCategory(category: LawyerCategory): LawyerDetail[] {
  if (category === '全部') {
    return LAWYERS;
  }
  return LAWYERS.filter((lawyer) => lawyer.category === category);
}

/**
 * 按路由姓名查找律师。
 *
 * @param routeName 去掉「律师」后缀的姓名
 * @returns 匹配的律师；未命中时返回第一位律师作为兜底（与原详情页默认值行为一致）
 */
export function findLawyerByRouteName(routeName: string | undefined): LawyerDetail {
  const matched = LAWYERS.find((lawyer) => lawyer.routeName === routeName);
  // 原详情页 `currentLawyer` 初值为「张领」，参数不匹配时保持该初值。
  return matched ?? (LAWYERS[0] as LawyerDetail);
}
