import type { ImageSourcePropType } from 'react-native';

import { LEGACY_IMAGES } from '@/assets/legacy';

/**
 * 首页数据类型。
 *
 * 字段命名与原项目 `pages/index/index.vue` 的渲染项一一对应，
 * 便于对照原文件核对文案与顺序。
 */

/** 首页三宫格功能入口。 */
export type HomeFunctionEntry = {
  /** 稳定标识，用于点击分发，不使用数组下标。 */
  key: 'document' | 'lawyer' | 'progress';
  /** 入口文字。 */
  label: string;
  /** 入口图标，沿用原项目 PNG。 */
  icon: ImageSourcePropType;
};

/** 每日学法列表项。 */
export type HomeLawItem = {
  id: string;
  /** 文章标题。 */
  title: string;
  /** 发布日期文案，原项目为「2026年2月1号」这种中文格式。 */
  dateLabel: string;
  /** 浏览量。 */
  views: number;
};

/** 首页整体展示数据。 */
export type HomeContent = {
  /** 问候语。原项目固定为「下午好！」，未按时间段动态生成。 */
  greeting: string;
  /** 主标语。 */
  slogan: string;
  /** 副标语。 */
  subSlogan: string;
  /** AI 解答区块标题。 */
  aiTitle: string;
  /** 模拟搜索框占位文案。 */
  searchPlaceholder: string;
  /** 三宫格入口。 */
  functions: HomeFunctionEntry[];
  /** 每日学法区块标题与「更多」文案。 */
  dailyLawTitle: string;
  moreLabel: string;
  /** 每日学法列表。 */
  laws: HomeLawItem[];
};

/**
 * 首页假数据。
 *
 * 来源：原项目 `pages/index/index.vue:76-92`（三条学法文章）与 `locale/zh-Hans.json`（其余文案）。
 * 接入接口后整体替换本文件，页面结构不变。
 */
export const HOME_CONTENT: HomeContent = {
  greeting: '下午好！',
  slogan: '站在你的角度',
  subSlogan: '为你争取和维护最大的权益',
  aiTitle: 'AI 解答',
  searchPlaceholder: '请输入关键词',
  functions: [
    { key: 'document', label: '生成起诉状', icon: LEGACY_IMAGES.iconDocument },
    { key: 'lawyer', label: '律师咨询', icon: LEGACY_IMAGES.iconGroup },
    { key: 'progress', label: '查询进程', icon: LEGACY_IMAGES.iconHistory },
  ],
  dailyLawTitle: '每日学法',
  moreLabel: '更多',
  laws: [
    {
      id: 'law-1',
      title: '未约定借款期限和借款利息，可以主张逾期利息吗？',
      dateLabel: '2026年2月1号',
      views: 236,
    },
    {
      id: 'law-2',
      title: '注意！劳务合同与劳动合同的区别，签错了损失巨大。',
      dateLabel: '2026年2月3号',
      views: 560,
    },
    {
      id: 'law-3',
      title: '【干货】起诉状怎么写？掌握这五个要素就够了。',
      dateLabel: '2026年2月5号',
      views: 892,
    },
  ],
};
