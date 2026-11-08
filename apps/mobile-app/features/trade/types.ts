/**
 * 交易域共享类型（第 6 段 B2 起）。
 *
 * 职责：充值页与充值记录用到的全部出参/入参类型，供 `api/recharge.ts` 与页面共用。
 * 调用方：`features/trade/api/recharge.ts`、`features/trade/pages/RechargePage.tsx`。
 * 数据来源：对齐服务端 `RechargePanelVO` / `RechargeCreateVO` / `RechargeDetailVO` / `RechargeRowVO`。
 * 注意：
 *   1. **金额口径要分清**：`GET /app/wallet/balance` 的三个余额字段是包装类 `Long`，
 *      服务端会序列化成**字符串**（Java 侧全局 Long→String），所以余额类型是 `string`；
 *      而充值单与面板里的金额是原生 `long`，服务端输出的是**数字**，类型是 `number`。
 *      混用会让 `MoneyText` 拿到 `NaN`——两者都经 `centsFromServer()` 归一后再渲染。
 *   2. **单号、游标是字符串**：`nextCursor` 来自服务端 Long，JSON 里也是字符串，回传时原样带上。
 *   3. **状态不返回中文**：`status` 是机器值，`statusKey` 是 i18n key，页面只渲染后者。
 */

import type { MessageKey } from '@/shared/i18n';

/** 一个可用的支付渠道（对应服务端 `RechargePanelVO.ChannelOption`）。 */
export type RechargeChannelOption = {
  /** 渠道持久化值：wechat / alipay / mock。 */
  code: string;
  /**
   * 渠道名的 i18n key，如 `trade.pay.channelWechat`。
   *
   * 声明成 `MessageKey` 而不是 `string`：服务端只返回约定的渠道键，页面要把它直接喂给
   * `t()`；用 `string` 会在类型检查时卡在「动态 key 不能进 t()」上。
   */
  i18nKey: MessageKey;
};

/**
 * 充值页参数快照（`GET /app/wallet/balance` 的 `recharge` 子对象）。
 *
 * 服务端未填充时为 `null`（其它调用方走的是不带面板的余额查询），页面必须判空。
 */
export type RechargePanel = {
  /** 单笔最低（分）。 */
  minCents: number;
  /** 单笔最高（分）。 */
  maxCents: number;
  /** 当日额度上限（分）。 */
  dailyLimitCents: number;
  /** 当日已占用（分）。 */
  usedDailyCents: number;
  /** 当日剩余（分）。 */
  remainingDailyCents: number;
  /** 当日笔数上限。 */
  dailyCountLimit: number;
  /** 当日已用笔数。 */
  usedDailyCount: number;
  /** 金额档位（分）。 */
  amountOptions: number[];
  /** 当前启用的渠道；未启用的不返回。 */
  channels: RechargeChannelOption[];
  /** 实名状态原值：none / pending / approved / rejected。 */
  realnameStatus: string;
  /** 是否必须先实名（未实名且额度为 0 时为 true）。 */
  realnameRequired: boolean;
};

/** 余额响应（含充值页参数；`recharge` 可能为 null）。 */
export type WalletBalanceWithPanel = {
  /** 可用余额（分，字符串）。 */
  availableCents: string;
  /** 冻结金额（分，字符串）。 */
  frozenCents: string;
  /** 总余额（分，字符串）。 */
  totalCents: string;
  /** 充值页参数。 */
  recharge: RechargePanel | null;
};

/** 充值下单结果（`RechargeCreateVO`）。 */
export type RechargeCreateResult = {
  /** 平台充值单号（RC 开头）。 */
  orderNo: string;
  /** 充值金额（分）。 */
  amountCents: number;
  /** 渠道持久化值。 */
  channel: string;
  /** 渠道 i18n key。 */
  channelKey: string;
  /** 拉起渠道支付的公开参数；幂等命中且缓存缺失时可能是空对象。 */
  payParams: Record<string, string>;
  /** 支付截止时间（UTC）。 */
  expireAt: string;
};

/** 充值单详情（`RechargeDetailVO`）。 */
export type RechargeDetail = {
  /** 平台充值单号。 */
  orderNo: string;
  /** 状态机器值：pending / success / failed / closed。 */
  status: string;
  /** 状态 i18n key。 */
  statusKey: string;
  /** 金额（分）。 */
  amountCents: number;
  /** 渠道持久化值。 */
  channel: string;
  /** 渠道 i18n key。 */
  channelKey: string;
  /** 支付成功时间（UTC）；未成功为 null。 */
  paidAt: string | null;
  /** 下单时间（UTC）。 */
  createdAt: string;
  /** 截止时间（UTC）。 */
  expireAt: string;
  /** 当前可用余额（分）。 */
  balanceAfterCents: number;
};

/** 充值记录行（`RechargeRowVO`）。 */
export type RechargeRow = {
  /** 内部主键，用作游标。 */
  id: string;
  /** 平台充值单号。 */
  orderNo: string;
  /** 金额（分）。 */
  amountCents: number;
  /** 渠道持久化值。 */
  channel: string;
  /** 渠道 i18n key。 */
  channelKey: string;
  /** 状态机器值。 */
  status: string;
  /** 状态 i18n key。 */
  statusKey: string;
  /** 下单时间（UTC）。 */
  createdAt: string;
  /** 支付成功时间（UTC）。 */
  paidAt: string | null;
};

/** 充值记录分页（`RechargeListPageVO`）。 */
export type RechargeListPage = {
  /** 本页条目（倒序）。 */
  list: RechargeRow[];
  /** 下一页游标；无下一页为 null。 */
  nextCursor: string | null;
  /** 是否还有下一页。 */
  hasMore: boolean;
};
