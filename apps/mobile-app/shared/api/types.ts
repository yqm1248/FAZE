/**
 * 网络层类型：统一响应、错误归一、结果包装。
 *
 * 依据：《接口规范》§5.1（`R<T>` 四个字段）与 §6.1.1（401 双通道）。
 * 调用方：`shared/api/client.ts`、各 `features/<域>/api/*`、`shared/components/AsyncBoundary`。
 * 注意：
 *   1. **`R.msg` 绝不直接展示**（接口规范 §5.1 约束 2）。页面只认 `msgKey` → `t()`。
 *   2. `data: null` 是**合法的成功值**（§5.1 约束 1 明确字段保留），不是错误。
 *   3. 本模块只有类型与常量，没有运行时逻辑——避免被循环引用。
 */

/**
 * 服务端统一响应体（与 `R<T>` 一一对应）。
 *
 * @template T 业务数据类型
 */
export type ApiEnvelope<T> = {
  /** 200 或 6 位业务错误码。 */
  code: number;
  /** 开发者可见的调试信息，**客户端禁止展示**。 */
  msg: string;
  /** 业务数据；无数据时为 null（字段保留，不是缺失）。 */
  data: T | null;
  /** 服务端链路 id，与响应头 X-Trace-Id 同值。 */
  traceId: string | null;
};

/**
 * i18n 文案 key 的类型占位。
 *
 * B3 会把它收窄为 `keyof typeof zhHans`（编译期查出缺 key）；在此之前先用 string，
 * 避免 B2 反向依赖 B3。**这是本文件唯一的临时类型，B3 改造时只改这一行。**
 */
export type MessageKey = string;

/** 业务错误：服务端明确返回了非 200 的业务码。 */
export type BusinessAppError = {
  kind: 'business';
  /** 6 位业务码。 */
  code: number;
  /** 对应的 i18n key。 */
  msgKey: MessageKey;
  /** 链路 id（有则带上，便于后端定位）。 */
  traceId: string | null;
};

/** 网络错误：断网、超时、DNS 失败等。 */
export type NetworkAppError = {
  kind: 'network';
  msgKey: MessageKey;
  traceId: null;
};

/** 解析错误：响应不是合法 JSON（例如被网关改写成 HTML）。 */
export type ParseAppError = {
  kind: 'parse';
  msgKey: MessageKey;
  traceId: string | null;
};

/** 网络层归一后的错误联合。三种来源互斥，页面按 `kind` 选文案。 */
export type AppError = BusinessAppError | NetworkAppError | ParseAppError;

/**
 * 网络调用结果。
 *
 * **不抛异常**：`client.ts` 把所有失败（网络、超时、业务码、解析）都收敛成 `{ ok: false }`，
 * 渲染层据此进错误态而不是崩溃（B4 的四态与 `0-A8` 的基础）。
 *
 * @template T 业务数据类型
 */
export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: AppError };
