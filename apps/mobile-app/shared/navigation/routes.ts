/**
 * 路由常量表。
 *
 * 页面导航一律从这里取路径，禁止在页面里直接写路径字符串。
 * 理由：路由改名时只需改一处，`Href` 类型能在构建期发现失效路径。
 *
 * 当前状态（段 0 B1，2026-11-03）：
 * UI 复刻原型（12 个页面 + 5 个假数据）已在 `9d589d3` 裁剪、归档到只读分支 `ui-reference`。
 * 本文件只保留**实际存在的路由**——`as Href` 断言在 typedRoutes 下指向不存在的路由会
 * 直接报 TS2352，所以不能预先写还没建的路由。
 *
 * **68 页的路由常量随段生长，段 0 一条都不预置**（总纲 §4.4 规则 3）。
 */

import type { Href } from 'expo-router';

/**
 * 段 0 的路由。
 *
 * 共 5 条常量（`+not-found` 是兜底文件、不作为跳转目标，故不设常量）。
 */
export const PAGE_ROUTES = {
  /** 启动分流器：读持久化的语言与登录态后决定去哪。 */
  root: '/' as Href,
  /** 登录页（C1 实现，B1 阶段为空壳）。 */
  login: '/login' as Href,
  /** 语言切换页（C2 实现，B1 阶段为空壳）。 */
  language: '/language' as Href,
  /** 探针页：ping / AI 流 / law 检索（D3）。 */
  devProbe: '/dev/probe' as Href,
  /** 探针页：RTL 镜像 / 藏文渲染 / 静态 Tab 样本（B3 + D2）。 */
  devRtl: '/dev/rtl' as Href,
} as const;

/** 路由常量表的值类型（用于接收「任一页面路径」的参数）。 */
export type PageRoute = (typeof PAGE_ROUTES)[keyof typeof PAGE_ROUTES];
