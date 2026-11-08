# 段 6 · A3 验收证据：渠道配置键与加密状态

- 生成时间：2026-11-08（Asia/Shanghai）
- 环境：dev / MySQL 8.0.44 / 库 `faze`
- 迁移：`V601__seg6_pay_config.sql`（flyway success=1）
- 结论：**21 个必需键 + 3 个 Mock 测试开关全部就位**（共 24 个），加密项按 `ENC(...)` 校验通过。

---

## 一、24 个键的实测值

查询命令：

```powershell
$sql | & mysql.exe --host=127.0.0.1 -uroot -p123456 -N -B faze
```

```sql
SELECT config_key, config_value, value_type, category FROM sys_config
 WHERE config_key LIKE 'pay.%' OR config_key LIKE 'recharge.%' ORDER BY config_key;
```

| # | 键 | 实测值 | 类型 | 加密 | 用途 |
|---|---|---|---|---|---|
| 1 | `pay.mock.enabled` | `true` | bool | — | Mock 通道总开关；**生产必须 false**（`ProdMockPayGuard` 启动即拦） |
| 2 | `pay.wechat.enabled` | `false` | bool | — | 微信通道开关（商户号未下发） |
| 3 | `pay.alipay.enabled` | `false` | bool | — | 支付宝通道开关（商户号未下发） |
| 4 | `pay.wechat.appid` | 空 | string | — | 微信 AppID |
| 5 | `pay.wechat.mch_id` | 空 | string | — | 微信商户号 |
| 6 | `pay.wechat.api_v3_key` | `ENC(73kcfTFpdUtg…mRY=)`（77 字符） | string | ✅ **ENC** | APIv3 密钥，验签与报文解密 |
| 7 | `pay.wechat.cert_path` | 空 | string | — | 商户证书**路径**（文件不进仓库） |
| 8 | `pay.wechat.cert_serial` | 空 | string | — | 商户证书序列号 |
| 9 | `pay.alipay.appid` | 空 | string | — | 支付宝 AppID |
| 10 | `pay.alipay.mch_id` | 空 | string | — | 支付宝商户号 |
| 11 | `pay.alipay.private_key` | 空 | string | 待写 **ENC** | 应用私钥，签名用 |
| 12 | `pay.alipay.public_key` | 空 | string | — | 支付宝公钥（非敏感） |
| 13 | `pay.callback_base_url` | 空 | string | — | 回调域名前缀；可留空，Mock 不依赖 |
| 14 | `pay.callback_ips` | 空 | string | — | 回调 IP 白名单；空 = 只靠验签 |
| 15 | `recharge.min_cents` | `10` | int | — | 单笔最低 0.1 元（设计稿「10 元起」作废） |
| 16 | `recharge.max_amount_cents` | `500000` | int | — | 单笔最高 5000 元 |
| 17 | `recharge.max_daily_cents` | `500000` | int | — | 单日累计 5000 元 |
| 18 | `recharge.max_daily_count` | `10` | int | — | 单日笔数上限 |
| 19 | `recharge.amount_options` | `[10000,20000,50000,100000,200000]` | json | — | 档位（分） |
| 20 | `recharge.expire_minutes` | `30` | int | — | 充值单超时（上线值） |
| 21 | `recharge.unverified_daily_cents` | `0` | int | — | 未实名额度 0 = 完全不可充值（拦截） |
| 22 | `pay.mock.delay_ms` | `0` | int | — | Mock 回调延迟（6-A2 重放窗口） |
| 23 | `pay.mock.result` | `success` | string | — | Mock 结果：success / fail / callback_lost（6-A3 掉单） |
| 24 | `pay.mock.amount_offset_cents` | `0` | int | — | Mock 金额偏移（6-A4 对账差异） |

> 22~24 是 A3 第 4 条要求的 Mock 测试开关，**不在 21 个业务键内**，单独计。

---

## 二、加密项的验证

### 2.1 库值确以 `ENC(` 开头

```sql
SELECT config_key, LEFT(config_value,20), LENGTH(config_value)
  FROM sys_config WHERE config_key IN ('pay.wechat.api_v3_key','pay.alipay.private_key');
```

实测输出：

```text
pay.alipay.private_key            0
pay.wechat.api_v3_key   ENC(73kcfTFpdUtg+Eg3   77
```

### 2.2 加解密组件单测

`ConfigCipherTest`（5 个用例，`mvn -Dtest=ConfigCipherTest test`）：

| 用例 | 断言 | 结果 |
|---|---|---|
| 往返 | `encrypt` 输出 `ENC(...)`，`decryptIfEncrypted` 还原明文 | PASS |
| 非密文透传 | 明文 / 空串 / null 原样返回，`ENC()` 空壳不算密文 | PASS |
| 随机 IV | 同一明文两次加密密文不同 | PASS |
| 篡改 | 改一个字符 → `IllegalStateException`（GCM 完整性） | PASS |
| 密钥长度 | 非 32 字节 → 构造即失败 | PASS |

### 2.3 加密值怎么来的（生产流程）

迁移 `V601` 里两个加密键**故意留空**——密文与密钥绑定，写死进迁移只对开发库有效。

生产写值流程：

1. 生成 32 字节密钥，设环境变量 `FAZE_CONFIG_SECRET`（**不复用 dev 默认值，也不复用 `FAZE_IDCARD_SECRET`**）。
2. 用 `ConfigCipher#encrypt(plain)` 得到 `ENC(...)`。
3. `UPDATE sys_config SET config_value='ENC(...)' WHERE config_key='...'`。
4. 重启让配置生效（`SysConfigQueryService` 不缓存，改完即读新值）。

本文件里的 `ENC(73kcfTFpdUtg…mRY=)` 是 **dev 演示值**，加密内容是 `dev-only-wechat-apiv3-key`（无意义占位），只能被 dev 默认密钥解开。

---

## 三、红线自检

| 检查 | 命令 | 结果 |
|---|---|---|
| 明文密钥进仓库 | `grep -rn "api_v3_key\s*[:=]\s*[A-Za-z0-9]\{16,\}" services/` | 无命中（键名在常量类，值只在 DB） |
| 日志打密钥 | 检索 `infra/pay` 的日志调用 | 三个网关均无 `log.info` 打配置值；`ConfigCipher` 不打日志 |
| 生产误开 Mock | `ProdMockPayGuard` 存在且 `@Profile("prod")` | ✅ 启动即抛 `IllegalStateException` |
