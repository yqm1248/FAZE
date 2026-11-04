/*
 * 职责：第 1 段管理端 21 个接口的独立复核（Lead 侧，不依赖实现者自述）。
 * 调用方：deploy/verify-seg1.ps1（H1 的锚点验收）；也可单独 `node deploy/verify-admin-api.mjs`。
 * 数据来源：真实 faze-api(8081) + 真实 MySQL(faze) + 真实 RuoYi 登录态（CDP 取 Cookie `Admin-Token`）。
 * 注意：
 *   1. 几乎全是读操作；唯一的写是「同一个 Idempotency-Key 重放一次充值」，不会二次入账。
 *   2. **必须用 curl.exe**：PowerShell 的 Invoke-WebRequest 发这个 `Authorization` 头会失败（实测 401），
 *      这是 PowerShell 的行为，不是接口问题。
 *   3. 前置：Chrome 以 `--remote-debugging-port=9222` 启动、且已登录 RuoYi 后台。
 *      取不到 token 时整脚本退出码 2（环境问题），不算「接口失败」。
 *   4. 退出码：0 全通过 / 1 有失败项 / 2 环境不可用。
 */
import { execFileSync } from 'node:child_process';

const BASE = 'http://127.0.0.1:8081';
const RUOYI = 'http://127.0.0.1:8080';

/**
 * 取 RuoYi 的管理端 token。
 *
 * 为什么不再从 CDP 浏览器读 `Admin-Token` Cookie：RuoYi 的 token 只在 Redis 里活 30 分钟，
 * 而验收脚本常常是「几十个浏览器脚本跑完之后」才轮到这一项——实测 2026-11-05 就因为
 * cookie 过期导致 23 项全 401，看起来像接口坏了，其实是登录态过期（假失败最误导人）。
 *
 * 改成直接调 RuoYi 的 `/login`：它拿的是真实签发的 token，不依赖浏览器里是否登录。
 * `admin/admin123` 是 RuoYi 初始化 SQL 自带的本地开发账号，只在本机 dev 环境有意义。
 *
 * @returns {Promise<string>} 管理端 token
 * @throws {Error} RuoYi 未启动或凭据不对时抛出（外层按环境问题处理）
 */
async function getToken() {
  const res = await fetch(`${RUOYI}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' }),
  });
  const json = await res.json();
  if (!json.token) {
    throw new Error('RuoYi /login 没返回 token：code=' + json.code + ' msg=' + json.msg);
  }
  return json.token;
}

function curl(path, { method = 'GET', token, headers = {}, body } = {}) {
  const args = ['-s', '--max-time', '15', '-w', '\n__STATUS__%{http_code}', '-X', method, BASE + path];
  if (token) args.push('-H', `Authorization: Bearer ${token}`);
  for (const [k, v] of Object.entries(headers)) args.push('-H', `${k}: ${v}`);
  if (body !== undefined) {
    args.push('-H', 'Content-Type: application/json; charset=utf-8');
    args.push('--data-binary', JSON.stringify(body));
  }
  const raw = execFileSync('curl.exe', args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  const idx = raw.lastIndexOf('\n__STATUS__');
  const status = Number(raw.slice(idx + 11).trim());
  const text = raw.slice(0, idx).trim();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON */ }
  return { status, text, json };
}

const results = [];
function check(id, name, r, predicate, note = '') {
  const ok = predicate(r);
  results.push({ id, name, ok, status: r.status, code: r.json?.code, note });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${id}  ${name}  [HTTP ${r.status} code=${r.json?.code ?? '-'}]  ${note}`);
  if (!ok) console.log(`       ↳ ${r.text.slice(0, 240)}`);
}

const is200 = r => r.status === 200 && r.json?.code === 200;
const isList = r => is200(r) && Array.isArray(r.json.rows) && typeof r.json.total === 'number';
const isErr = expected => r => r.json?.code === expected;

let token;
try {
  token = await getToken();
} catch (e) {
  console.error(`[ENV] 取不到 RuoYi 登录态：${e.message}`);
  process.exit(2);
}
console.log(`RuoYi token 已取到（长度 ${token.length}）\n`);

// ── 鉴权桥（F1 的产物，必须一直成立）──────────────────────────────────────
check('AUTH-1', '管理端无 token → HTTP200 + code401', curl('/api/v1/admin/users'),
  r => r.status === 200 && r.json?.code === 401);
check('AUTH-2', '管理端带 RuoYi token → 200', curl('/api/v1/admin/dashboard/ping', { token }), is200);

// ── A-03 用户 ────────────────────────────────────────────────────────────
const users = curl('/api/v1/admin/users?pageNum=1&pageSize=10', { token });
check('A-03-1', '用户列表 rows/total', users, isList);
check('A-03-2', '行字段冻结集（大写枚举 + 脱敏 + 金额数字）', users, r => {
  const u = r.json?.rows?.[0];
  return !u || (typeof u.id === 'string' && typeof u.phoneMasked === 'string'
    && ['NONE', 'PENDING', 'APPROVED', 'REJECTED'].includes(u.realnameStatus)
    && ['NORMAL', 'BANNED', 'CANCELLED'].includes(u.accountStatus)
    && typeof u.totalConsumeCents === 'number');
});
const firstUser = users.json?.rows?.[0];
if (firstUser) {
  check('A-03-3', '用户详情', curl(`/api/v1/admin/users/${firstUser.id}`, { token }),
    r => is200(r) && !!r.json.data);
}

// ── A-04 认证审核 ────────────────────────────────────────────────────────
const vers = curl('/api/v1/admin/verifications?pageNum=1&pageSize=10', { token });
check('A-04-1', '实名+企业合并队列 rows/total', vers, isList);
check('A-04-2', '行含 type 与脱敏字段', vers, r => {
  const v = r.json?.rows?.[0];
  return !v || (['REALNAME', 'ENTERPRISE'].includes(v.type) && typeof v.userId === 'string');
});

// ── A-05 入驻审核 ────────────────────────────────────────────────────────
const apps = curl('/api/v1/admin/lawyer-applications?pageNum=1&pageSize=10', { token });
check('A-05-1', '待审队列 rows/total', apps, isList);
const firstApp = apps.json?.rows?.[0];
if (firstApp) {
  check('A-05-2', '四步详情 + 审核流水', curl(`/api/v1/admin/lawyer-applications/${firstApp.id}`, { token }),
    r => is200(r) && !!r.json.data?.form && Array.isArray(r.json.data?.logs));
}

// ── A-06 律师 ────────────────────────────────────────────────────────────
const lawyers = curl('/api/v1/admin/lawyers?pageNum=1&pageSize=10', { token });
check('A-06-1', '律师列表 rows/total', lawyers, isList);
const firstLawyer = lawyers.json?.rows?.[0];
if (firstLawyer) {
  check('A-06-2', '律师详情', curl(`/api/v1/admin/lawyers/${firstLawyer.id}`, { token }),
    r => is200(r) && 'consultPriceCents' in (r.json.data ?? {}));
}

// ── A-07 钱包（含锚点 1-A4 的幂等重放）───────────────────────────────────
const walletUser = '3';
const wallet = curl(`/api/v1/admin/wallets/${walletUser}`, { token });
check('A-07-1', '钱包概览（余额来自流水汇总）', wallet,
  r => is200(r) && typeof r.json.data?.balanceCents === 'number',
  `balanceCents=${wallet.json?.data?.balanceCents}`);

const idem = `verify:${Date.now()}`;
const bizNo = `VERIFY-${Date.now()}`;
const pay1 = curl(`/api/v1/admin/wallets/${walletUser}/recharge`, {
  method: 'POST', token, headers: { 'Idempotency-Key': idem },
  body: { amountCents: 1, reason: 'verify-seg1 独立复核（1 分）', bizNo, remark: 'verify-admin-api' }
});
check('A-07-2', '充值 1 分成功', pay1, r => is200(r) && r.json.data?.duplicated === false);
const pay2 = curl(`/api/v1/admin/wallets/${walletUser}/recharge`, {
  method: 'POST', token, headers: { 'Idempotency-Key': idem },
  body: { amountCents: 1, reason: 'verify-seg1 独立复核（1 分）', bizNo, remark: 'verify-admin-api' }
});
check('A-07-3', '同幂等键重放 → duplicated=true', pay2, r => is200(r) && r.json.data?.duplicated === true);
check('A-07-4', '幂等不重复入账（余额相等）', pay2,
  () => pay1.json?.data?.balanceCents === pay2.json?.data?.balanceCents);
check('A-07-5', '调账 delta=0 → 405006', curl(`/api/v1/admin/wallets/${walletUser}/adjust`, {
  method: 'POST', token, headers: { 'Idempotency-Key': `zero:${Date.now()}` },
  body: { deltaCents: 0, reason: 'verify-seg1 零调账' }
}), isErr(405006));
check('A-07-6', '流水分页', curl(`/api/v1/admin/balance-flows?userId=${walletUser}&pageNum=1&pageSize=5`, { token }), isList);

// ── A-08 系统参数 ────────────────────────────────────────────────────────
const cfgs = curl('/api/v1/admin/sys-configs', { token });
check('A-08-1', '参数列表', cfgs, r => is200(r) && Array.isArray(r.json.data));
check('A-08-2', 'editable 是 boolean', cfgs, r => (r.json.data ?? []).every(c => typeof c.editable === 'boolean'),
  `共 ${cfgs.json?.data?.length} 项`);
check('A-08-3', 'keys 过滤', curl('/api/v1/admin/sys-configs?keys=pay_pwd_max_fail', { token }),
  r => is200(r) && r.json.data.length === 1);

// ── A-09 对账 ────────────────────────────────────────────────────────────
const summary = curl('/api/v1/admin/reconciliation/summary', { token });
check('A-09-1', '巡检摘要', summary, r => is200(r) && typeof r.json.data?.checkedOwners === 'number',
  `checked=${summary.json?.data?.checkedOwners} diffOwners=${summary.json?.data?.diffOwners}`);
check('A-09-2', '差异列表', curl('/api/v1/admin/reconciliation/diffs', { token }),
  r => is200(r) && Array.isArray(r.json.data));
check('A-09-3', '手动巡检', curl('/api/v1/admin/reconciliation/run', { method: 'POST', token }),
  r => is200(r) && typeof r.json.data?.checkedOwners === 'number');

// ── 汇总 ────────────────────────────────────────────────────────────────
const pass = results.filter(r => r.ok).length;
console.log(`\n========== 管理端接口复核：${pass}/${results.length} 通过 ==========`);
const failed = results.filter(r => !r.ok);
if (failed.length) {
  console.log('失败项：');
  for (const f of failed) console.log(` - ${f.id} ${f.name} [HTTP ${f.status} code=${f.code}]`);
  process.exit(1);
}
process.exit(0);
