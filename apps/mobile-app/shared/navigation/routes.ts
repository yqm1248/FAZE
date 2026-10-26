/**
 * 路由常量表。
 *
 * 页面导航一律从这里取路径，禁止在页面里直接写路径字符串。
 * 理由（沿用 zxz-bi 把"金额口径/键前缀"集中定义的做法）：
 * 路由改名时只需改一处，`Href` 类型能在构建期发现失效路径。
 *
 * 路径分配说明（重要）：
 * - `/` 保留给启动页（SplashPage）。原项目 `pages.json` 的第一项就是 `pages/auth/splash`。
 * - 首页 tab 因此不能占用 `/`，改为 `/home`。这是与原项目唯一的路径差异，
 *   原因是 expo-router 中 `app/index.tsx` 与 `app/(tabs)/index.tsx` 都会解析到 `/`，
 *   两者不能共存。tab 的顺序不变，仅首页的文件名为 `home.tsx`。
 */

import type { Href } from 'expo-router';

/**
 * 底部 5 个 tab 的路由。
 *
 * `(tabs)` 是路由分组，不出现在 URL 中，所以文件名即路径段。
 */
export const TAB_ROUTES = {
  /** 首页。文件为 `app/(tabs)/home.tsx`。 */
  home: '/home' as Href,
  /** 找律师。 */
  lawyer: '/lawyer' as Href,
  /** 智能体。 */
  agent: '/agent' as Href,
  /** 案件中心。 */
  caseList: '/case' as Href,
  /** 个人中心。 */
  profile: '/profile' as Href,
} as const;

/**
 * 二级页面路由与认证页路由。
 *
 * `lawyerDetail` 是函数而非常量：律师姓名是路由参数，
 * 原项目通过 `/pages/lawyer/detail?name=张领` 传参，此处保持同样的参数名 `name`。
 */
export const PAGE_ROUTES = {
  /** 启动页。 */
  splash: '/' as Href,
  /** 登录页。 */
  login: '/login' as Href,
  /** 注册页。 */
  register: '/register' as Href,
  /** 律师 / 学生入驻。 */
  lawyerJoin: '/lawyer/join' as Href,
  /** 案件进度查询。 */
  progress: '/progress' as Href,
  /** 起诉状生成。 */
  lawsuitForm: '/lawsuit-form' as Href,
} as const;

/**
 * 生成律师详情页路由。
 *
 * @param name 律师姓名（不含"律师"后缀），例如 `张领`
 * @returns 带 `name` 查询参数的详情页路由
 */
export function lawyerDetailRoute(name: string): Href {
  return { pathname: '/lawyer/detail', params: { name } } as Href;
}

/** tab 与中文名的对照，供底部导航渲染使用。 */
export const TAB_LABELS = {
  home: '首页',
  lawyer: '律师',
  agent: '智能体',
  caseList: '案件',
  profile: '我的',
} as const;

export type TabKey = keyof typeof TAB_ROUTES;
