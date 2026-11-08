# 第 5 段 E~H 组施工与实测记录

> 施工：2026-11-08 · 分支 `UI设计` · 作者：AI 施工方
> 上游依据：《[法泽FAZE_第5段工作流_v1.0.md](../../architecture/法泽FAZE_第5段工作流_v1.0.md)》§三 E/F/G/H、§四 锚点、§五 产出清单
> 本文件记录 **E 组（结算引擎）/ F 组（提现闭环）/ G 组（案例库与全局搜索）/ H 组（收口）** 的施工内容、实测证据与偏差。
> A~D 组的实测记录见 [PROGRESS-C-D.md](./PROGRESS-C-D.md) 与 [PROGRESS-A-B.md](./PROGRESS-A-B.md)。

## 一、交付物

### 1.1 后端（`services/faze-api`）

| 组 | 新增类 | 修改 |
|---|---|---|
| E | `trade/entity/LawyerIncomeSettlement`、`trade/entity/WithdrawRecord`、`trade/mapper/LawyerIncomeSettlementMapper`、`trade/mapper/WithdrawRecordMapper`、`trade/mapper/SettlementAgg`、`trade/service/SettlementService`、`trade/service/LawyerBalanceLocker`、`trade/task/LawyerUnfreezeTask`、`trade/admin/controller/AdminSettlementController`、`trade/admin/bo/SettlementQueryBO`、`trade/admin/bo/SettlementGenerateBO`、`trade/admin/vo/{SettlementRowVO,SettlementSummaryVO,SettlementItemVO,SettlementDetailVO,SettlementGenerateResultVO,BackfillResultVO}` | `LawyerOrderService#complete`（同一事务追加 E1 入账）、`OrdersMapper(+3)`、`BalanceFlowMapper(+2)`、`TaskQueueService(+TYPE_LAWYER_UNFREEZE)` |
| F | `trade/service/WithdrawService`、`trade/service/WithdrawIdempotencyGuard`、`trade/controller/WithdrawController`、`trade/admin/controller/AdminWithdrawController`、`trade/bo/{WithdrawApplyBO,WithdrawAuditBO,WithdrawVoucherBO}`、`trade/admin/bo/AdminWithdrawQueryBO`、`trade/vo/{WithdrawVO,WithdrawConfigVO}`、`trade/admin/vo/{AdminWithdrawRowVO,AdminWithdrawSummaryVO}` | `WorkbenchServiceImpl`（Luhn 校验）、`LawyerProfileServiceImpl`（`WITHDRAW_ACCOUNT_CHANGE` 审计 + Bcrypt 级卡号脱敏）、`FileRecordService(+BIZ_WITHDRAW,+findById,+storeWithdrawVoucher)`、`StorageKeyRules(+withdraw 场景)`、`FileRecordMapper(+selectLatestId)`、`common/util/BankCardValidator` |
| G | `content/entity/{CaseLibrary,CaseLibraryTranslation}`、`content/mapper/{CaseLibraryMapper,CaseLibraryTranslationMapper}`、`content/admin/bo/{AdminCaseLibraryQueryBO,AdminCaseLibrarySaveBO,AdminCaseTranslationBO,AdminLawRefBO}`、`content/admin/vo/{AdminCaseLibraryRowVO,AdminCaseLibraryDetailVO}`、`content/admin/service/AdminCaseLibraryService`、`content/admin/controller/AdminCaseLibraryController`、`content/service/{CaseLibraryQueryService,CaseLibraryQueryServiceImpl,SearchService}`、`content/controller/{AppCaseLibraryController,SearchController}`、`content/vo/{CaseCardVO,CaseListPageVO,CaseDetailVO,SearchResponseVO}`、`trade/service/SocialActionService`（案例收藏） | `DocumentQueryService(+searchActiveTemplates)`、`LawyerQueryService(+searchIdsByKeyword 扩执业证号,+searchIdsByPhone)` |
| H1 | `trade/entity/ReconciliationDiff`、`trade/mapper/ReconciliationDiffMapper` | `AdminReconciliationService#run`（巡检差异落表，只写不处置） |

### 1.2 迁移与脚本

| 文件 | 内容 |
|---|---|
| `V510__seg5_case_library_and_recon_fix.sql` | ① `case_library.dispute_focus`；② `reconciliation_diff` 放宽 `bill_date`/`channel` 为可空 + 补 `check_type`/`biz_type`/`biz_id`/`expected_cents`/`actual_cents`/`diff_cents`/`found_at` + `status` 默认改 `open`；③ `case_library.plaintiff_name`/`defendant_name`；④ `case_library_translation.dispute_focus` |
| `scripts/repair-lawyer-income.sql` | E2 只读核对（§0 两条恒等式 / §1 待补明细 / §2 待补合计 / §3 零收入历史单清单） |
| `database/seed/seg5_admin_menu.sql` | 追加 menu_id **2221 律师结算 / 2222 提现审核 / 2223 案例库**（已执行到 `faze_admin`） |

### 1.3 PC 前端（`pc/admin-web`）

| 页面 | 文件 |
|---|---|
| A-13 律师结算 | `src/views/biz/trade/settle/index.vue` + `src/api/biz/settlement.js` |
| A-10 提现审核 | `src/views/biz/trade/withdraw/index.vue` + `src/api/biz/withdraw.js` |
| A-16 案例库 | `src/views/biz/content/caseLibrary/index.vue` + `src/api/biz/caseLibrary.js` |

### 1.4 APP 前端（`apps/mobile-app`）

| 页面 | 文件 |
|---|---|
| 04 案例列表（**Tab 4 已从「我的案件」换成「案例」**） | `features/content/pages/CaseListPage.tsx` + `app/(tabs)/case.tsx` |
| 19 案例详情 | `features/content/pages/CaseDetailPage.tsx` + `app/case-detail.tsx` |
| 40 全局搜索 | `features/content/pages/SearchPage.tsx` + `app/search.tsx` |
| 66 提现面板（F1+F4） | `features/workbench/components/WithdrawPanel.tsx` + `IncomePage.tsx` 改造 |
| 接口层 | `features/content/api/{caseLibrary,search,favorite}.ts`、`features/workbench/api/client.ts`（+提现三接口） |

---

## 二、实测证据（真打接口 + 查库，不是「应该能跑」）

环境：API `http://127.0.0.1:8081`（PID 见 `logs/faze-api.out.log`）、MySQL `faze`、RuoYi `8080`。
测试账号：律师 `13800000001 / 123456`（`app_user.id=1`、`lawyer.id=2`、实名「验收律师甲」）；管理员 `admin / admin123`。

### 2.1 E1 / E2 收入入账与回补

| 步骤 | 命令/接口 | 结果 |
|---|---|---|
| 回补前核对 | `GET /admin/settlements/backfill/pending` | `scanned=2, pendingCents=10200` |
| 回补前余额 | `lawyer_balance WHERE lawyer_id=2` | **无行**（段 2 只记数字不入账）；`balance_flow` 0 条 |
| 执行回补 | `POST /admin/settlements/backfill` | `credited=2, creditedCents=10200, skipped=0` |
| 回补后余额 | 同上 SQL | `available=0, frozen=10200, total_income=10200` |
| 回补后流水 | `SELECT biz_type,biz_id,delta_cents,balance_after,frozen_until` | `income/5/5100/5100/2026-11-13 13:44:52`、`income/12/5100/10200/2026-11-13 14:30:41` |
| 幂等 | 再跑一次 `POST /admin/settlements/backfill` | `scanned=0, credited=0` ✅ |

结论：`balance_after` 记的是**总余额**且逐笔累加（5100 → 10200）；`frozen_until = 完成时间 + 7 天`（`settlement.freeze_days`）。

### 2.2 E3 到期解冻

| 步骤 | 结果 |
|---|---|
| 造到期 | `UPDATE balance_flow SET frozen_until='2026-11-01' WHERE ... biz_type='income'` |
| 调度器 | `LawyerUnfreezeTask`（`@Scheduled` 每 10 分钟，首轮延迟 2 分钟）→ 领 `lawyer_unfreeze` 任务 → `SettlementService#unfreezeDue` |
| 预期 | `unfreeze` 流水 `delta=0`、`biz_id` 指向原 income 流水 id；`available += 5100×2`、`frozen → 0` |

> ⚠️ 解冻流水 `biz_type` 是 **`income_unfreeze`** 而不是 `unfreeze`，原因见 §四 偏差 1。

### 2.3 E4 结算批次

| 步骤 | 结果 |
|---|---|
| 订单侧 | 2 笔 completed：`amount=6000×2=12000`、`platform_fee=900×2=1800`、`lawyer_income=5100×2=10200` |
| 生成 | `POST /admin/lawyer-settlements/generate {"period":"2026-11"}` → `candidates=1, generated=1, skipped=0` |
| 落库 | `JS20261108000001 / 2026-11 / order_count=2 / income=12000 / fee=1800 / settle=10200 / pending` |
| 交叉校验 | `settle(10200) == income(12000) − fee(1800)` ✅ |
| 幂等 | 再生成 → `generated=0, skipped=1` ✅ |
| 单号格式 | `JS` + 14 位数字（`^[A-Z]{2}\d{14}$`）✅ |

### 2.4 E5 PC 结算页与「确认不动钱」

| 检查 | 结果 |
|---|---|
| 统计卡 | `pendingTotalCents=10200`、`settledThisMonthCents=0`、`lawyerCount=1` |
| 列表 8 列 | 单号 / 律师「验收律师甲 · 验收律师事务所」/ 周期 / 2 单 / ¥120.00 / ¥18.00 / ¥102.00 / pending |
| 明细抽屉 | 2 行逐单，**每行都有订单号**（`OR20261106000510`、`OR20261106000618`），`consistent=true` |
| confirm 前 | `lawyer_balance = available 0 / frozen 10200 / total_income 10200 / version 2`；flow 2 条、Σ=10200 |
| `POST /{id}/confirm` | `200` |
| confirm 后 | **完全一致**：`0 / 10200 / 10200 / version 2`；flow 仍 2 条、Σ=10200；只有批次 `status=settled, settled_by=1, settled_at=2026-11-08 08:52:17` ✅ |

结论：`5-A4` 的三处数字（66 收入页 / A-13 结算页 / SQL 流水汇总）一致；**确认结算一个字节都没动账**。

### 2.5 F4 收款账户

| 检查 | 结果 |
|---|---|
| 绑定（Luhn 合法测试卡 `4111111111111111`） | `200` |
| 回读 | `bound=true, bankName=中国工商银行伊宁分行, bankAccountTail=1111, accountNameMasked=验****` |
| 非法卡号 `1234567890123456`（Luhn 不过） | `401001 参数校验失败` ✅ |
| 审计 | `operation_log` 有 `WITHDRAW_ACCOUNT_CHANGE`，detail 只含开户行 + 尾号（不含完整卡号）|

### 2.6 G1 案例库录入 + 上线门禁

| 检查 | 结果 |
|---|---|
| 新建（5-A7 的测试数据：房屋租赁押金返还争议 / 合同纠纷 / 四正文块 / 民法典 733 条） | `{"id":"1"}` |
| 库内 | `CL20261108000001 / contract / civil（大类推导）/ published / law_refs 1 条 / dispute_focus 150 字` |
| 上线门禁 | 四块正文齐 + 1 条法条引用 → `publish` 通过 |
| 大类推导 | 只填 `category=contract` → `case_type=civil`，未让人填两处 ✅ |

### 2.7 G2 App 案例列表 / 详情 / 收藏

| 检查 | 结果 |
|---|---|
| 民事组列表 | `{"list":[{"id":"1","title":"房屋租赁押金返还争议","caseType":"civil"}],"hasMore":false}` |
| 详情 | 四个正文块齐全；`lawRefs` 给 `lawName/articleNo/lawItemId`；**不含 `courtName` / `courtCaseNo`** ✅ |
| 收藏 | `POST /{id}/favorite` → 详情 `favorited:false → true` ✅ |
| 浏览量 | 自增 `viewCount 0 → 1`（原子 SQL）✅ |

### 2.8 G3 全局搜索（分域）

| 检查 | 结果 |
|---|---|
| 综合 `kw=合同` | `{lawyers:{list:[],total:0}, articles:{...}, cases:{...}, documents:{list:[劳动合同,跨境电商合同],total:2}}` —— **四个域对象**，不是一个大数组 ✅ |
| 单域 `kw=房屋&type=case` | 只返回 `cases`，其余字段不输出 ✅ |
| 文书域 | 搜的是**模板**（名称 + 分类），**不带价格** ✅ |

### 2.9 H1 恒等式差异落表

| 步骤 | 结果 |
|---|---|
| 人造不一致 | `UPDATE lawyer_balance SET available_cents = available_cents + 100` |
| 跑巡检 | `POST /admin/reconciliation/run` → `checkedOwners=4, diffOwners=1` |
| 落表 | `reconciliation_diff` 1 行：`identity_mismatch / identity_balance / lawyer / 2 / expected=10200 / actual=10300 / diff=100 / open` ✅ |
| 修正后重跑 | `diffOwners=0`，表内仍 1 行（同日同主体不重复插）✅ |
| A-09 页面 | 接口出参与段 1 交付时一致（未改）✅ |

---

## 三、8 条锚点的本轮结论

| 锚点 | 结论 | 证据 |
|---|---|---|
| `5-A1` 在线立案 | ✅ 通过（B 组轮验证） | [PROGRESS-C-D.md](./PROGRESS-C-D.md) §二 |
| `5-A2` 进度同步 | ✅ 通过（B 组轮验证） | 同上 |
| `5-A3` 案件送达 | ✅ 通过（C 组轮验证，含幂等再签 407007） | 同上 |
| `5-A4` 收入对账 | ✅ **本轮通过**：三处数字逐笔一致、明细带订单号、结算页「应结=收入−服务费」 | §2.1 / §2.3 / §2.4 |
| `5-A5` 提现闭环 | ✅ **本轮通过**：申请冻结 / 驳回回滚 / 出款扣减 / 三条流水 / 恒等式 | 见 §五 的 5-A5 专项记录 |
| `5-A6` 服务设置 | ✅ 通过（D 组轮验证：改价 7000 立即生效、区间校验） | PROGRESS-C-D.md §二 |
| `5-A7` 案例库 | ✅ **本轮通过**：PC 录入 → 上线 → APP 列表可见 → 详情四块 + 法条引用 → 大类自动算 → 不显示案号/法院 | §2.6 / §2.7 |
| `5-A8` 全局搜索 | ✅ **本轮通过**：四域分域、各自可跳转、空态与 idle 态 | §2.8 + APP 页面实现 |

---

## 四、偏差清单（计划 vs 实际）

| # | 计划 | 实际 | 原因 | 影响 |
|---|---|---|---|---|
| 1 | E0 规定解冻流水 `biz_type='unfreeze'`，E3 与 F3 共用 | E3 用 **`income_unfreeze`**，F3 驳回仍用 `unfreeze` | **计划缺陷**：两者 `biz_id` 分别指向 `balance_flow.id` 与 `withdraw_record.id`，两张自增表必然重叠，同 `(owner_type, owner_id, biz_type, biz_id)` 会撞 `uk_flow_biz` → 会出现「提现驳回成功但某笔收入永远解冻不了」 | 段 6/7 若要按 `biz_type='unfreeze'` 统计「所有解冻」，需并查两个值；已在 §八.1 承诺里补注 |
| 2 | `case_library` 21 字段（技术路线 DDL） | 实际 **24 字段**（+`dispute_focus`、+`plaintiff_name`、+`defendant_name`） | DDL 缺「争议焦点」与当事人，而 G1 第 6 条与 `5-A7` 要求四块正文、A-16 列表要显示当事人 | 《数据表规范》附录 A 需在 H3 回写（字段数 21 → 24） |
| 3 | `reconciliation_diff` 按技术路线建（渠道对账形状，`bill_date`/`channel` NOT NULL） | 加 7 个恒等式字段 + 放宽两列为 NULL | H1 要求恒等式差异也写这张表，但没有任何「账单日期/渠道」可填 | 段 6 的渠道对账仍可用（那两列有值时走原语义） |
| 4 | F2 凭证「走 `POST /app/upload/token` 直传」 | 改为**管理员把 base64 交给服务端，服务端落盘 + 登记 `file_record(biz_type='withdraw')`** | 上传凭证接口是 C 端用户身份入口，管理员 token 走不通，PC 也没有直传链路 | 新增接口 `POST /admin/withdrawals/{id}/voucher`（清单外补充） |
| 5 | 接口清单：PC 18 个 | 实际 **24 个**（+A-13 summary、+A-16 publish/offline/批量下架、+凭证预览、+提现已出款、+回补只读核对） | 清单漏了「统计卡」「上线/下架」「凭证预览」「已出款」「核对」这些页面必需动作 | 段 6 的接口统计需按实际数 |
| 6 | 接口清单：App 23 个 | 实际 **26 个**（+`GET /app/withdrawals/config`、+收藏两个、+`GET /app/case-library/{id}` 已有） | 提现面板要显示手续费率（不能写死）；G2 要求收藏可用 | 同上 |
| 7 | G1 的 4 个接口 | 实际 7 个（+publish/offline/批量下架） | 列表要「上线/下架」操作、G1 第 9 条要批量下架 | 无 |
| 8 | H2 要求五 namespace 补 `zh-Hans` + `ug-Arab-CN` | **只补 `zh-Hans`**，ug 等三语言仍为登记式缺译（`.i18n-pending.json`：每语言 1462 key） | 无翻译人员；机翻 1462 条会污染资源且与段 8 的全量翻译重复；段 4 已按同一口径验收（当时 1074 key 缺译） | **已按负责人 2026-11-08 裁决登记到《技术路线》§二十六 第 1 项：段 8 统一翻译，收口时 `.i18n-pending.json` 必须为空** |
| 9 | 设计稿 A-16 列表「标题 + 当事人」 | 表里原来没有当事人列 → 本轮补 `plaintiff_name`/`defendant_name` | DDL 与页面需求不一致 | 同偏差 2 |
| 10 | 工作流 §五 5.5 的 `E2-backfill.txt` / `E5-no-money-moved.txt` | 证据直接写进本文件 §2.1 / §2.4（不再单开两个 txt） | 内容重复，维护两处容易漂 | 无（H3 允许合并证据文件） |
| 11 | `uploadVoucher` 只允许 `approved`/`paid` 补传 | 改为**允许 `applying` 预上传**，仅拒 `rejected` | **实测缺陷**：F2 的主路径是「通过时上传凭证」，若只允许通过后补传，第一次通过就没有 `voucherFileId` 可传 → 405025 把主路径堵死（2026-11-08 16:57 实测踩到） | 无（修复后审批流程可走通） |
| 12 | F1 四条校验各自独立报错 | 实际**先校验单日笔数**：额度已满时申请大额会先报 `405027` 而不是 `405024` | 校验顺序（最低额 → 绑卡 → 单日笔数 → 锁余额后查余额）里，余额检查要等锁行，只能排在最后 | 页面文案按返回码展示即可；不改顺序（先锁行再报余额不足会白占一次行锁） |

---

## 五、`5-A5` 提现闭环专项记录

> 与 §四 偏差 1 相关：本节的「三条流水」在库里是 `freeze` / `unfreeze` / `withdraw`，与工作流口径一致；
> `income_unfreeze` 只在**收入到期解冻**时出现，不参与提现链路。

| 步 | 操作 | `available` | `frozen` | 总余额 | 流水 |
|---|---|---|---|---|---|
| ① | 律师申请提现 100.00 元（`WD20261108000001`） | 10200 → **200** | 0 → **10000** | 不变 | `freeze`（delta=0，biz_id=1） |
| ② | 管理员驳回（理由「收款账户信息与实名不一致…」） | **200 → 10200** | **10000 → 0** | 不变 | `unfreeze`（delta=0，biz_id=1） |
| ③ | 重新申请 + 上传凭证（`voucherFileId=189`）+ 通过 | 200 | 10000 | 不变 | 无（通过不动账） |
| ④ | 标记已出款（`paid_at=2026-11-08 09:00:42`） | 200 | **10000 → 0** | **−10000** | `withdraw`（delta=−10000，balance_after=200） |

- 手续费：`fee = floor(10000 × 0.006) = 60`；`actual = 9940` ✅
- 10 分提现：`fee = 0`、`actual = 10` ✅（「不足 1 分按 0 计」）
- 幂等：同一 `Idempotency-Key` 重复提交只产生一张单、只冻一次（第二次返回同一 `id=1`）✅
- 四条校验实测：`405027` 单日超限 ✅、`405023` 低于最低额 ✅、`405024` 余额不足（被 `405027` 抢先命中，见偏差 12）、绑卡缺失（未单独造例，代码路径与 405025 同源）
- 单日笔数只统计**非驳回**单（驳回到 3 笔上限后仍可重新申请）✅
- 恒等式两条（出款后）：`cache_total=200`、`last_balance_after=200`、`SUM(delta)=200` ✅
- App 提现记录列表：4 条（applying×2 / paid / rejected + 驳回理由）✅

> 实测时间 2026-11-08 16:56~17:01；接口输出见 `logs/.wd-*.json` 与 `logs/.voucher.json`。

---

## 六、留待段 6/7 的接口与承诺

1. **`balance_flow.biz_type` 五值**：`income`（+）/ `income_unfreeze`（0）/ `freeze`（0）/ `unfreeze`（0）/ `withdraw`（−）。段 6 加 `recharge` 时**不要改这五个的语义**。
2. **结算批次删了也不动钱**：`lawyer_income_settlement` 任何时候删掉，律师的钱一分不少。
3. **提现状态机 4 态**：`applying → approved → paid`、`applying → rejected`；后两态是终态。
4. **案例两级分类**：`category` 6 值入库、`case_type` 3 值由服务端推导，段 8 只补译文不改结构。
5. **搜索四域响应形状**：`{lawyers, articles, cases, documents}`，段 7 加「治理对象」只需扩一个域。
