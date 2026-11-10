/**
 * 不可见断言 —— 跑真实 API，验证「界面上看不出来、但坏了也说不清」的那几件事。
 *
 * 职责：
 *   1. 受保护接口**没有 token** 时必须 HTTP 401，且业务码是 402002/402003（鉴权破例）
 *   2. 该 401 响应带 `X-Trace-Id`（否则排障时拿不到链路 id）
 *   3. 登录成功返回令牌三件套，且 `user.id` 是**字符串**（Long 型 ID 序列化约定）
 *   4. 带 token 调受保护接口 → 200，且带 `X-Trace-Id`
 *   5. **跨域可读性**：OPTIONS 预检的 `Access-Control-Expose-Headers` 必须含
 *      `X-Trace-Id` / `X-First-Byte-Ms` / `X-Pay-Pwd-Remaining` / `Retry-After`
 *      —— 头发了但不在暴露列表里，对浏览器等于不存在
 *
 * 调用方：
 *   - 人工：`npm run check:invisible`（需要 API 在 8081 跑着，且库里有测试账号）
 *   - **CI 暂未接**，理由见文件末尾
 *
 * 数据来源：`http://127.0.0.1:8081`（可用 `--base-url` 覆盖）；登录账号来自
 *   `database/seed/test.sql`（13800000001 / 123456）。
 *
 * 注意事项：
 *   - **本脚本只读**：只发 GET / 登录 / OPTIONS 预检，不产生业务数据。
 *   - API 连不上时退出 2（环境问题），不是 1（断言失败）——两者处置完全不同。
 *   - 不测「支付密码错误 → X-Pay-Pwd-Remaining 实际存在」：那需要把某个账号的密码试错，
 *     会触发锁定计数，副作用大于收益。改用「CORS 暴露列表包含它」（第 5 条）覆盖可读性，
 *     服务端是否真的发头由 `CorsExposedHeadersTest` + 代码 review 共同守。
 *
 * 退出码：0 全部通过 / 1 有断言失败 / 2 API 不可用
 */

import { randomUUID } from 'node:crypto';

const args = process.argv.slice(2);
const baseUrlArg = args.find((a) => a.startsWith('--base-url='));
const BASE = (baseUrlArg ? baseUrlArg.split('=')[1] : 'http://127.0.0.1:8081').replace(/\/$/, '');

const PHONE = '13800000001';
const PASSWORD = '123456';
const DEVICE_ID = 'check-invisible-device';

const results = [];

/** 记一条断言结果。 */
function record(id, title, ok, detail) {
  results.push({ id, title, ok, detail });
}

/** 统一请求头（与 App 的真实请求头同构，见接口规范 §2）。 */
function headers(extra = {}) {
  return {
    Accept: 'application/json',
    'Accept-Language': 'zh-Hans',
    'X-Device-Id': DEVICE_ID,
    'X-Client-Platform': 'check-invisible',
    'X-Client-Version': '1.0.0',
    'X-Request-Id': randomUUID(),
    ...extra,
  };
}

/** 带超时的 fetch（防止 API 假死把脚本挂住）。 */
async function req(path, init = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    return await fetch(`${BASE}${path}`, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

// ── 0. API 是否活着 ───────────────────────────────────────────────────
try {
  const ping = await req('/api/v1/pub/app/config');
  if (!ping.ok) {
    console.error(`[FAIL] API 返回 ${ping.status}，不算「活着」。先确认服务状态。`);
    process.exit(2);
  }
} catch (err) {
  console.error(`[FAIL] 连不上 API（${BASE}）：${err.message}`);
  console.error('       先起服务（deploy/start-api.ps1 或 start-services.ps1），再跑本脚本。');
  process.exit(2);
}

// ── 1~2. 无 token 打受保护接口 → 401 + 业务码 + 链路头 ────────────────
{
  const res = await req('/api/v1/app/account/profile', { headers: headers() });
  let body = null;
  try {
    body = await res.json();
  } catch {
    body = null;
  }
  const code = body?.code;
  record(
    'I1',
    '无 token 调 /app/account/profile 必须 HTTP 401 且业务码 402002/402003',
    res.status === 401 && (code === 402002 || code === 402003),
    `status=${res.status} code=${code}`,
  );
  record(
    'I2',
    '401 响应必须带 X-Trace-Id（否则排障拿不到链路 id）',
    Boolean(res.headers.get('x-trace-id')),
    `x-trace-id=${res.headers.get('x-trace-id')}`,
  );
}

// ── 3. 登录 ───────────────────────────────────────────────────────────
let token = null;
{
  const res = await req('/api/v1/pub/auth/login/password', {
    method: 'POST',
    headers: headers({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ phone: PHONE, password: PASSWORD, deviceId: DEVICE_ID }),
  });
  let body = null;
  try {
    body = await res.json();
  } catch {
    body = null;
  }
  token = body?.data?.accessToken ?? null;
  const userIdType = typeof body?.data?.user?.id;
  record(
    'I3',
    '登录成功：code=200 + 拿到 accessToken + user.id 是字符串',
    body?.code === 200 && Boolean(token) && userIdType === 'string',
    `code=${body?.code} token=${token ? '有' : '无'} userIdType=${userIdType}`,
  );
}

// ── 4. 带 token 调受保护接口 ──────────────────────────────────────────
if (token === null) {
  record('I4', '带 token 调受保护接口 → 200 且带 X-Trace-Id', false, '跳过：I3 没拿到 token');
} else {
  const res = await req('/api/v1/app/account/profile', {
    headers: headers({ Authorization: `Bearer ${token}` }),
  });
  let body = null;
  try {
    body = await res.json();
  } catch {
    body = null;
  }
  record(
    'I4',
    '带 token 调受保护接口 → 200 且带 X-Trace-Id',
    res.status === 200 && body?.code === 200 && Boolean(res.headers.get('x-trace-id')),
    `status=${res.status} code=${body?.code} x-trace-id=${res.headers.get('x-trace-id')}`,
  );
}

// ── 5. 跨域可读性：暴露响应头 ─────────────────────────────────────────
{
  const res = await req('/api/v1/app/account/profile', {
    method: 'OPTIONS',
    headers: headers({
      Origin: 'http://localhost:8090',
      'Access-Control-Request-Method': 'GET',
      'Access-Control-Request-Headers': 'authorization,x-request-id',
    }),
  });
  const exposed = (res.headers.get('access-control-expose-headers') ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  const required = ['X-Trace-Id', 'X-First-Byte-Ms', 'X-Pay-Pwd-Remaining', 'Retry-After'];
  const missing = required.filter((h) => exposed.length > 0 && !exposed.some((e) => e.toLowerCase() === h.toLowerCase()));
  // 预检没有 CORS 配置时（例如 API 以 !dev profile 起）exposed 为空，这时不算失败但要说清
  const configured = exposed.length > 0;
  record(
    'I5',
    '预检响应 Access-Control-Expose-Headers 必须含 4 个动态头',
    configured && missing.length === 0,
    configured ? `缺失=${missing.join(',') || '无'}` : `未返回 Access-Control-Expose-Headers（CORS 未启用？Origin 是否为 ${'http://localhost:8090'}）`,
  );
}

// ── 汇总 ──────────────────────────────────────────────────────────────
console.log('=== 不可见断言（连真实 API）===\n');
console.log(`目标 ${BASE}\n`);
for (const r of results) {
  console.log(`  [${r.ok ? 'PASS' : 'FAIL'}] ${r.id}  ${r.title}`);
  if (!r.ok || process.env.FAZE_VERBOSE === '1') console.log(`         ${r.detail}`);
}

const failed = results.filter((r) => !r.ok);
if (failed.length === 0) {
  console.log(`\n[OK] ${results.length} 条不可见断言全部通过。`);
  process.exit(0);
}
console.error(`\n[FAIL] ${failed.length}/${results.length} 条不通过。`);
console.error('这些断言测的都是「界面上看不出来、但坏了也说不清」的东西——不要因为它们不显眼就跳过。');
process.exit(1);

/**
 * 为什么 CI 暂未接：
 *   本脚本需要一个**运行中的 API 进程**。CI 的 backend job 现在只跑 `mvn test`，
 *   要接就得再起一次 Spring Boot（等健康检查、等 Flyway、还要保证与测试用同一个库），
 *   这会让 CI 多出一个「启动失败」的失败源。
 *   而「起了服务却跑不通」和「代码错了」在 CI 日志里长得一样，都属于「红了但不知道修哪里」。
 *   所以本脚本定位为**本地验收工具**（每轮验收前跑一次，证据附在验收文档里），
 *   等 G9 把 CI 装配稳定后再评估是否接入。
 */
