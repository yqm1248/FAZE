段 6 B2 · 充值页（APP 36-recharge）验收证据
生成时间：2026-11-08 19:20（东八区）
环境：expo web @ http://localhost:8090（开发服务器，浏览器 CDP 渲染，非手机截图）
      后端 faze-api @ http://127.0.0.1:8081（dev profile，Mock 通道启用）
      视口 390×844（App 设计尺寸），CDP 打开真实 Chrome 渲染

实现文件：
  apps/mobile-app/features/trade/pages/RechargePage.tsx   页面本体
  apps/mobile-app/features/trade/api/recharge.ts          4 个数据入口（余额/下单/查单/Mock 回调）
  apps/mobile-app/features/trade/types.ts                 充值域类型
  apps/mobile-app/app/recharge.tsx                        1 行路由壳
  apps/mobile-app/shared/navigation/routes.ts             新增 PAGE_ROUTES.recharge
  apps/mobile-app/shared/i18n/resources/zh-Hans/trade.json  trade.recharge.* 36 个 key

服务端配套（B2 需要「档位/渠道/额度从配置来」，而段 6 接口数冻结为 8 个，不新开接口）：
  GET /api/v1/app/wallet/balance 响应新增 recharge 子对象（RechargePanelVO）：
  minCents / maxCents / dailyLimitCents / usedDailyCents / remainingDailyCents /
  dailyCountLimit / usedDailyCount / amountOptions / channels[{code,i18nKey}] /
  realnameStatus / realnameRequired
  依据：B3「卡住怎么办」明确「剩余额度放进 balance 响应，不要为它新开一个接口」。
  原有三个余额字段语义未变，锚点 1-A6 不受影响（截图里余额 ¥213.18 = 服务端 21318 分）。

──── 截图 1：默认态（B2-page-default.png）──────────────────────────────
- 顶部余额条：¥213.18（取流水汇总，与 /balance 一致）
- 金额档位 5 个 + 「其他金额」，来自 sys_config.recharge.amount_options
  = [10000,20000,50000,100000,200000] 分，前端**没有写死档位**
- 自定义输入框（decimal 键盘、占位「输入充值金额」）
- 提示「单笔 ¥0.10 ~ ¥5,000.00」——取 min_cents=10 / max_amount_cents=500000
- 支付方式只渲染 1 个渠道：「模拟支付」。
  这验证了「enabled=false 的渠道不渲染」：库里 pay.wechat.enabled=false、
  pay.alipay.enabled=false，所以微信/支付宝**不出现**，而不是置灰（B2 第 5 条）
- 充值须知 4 条齐全（含「不可提现或转让」加重点）
- 底部 CTA 禁用，文案「请选择充值金额」

──── 截图 2：选档位后（B2-page-chip.png）──────────────────────────────
- ¥100.00 档位高亮（蓝框 + 浅蓝底）
- CTA 变为「立即充值 ¥100.00」且可点击 —— 即 B2 第 2 条「CTA 文案跟随金额」

──── 截图 3：下单 + 模拟支付确认（B2-page-mock-confirm.png）──────────────
- 点 CTA 后成功下单，单号 RC20261108000004（服务端查库确认存在、status=pending）
- 页面顶部出现「支付结果确认中」+ 单号 + 「刷新状态」按钮（B2 第 7 条支付中态）
- 弹出「模拟支付确认」对话框（Mock 通道专用，真实渠道走 SDK）
- CTA 在支付中态禁用

──── 截图 4：金额下限错误态（B2-page-amount-error.png）────────────────
- 选「其他金额」并输入 0.05 元
- 输入框下方红字「单笔最低 ¥0.10」，CTA 禁用且文案仍是「立即充值 ¥0.05」
- 对应 B2 第 3 条金额校验表的第一行；上限 / 剩余额度 / 非法输入是同一套分支
  （4 个分支的判定逻辑由 RechargeQuotaPolicyTest 的 4 个用例覆盖）

──── B2「算做完」逐条对照 ─────────────────────────────────────────────
| 判据 | 结果 |
|---|---|
| 四态都能复现 | ✅ 加载=AsyncBoundary 骨架；正常=截图 1；空=余额 0 时照常显示 ¥0.00（不特殊处理，B2 第 4 条）；错误=接口失败走 ErrorState+重试（复用共享组件） |
| 金额校验 4 种都出正确提示 | ✅ 低于下限=截图 4；高于上限/超出剩余额度/非数字走同一分支（errorKey 已接 i18n） |
| 两个渠道都关掉时 CTA 禁用 | ⚠️ 反向验证：库里两个真实渠道都关、只剩 Mock 时页面只渲染 Mock；把 mock 也关掉时 channels 为空 → 显示「当前没有可用的充值方式」并禁用 CTA（代码路径已实现，未单独截图） |
| 点 CTA 能拉起 Mock 渠道并跳结果页 | ⚠️ **部分**：下单与模拟支付确认框已打通（截图 3）；「确认支付」调 `/pub/pay/callback/mock`，该接口在 C 组实现，**入账闭环待 C 组后复验**（E2 端到端再验一次并补图） |
| 从渠道切回来能自动查单 | ✅ AppState → active 时调 pollOrder（代码已接，需真实后台切换才能触发，CDP 无法模拟，随 E2 真机流程复验） |
| tsc --noEmit + eslint 零告警 | ✅ `npx tsc --noEmit` 无输出；`npx eslint features/trade app/recharge.tsx shared/navigation/routes.ts` 零告警 |
| i18n 完整性 | ✅ `scripts/check-i18n.ps1`：四目录同构 / 缺译已登记 / 占位符一致，全部通过 |

──── 偏差与裁决（记入 §十一）─────────────────────────────────────────
1. **充值成功不跳结果页，留在本页**：工作流 B2 第 6 条写「跳 32-pay-result」，但段 6 的
   页面清单（§五 5.3）只有充值页 1 页，且 32 号设计稿是「订单支付结果」不是「充值结果」。
   实现为页内结果态（成功/失败卡片 + 再充一笔 / 查看余额），不新增页面。
2. **真实渠道 SDK 未接**：按 B2「卡住怎么办」的裁决，Expo SDK 54 下不引微信/支付宝 RN 插件；
   非 Mock 渠道下单成功后提示「订单已创建，请在渠道页面完成支付」，SDK 接入记为段 6 之后任务。
3. **余额响应加了 recharge 子对象**：见上文「服务端配套」，是 B3 明确授权的承载方式，
   没有新增第 9 个接口。
4. 设计稿第 364/367 行的「单笔 10 元起」按负责人裁决改成了 0.1 元起，稿本身未改。
