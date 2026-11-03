# `0-A1` 可安装 · 证据（D1 云构建）

> **本目录是什么**：`0-A1`（可安装）的证据入口。**APK 与构建日志不入库**（`.gitignore` 含 `*.apk`/`*.aab`，AGENTS §5 禁止提交构建产物），入库的只有「证据指针」。

---

## 一、构建记录（2026-11-04 首次）

| 项 | 值 |
|---|---|
| 构建 profile | **`preview`**（APK，internal distribution） |
| buildId | `f6d854fa-7132-4c04-a37b-eabed777fa00` |
| **构建页面** | https://expo.dev/accounts/1454132yqm/projects/faze/builds/f6d854fa-7132-4c04-a37b-eabed777fa00 |
| Expo 账号 | `1454132yqm`（Owner） |
| EAS projectId | `06aff2d2-086b-4718-8f91-9d2a50a9adc0` |
| 代码版本（构建时 HEAD） | `46a4820`（**工作区含 D1 未提交改动**：`eas.json`、`app.config.ts` 的 projectId/runtimeVersion/updates、`expo-dev-client`，构建上传的是工作区快照） |
| 内联 baseURL | `http://192.168.43.186:8081`（PC 局域网 IPv4，写死在 `eas.json` 的 `preview.env`） |
| keystore | **EAS 云端生成并托管**（本机无 keytool）；`.gitignore` 已忽略 `*.jks`/`*.keystore` |
| 构建启动命令 | `npx --yes eas-cli@latest build --platform android --profile preview --non-interactive --no-wait` |

## 二、APK 落地位置（不入库）

```text
E:\dshcode\tmp\seg0\apk\faze-46a4820.apk
```

`tmp/` 已被 `.gitignore` 忽略，符合「构建产物不入库」。

**SHA256**：待构建完成后补（`Get-FileHash <apk> -Algorithm SHA256`），供他人核对手上那个包是不是同一个。

## 三、`0-A1` 的验收判据与当前状态

| # | 判据 | 状态 |
|---|---|---|
| 1 | `adb shell pm list packages` 输出含 `package:com.faze.app` | ⏳ 待真机 |
| 2 | 真机点图标能进登录页，启动 30 秒内 `logcat` 无 `FATAL EXCEPTION` | ⏳ 待真机 |
| 3 | 本文件五项齐全（buildId / buildUrl / git commit / APK SHA256 / 设备型号） | ⏳ 前三项已有，后两项待真机与下载 |
| 4 | 装的是 `preview` 包（不是 dev-client 包） | ✅ 已确认 profile=preview |
| 5 | 切 `ug-Arab-CN` → `am force-stop` → 重开仍 RTL | ⏳ 待真机（同时是 `0-A3` 的硬判据） |
| 6 | `reloadAsync` 实际结果写成一句话结论 | ⏳ 待真机 |
| 7 | 另一人只照 `docs/standards/构建路径.md` 能独立构建 | ⏳ 待第二人 |

## 四、待产出（真机接入后）

```text
docs/acceptance/seg0/D1/
├─ README.md           # 本文件
├─ eas-build.json      # npx eas-cli@latest build:view <buildId> --json 的完整输出
├─ install-<型号>.png  # 真机安装与启动截图
└─ logcat-startup.txt  # 启动 30 秒日志，改名避开 *.log，去敏后入库
```

> APK 本体留在 `E:\dshcode\tmp\seg0\apk\`，只把 SHA256 记进本文件。
