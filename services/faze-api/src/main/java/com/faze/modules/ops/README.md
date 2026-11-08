# ops 域（运营配置与看板）

> 《后端代码规范》§2.6 要求每个业务域有 README，固定四段：边界 / 数据来源 / 不可改的契约 / 测试入口。

## 边界

**管**：
- `sys_config` 键值（平台参数）与 PC 参数页
- 后台看板的汇总查询（只读聚合）
- App 侧公开配置（`/api/v1/pub/config`）

**不管**：
- 审计日志 → `infra/audit`（`operation_log` 只追加）
- 内容审核、敏感词 → 段 7
- 资金计算 → `trade` 域

## 数据来源

| 方向 | 表 / 来源 |
|---|---|
| 写 | `sys_config`（PC 参数页整批保存） |
| 读 | `sys_config`；看板聚合读 `app_user` / `orders` 等（只读） |
| 跨域 | 只读权限，走对方 `QueryService` |

## 不可改的契约

1. **`sys_config` 的键名是配置契约**：改键名 = 破坏性变更（代码、文档、前端同时引用）。
2. 段 2 起新增键：`platform_fee_rate`（15% 占位）、`order_close_minutes`、`order_accept_timeout_minutes`、单号格式（`order_no_format` 已存在）。
3. 整批保存用「先删后插」（后端规范 §8.7），同一事务；不要逐条 upsert。
4. 新增配置项**必须有人读**（后端规范 §11.4「死开关」禁令）。

## 测试入口

```bash
cd services/faze-api
mvn -Dtest="*SysConfig*Test" test
```
