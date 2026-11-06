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
 * 段 0 的 5 条 + 段 1 G 组的 17 条（18 页里启动页复用 `root`）。
 */
export const PAGE_ROUTES = {
  /** 启动页：品牌渐变 + 读持久化登录态后分流到「我的」或登录页。 */
  root: '/' as Href,
  /** 登录页（C1 实现）。 */
  login: '/login' as Href,
  /** 语言切换页（C2 实现）。 */
  language: '/language' as Href,
  /** 探针页：ping / AI 流 / law 检索（D3）。 */
  devProbe: '/dev/probe' as Href,
  /** 探针页：RTL 镜像 / 藏文渲染 / 静态 Tab 样本（B3 + D2）。 */
  devRtl: '/dev/rtl' as Href,

  // ── 段 1 G1：启动与认证（启动页复用 root）────────────────────────────
  /** 注册页（手机号 + 验证码 + 密码 + 协议 + 未成年人监护人）。 */
  register: '/register' as Href,
  /** 忘记密码页。 */
  forgotPassword: '/forgot-password' as Href,
  /** 用户服务协议页（正文读服务端配置）。 */
  agreement: '/agreement' as Href,
  /** 隐私政策页（正文读服务端配置）。 */
  privacy: '/privacy' as Href,

  // ── 段 1 G4：余额与支付密码 ─────────────────────────────────────────
  /** 账户余额与明细页。 */
  balance: '/balance' as Href,
  /** 支付密码页（设置 / 修改 / 重置 / 校验 + 锁定倒计时）。 */
  payPassword: '/pay-password' as Href,

  // ── 段 1 G2：律师入驻 + 我的 ────────────────────────────────────────
  /** 律师入驻页（四步表单，可存草稿）。 */
  lawyerJoin: '/lawyer-join' as Href,
  /** 入驻审核状态页。 */
  lawyerJoinStatus: '/lawyer-join-status' as Href,
  /** 我的页。 */
  profile: '/profile' as Href,

  // ── 段 1 G3：设置与其他 8 页 ────────────────────────────────────────
  /** 账号与安全页。 */
  accountSecurity: '/account-security' as Href,
  /** 修改登录密码页。 */
  changePassword: '/change-password' as Href,
  /** 换绑手机页。 */
  changePhone: '/change-phone' as Href,
  /** 注销账号页（立即生效口径）。 */
  deactivate: '/deactivate' as Href,
  /** 实名认证页（上传 → 提交 → 状态）。 */
  verifyRealname: '/verify-realname' as Href,
  /** 个人资料编辑页。 */
  editProfile: '/edit-profile' as Href,
  /** 通知设置页。 */
  notifySettings: '/notify-settings' as Href,
  /** 隐私设置页。 */
  privacySettings: '/privacy-settings' as Href,

  // ── 段 2 G2：交易（订单 / 支付）──────────────────────────────────────
  /** 我的订单页（段 2 G2.1）。 */
  orders: '/orders' as Href,
  /** 订单详情页（段 2 G2.1），需带 `id` 参数。 */
  orderDetail: '/order-detail' as Href,
  /** 支付页（段 2 G2.3），需带 `id` 参数。 */
  pay: '/pay' as Href,
  /** 支付结果页（段 2 G2.3），需带 `id` 与 `ok` 参数。 */
  payResult: '/pay-result' as Href,
  /** 退款申请页（段 2 G4.1），需带 `id` 参数。 */
  refund: '/refund' as Href,
  /** 评价页（段 2 G5.1），需带 `id` 参数。 */
  review: '/review' as Href,

  // ── 段 2 G3：律师浏览与律师侧 ───────────────────────────────────────
  /** 找律师页（段 2 G3.1）。 */
  lawyerList: '/lawyer-list' as Href,
  /** 律师详情页（段 2 G3.2），需带 `id` 参数。 */
  lawyerDetail: '/lawyer-detail' as Href,
  /** 律师订单管理页（段 2 G3.3）。 */
  lawyerOrders: '/lawyer-orders' as Href,
  /** 律师工作台（段 2 G3.4）。 */
  lawyerWorkbench: '/lawyer-workbench' as Href,
} as const;

/** 路由常量表的值类型（用于接收「任一页面路径」的参数）。 */
export type PageRoute = (typeof PAGE_ROUTES)[keyof typeof PAGE_ROUTES];

/**
 * 拼订单详情路由（带 `id` 查询参数）。
 *
 * 为什么不直接在页面里写模板串：路由拼装集中在本文件，改路径时不用全仓搜字符串；
 * 也避免页面里出现 `'/order-detail?id='` 这样的裸路径（routes.ts 的既有约定）。
 *
 * @param orderId 订单主键
 * @returns 可传给 `router.push` 的 Href
 */
export function orderDetailHref(orderId: string): Href {
  return `${String(PAGE_ROUTES.orderDetail)}?id=${encodeURIComponent(orderId)}` as Href;
}

/**
 * 拼支付页路由（带 `id` 查询参数）。
 *
 * @param orderId 订单主键
 * @returns 可传给 `router.push` 的 Href
 */
export function payHref(orderId: string): Href {
  return `${String(PAGE_ROUTES.pay)}?id=${encodeURIComponent(orderId)}` as Href;
}

/**
 * 拼支付结果页路由。
 *
 * 失败原因**传 i18n key 而不是中文**：中文里传中文会让 RTL 语言页面显示中文，
 * 而且以后改文案要改两处。`reason` 缺省时按「未知原因」处理。
 *
 * @param orderId 订单主键
 * @param success 是否支付成功
 * @param reason  失败原因的 i18n key（如 `trade.payResult.reasonBalance`）
 * @returns 可传给 `router.replace` 的 Href
 */
export function payResultHref(orderId: string, success: boolean, reason?: string): Href {
  const params = new URLSearchParams({ id: orderId, ok: success ? '1' : '0' });
  if (reason !== undefined) {
    params.set('reason', reason);
  }
  return `${String(PAGE_ROUTES.payResult)}?${params.toString()}` as Href;
}

/**
 * 拼律师详情路由（带 `id` 查询参数）。
 *
 * @param lawyerId 律师主键
 * @returns 可传给 `router.push` 的 Href
 */
export function lawyerDetailHref(lawyerId: string): Href {
  return `${String(PAGE_ROUTES.lawyerDetail)}?id=${encodeURIComponent(lawyerId)}` as Href;
}

/**
 * 拼退款申请页路由（带 `id` 查询参数）。
 *
 * @param orderId 订单主键
 * @returns 可传给 `router.push` 的 Href
 */
export function refundHref(orderId: string): Href {
  return `${String(PAGE_ROUTES.refund)}?id=${encodeURIComponent(orderId)}` as Href;
}

/**
 * 拼评价页路由（带 `id` 查询参数）。
 *
 * @param orderId 订单主键
 * @returns 可传给 `router.push` 的 Href
 */
export function reviewHref(orderId: string): Href {
  return `${String(PAGE_ROUTES.review)}?id=${encodeURIComponent(orderId)}` as Href;
}
