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
  /** 充值页（第 6 段 B2；入口在余额页与支付页「余额不足」）。 */
  recharge: '/recharge' as Href,
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
  /** 一对一聊天页（段 2 G6.3），需带 `lawyerId` 参数。 */
  chat: '/chat' as Href,

  // ── 段 2 G8：内容与消息中心 ──────────────────────────────────────────
  /** 首页（段 2 G8.2）。 */
  home: '/home' as Href,
  /** 文章列表页（段 2 G8.2）。 */
  articles: '/articles' as Href,
  /** 文章详情页（段 2 G8.2），需带 `id` 参数。 */
  article: '/article' as Href,
  /** 消息中心（段 2 G8.3，空态）。 */
  messages: '/messages' as Href,
  /** 消息详情（段 2 G8.3，占位）。 */
  messageDetail: '/message-detail' as Href,

  // ── 段 2 G3：律师浏览与律师侧 ───────────────────────────────────────
  /** 找律师页（段 2 G3.1）。 */
  lawyerList: '/lawyer-list' as Href,
  /** 律师详情页（段 2 G3.2），需带 `id` 参数。 */
  lawyerDetail: '/lawyer-detail' as Href,
  /** 律师订单管理页（段 2 G3.3）。 */
  lawyerOrders: '/lawyer-orders' as Href,
  /** 律师工作台（段 2 G3.4）。 */
  lawyerWorkbench: '/lawyer-workbench' as Href,

  // ── 段 3 F1：底部 tab 内的两条新路由 ────────────────────────────────
  /** 智能体 tab（AI 对话页，段 3 F1/F2；对应 `(tabs)/agent.tsx`）。 */
  agent: '/agent' as Href,
  /** 案件 tab（段 3 F1 的占位空态；段 5 收口时替换为真实案件列表）。 */
  case: '/case' as Href,
  /** 律师 tab（`(tabs)/lawyer.tsx`；F3 的「转人工律师」跳这里，工作流 F3 第 6 条）。 */
  lawyerTab: '/lawyer' as Href,
  /** 对话历史页（段 3 G1；二级页，入口在 AI 对话页顶栏）。 */
  aiHistory: '/ai-history' as Href,

  // ── 段 5 B 组：案件中心四条路由 ─────────────────────────────────────
  /** 在线立案四步表单（`/case-filing`，段 5 B1）。 */
  caseFiling: '/case-filing' as Href,
  /** 材料已提交页（`/case-filing-done?caseNo=`，段 5 B2）。 */
  caseFilingDone: '/case-filing-done' as Href,
  /** 我的案件列表（`/case-my`，段 5 B3）。 */
  caseMy: '/case-my' as Href,
  /** 案件进度页（`/case-progress?caseId=`，段 5 B3/B4）。 */
  caseProgress: '/case-progress' as Href,
  /** 文书送达页（`/case-delivery?caseId=`，段 5 C2/C3/C4）。 */
  caseDelivery: '/case-delivery' as Href,

  // ── 段 5 D 组：律师工作台五页 ───────────────────────────────────────
  /** 客户咨询列表（`/workbench-consultations`，段 5 D1）。 */
  workbenchConsultations: '/workbench-consultations' as Href,
  /** 律师文书管理（`/workbench-docs`，段 5 D2）。 */
  workbenchDocs: '/workbench-docs' as Href,
  /** 律师收入与提现（`/workbench-income`，段 5 D3）。 */
  workbenchIncome: '/workbench-income' as Href,
  /** 服务设置（`/workbench-settings`，段 5 D4）。 */
  workbenchSettings: '/workbench-settings' as Href,
  /** 律师资料编辑（`/workbench-profile`，段 5 D5）。 */
  workbenchProfile: '/workbench-profile' as Href,
  /** 文书模板列表（段 4 E1 的页面，D2 的「新建文书」入口跳这里）。 */
  docTemplates: '/doc/templates' as Href,
  /** 我的文书（段 4 E1 的 `/doc/mine`；「全部功能」页「我的文书」入口）。 */
  docMine: '/doc/mine' as Href,

  // ── 段 7 C5：客服 / FAQ / 投诉 / 反馈（4 页）────────────────────────
  /** 客服中心（`/support`，APP 55）。 */
  support: '/support' as Href,
  /** 常见问题（`/faq`，APP 56）。 */
  faq: '/faq' as Href,
  /** 投诉（`/complaint`，APP 57）。 */
  complaint: '/complaint' as Href,
  /** 意见反馈（`/feedback`，APP 58）。 */
  feedback: '/feedback' as Href,

  // ── 段 7 E 组：设置 / 关于 / 全部功能 / 企业认证 / 收藏 / 关注 ─────────
  /** 设置页（`/settings`，APP 41）。 */
  settings: '/settings' as Href,
  /** 关于法泽（`/about`，APP 59）。 */
  about: '/about' as Href,
  /** 全部功能（`/all-features`，APP 61）。 */
  allFeatures: '/all-features' as Href,
  /** 企业认证（`/verify-company`，APP 49）。 */
  verifyCompany: '/verify-company' as Href,
  /** 我的收藏（`/favorites`，APP 51）。 */
  favorites: '/favorites' as Href,
  /** 关注律师（`/following`，APP 52）。 */
  following: '/following' as Href,
  /** 我的投诉（`/complaint-list`，段 7 新增第 69 页）。 */
  complaintList: '/complaint-list' as Href,
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

/**
 * 拼聊天页路由（带 `lawyerId` 查询参数）。
 *
 * 为什么传律师 id 而不是会话 id：入口（订单详情「联系律师」）手上只有 lawyerId，
 * 而 `POST /app/conversations` 是「创建或获取」——由聊天页统一取会话，入口不用先建一遍。
 *
 * @param lawyerId 律师主键
 * @returns 可传给 `router.push` 的 Href
 */
export function chatHref(lawyerId: string): Href {
  return `${String(PAGE_ROUTES.chat)}?lawyerId=${encodeURIComponent(lawyerId)}` as Href;
}

/**
 * 拼聊天页路由（带 `conversationId` 查询参数）。
 *
 * 推送点击直达（G7.3）手里只有 `conversationId`：聊天页据此走 `GET /app/conversations/{id}`
 * 拿对端信息并拉历史，不再走「按律师建会话」。
 *
 * @param conversationId 会话主键
 * @returns 可传给 `router.push` 的 Href
 */
export function chatByConversationHref(conversationId: string): Href {
  return `${String(PAGE_ROUTES.chat)}?conversationId=${encodeURIComponent(conversationId)}` as Href;
}

/**
 * 拼「回看某个历史会话」的 AI 对话页路由（带 `conversationId` 查询参数，段 3 G1）。
 *
 * 为什么用分组形式 `/(tabs)/agent`：expo-router 的 `(tabs)` 是 route group，不占 URL 段，
 * `/(tabs)/agent` 与 `/agent` 同屏；这里按工作流 G1 第 5 条的约定写分组形式，
 * F2 侧用 `useLocalSearchParams<{ conversationId?: string }>()` 读取即可（两种形式都能读到）。
 *
 * @param conversationId 会话主键（字符串形式的 Long）
 * @returns 可传给 `router.push` 的 Href
 */
export function aiChatConversationHref(conversationId: string): Href {
  return `/(tabs)/agent?conversationId=${encodeURIComponent(conversationId)}` as Href;
}

/**
 * 拼文章列表路由。
 *
 * @param category 分类（可空；空表示全部）
 * @returns 可传给 `router.push` 的 Href
 */
export function articlesHref(category?: string): Href {
  if (category === undefined || category === '') {
    return PAGE_ROUTES.articles;
  }
  return `${String(PAGE_ROUTES.articles)}?category=${encodeURIComponent(category)}` as Href;
}

/**
 * 拼文章详情路由（带 `id` 查询参数）。
 *
 * @param articleId 文章主键
 * @returns 可传给 `router.push` 的 Href
 */
export function articleHref(articleId: string): Href {
  return `${String(PAGE_ROUTES.article)}?id=${encodeURIComponent(articleId)}` as Href;
}

/**
 * 拼意见反馈路由（段 7 C5）。
 *
 * 为什么要带 `from`：从 55 客服中心「留言」进来时，反馈页要把预选类型改成「其他」
 * 并换一段引导文案（工作流 §零之二 第 7 条「非时段切留言形态」）。
 * 用查询参数而不是新建一条 `/support-message` 路由，是因为页面本身完全一样，
 * 只是初始值与文案不同——多一条路由会让「反馈」在两处维护。
 *
 * @param from 来源标记；`support` 表示从客服中心来
 * @returns 可传给 `router.push` 的 Href
 */
export function feedbackHref(from?: 'support'): Href {
  if (from === undefined) {
    return PAGE_ROUTES.feedback;
  }
  return `${String(PAGE_ROUTES.feedback)}?from=${encodeURIComponent(from)}` as Href;
}
