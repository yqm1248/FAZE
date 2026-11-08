# T7 · 注册页「所在地区」采集 — 验收证据

> 段 7 尾巴（负责人 2026-11-09 拍板：`app_user.region` 走**注册页采集**，不做资料页选填）
> 证据截图：本目录 `T7-register-region.png` / `T7-region-picker.png` / `T7-region-selected.png` / `T7-region-required.png`
> 方式：CDP 真实 Chrome 390×844（但实际截图视口 758×482，与段 7 其余 APP 截图一致）
> 后端：`V709` + `GET /api/v1/pub/regions` + `POST /pub/auth/register` 新增 `region`

## 一、为什么做这个（不是新需求，是接线）

`app_user.region` 是 `V707` 建的列，但**从来没有采集入口**，恒为 NULL。已实测的后果：

| 后果 | 实测 |
|---|---|
| A-02 看板「地区分布」只有一项「其他」 | 段 7 收口时即如此 |
| 按地区发公告必然 0 人，**且不报错** | `AnnouncementSendService` 的 `region` 目标人群走 `region IN (...)`，NULL 不命中；工单照常走完 `sent` |

负责人在两个方案里选了「注册页采集」，理由：选填覆盖率太低会让地区功能形同虚设。

## 二、契约（已冻结，前后端按此实现）

| 项 | 形状 |
|---|---|
| `GET /api/v1/pub/regions`（免登录） | `R<List<String>>` —— `data` **直接是字符串数组**，不包 `{regions:[...]}` |
| 列表内容 | 伊犁哈萨克自治州直辖 **11 个县级行政区** + 「其他」= 12 项：伊宁市 / 奎屯市 / 霍尔果斯市 / 伊宁县 / 察布查尔锡伯自治县 / 霍城县 / 巩留县 / 新源县 / 昭苏县 / 特克斯县 / 尼勒克县 / 其他 |
| 枚举来源 | `sys_config.user.region_options`（`value_type=json`）——**加县只改配置，不动代码、不发版** |
| `RegisterBO.region` | **必填**；不在白名单 → `401001`；首尾空格 trim 后落库 |
| `ProfileVO.region` | `string \| null`（null = 未采集的历史账号）；`UserVO` 是冻结契约，未动 |
| 未列入 | 塔城地区、阿勒泰地区（同属伊犁州管辖但历史上是独立片区），是否纳入待负责人再定 |

## 三、逐项验收（全部真实浏览器 + 真实后端）

| # | 验收点 | 看到什么 | 截图 |
|---|---|---|---|
| 1 | 注册页有「所在地区」必选项 | 位置在密码之后、协议勾选之前，占位「请选择所在地区」 | `T7-register-region.png` |
| 2 | 点开是页内底部弹层，不是系统 picker | 遮罩 + × 关闭 + 12 项列表；**中文专有名词原样显示**（不翻译） | `T7-region-picker.png` |
| 3 | 选中后回填 | 触发器显示「伊宁市」（DOM 断言 `triggerText="伊宁市"`） | `T7-region-selected.png` |
| 4 | **未选不能提交** | 填完手机号/验证码/密码但不选地区 → 字段下方红字「请选择所在地区」，**仍停在 /register 且未发出注册请求** | `T7-region-required.png` |
| 5 | **真实注册落库** | `POST /pub/auth/register` 带 `region:"伊宁市"` → 200；DB `app_user.region='伊宁市'` | 见 §四 |
| 6 | 资料接口读回 | `GET /app/account/profile` 返回 `region:"伊宁市"`（验证 `AccountConverter` 链路，不只是查库） | 见 §四 |
| 7 | 接口形状 | `GET /pub/regions` → `{"code":200,"data":[...12 项...],"msg":"ok"}` | 见 §四 |
| 8 | 非法值被拒 | 传不在白名单的 region → `401001`，`msg` 恒为「参数校验失败」（`BusinessException.detail` 只进日志不进响应体） | 见 §四 |
| 9 | 未传被拒 | 不传 region → `401001`，`msg:"region: 请选择所在地区"`（Bean Validation 字段提示透传） | 见 §四 |
| 10 | 首尾空格 | `"  伊宁市  "` → trim 后落库为 `伊宁市` | 见 §四 |

## 四、接口与数据库原始输出

```text
$ curl -s http://127.0.0.1:8081/api/v1/pub/regions
{"code":200,"data":["伊宁市","奎屯市","霍尔果斯市","伊宁县","察布查尔锡伯自治县","霍城县",
"巩留县","新源县","昭苏县","特克斯县","尼勒克县","其他"],"msg":"ok","traceId":"..."}
（字节级核验：e4 bc 8a e5 ae 81 e5 b8 82 = 「伊宁市」，UTF-8 正确，不是双重编码）

$ POST /api/v1/pub/auth/register  不带 region
{"code":401001,"data":null,"msg":"region: 请选择所在地区","traceId":"..."}

$ POST /api/v1/pub/auth/register  region="  伊宁市  "
200 → 查库
```

```sql
SELECT id, phone, region FROM app_user WHERE phone = '13900007777';
-- id=9393, region='伊宁市'   （测试账号已在验收后从 app_user / user_device / login_log 三表清理）
```

## 五、门禁（真实退出码）

```text
npx tsc --noEmit ..................... EXIT 0
npx eslint . --ext .ts,.tsx .......... EXIT 0（0 error / 0 warning）
npm run check:rawtext ................ EXIT 0（186 个 tsx，命中 0）
npm run check:i18n ................... EXIT 0（四目录同构；缺译 1818 条，与上版持平）
npx expo export --platform web ....... EXIT 0
```

> **`npx vite build` 不用于 `apps/mobile-app`**：那是 PC 端（`pc/admin-web`）的门禁，Expo 用 Metro。
> 强跑会临时下载 `vite@8.3.6`，违反「新增依赖必须先说明理由」（AGENTS §12），故用 Expo 官方导出做等价验证。

## 六、偏差与遗留

1. **三语 key 值暂为中文**：`bo` / `ug-Arab-CN` / `kk-Arab-CN` 的 4 个 key（`regionLabel` / `regionPlaceholder` / `regionRequired` / `regionClose`）值与 `zh-Hans` 相同。**不删 key**——删了四目录就不同构，风险比「藏文界面显示中文」大。随 `.i18n-pending.json` 一起留段 8。
2. **前端内置兜底列表与后端配置可能漂移**：接口正常时用服务端列表；接口挂了才用内置的 12 项。运营改配置后老客户端仍显示旧列表——这是「不白屏」的代价，已知。
3. **塔城 / 阿勒泰未列入**：是否纳入待负责人定；现在改 `sys_config.user.region_options` 即可加，不动代码。
4. **历史账号 `region` 仍为 NULL**：本页只解决新注册用户；存量用户在段 8 是否补采集（资料页）未定。
