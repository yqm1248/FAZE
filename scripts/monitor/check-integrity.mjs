/**
 * 数据完整性巡检执行器 —— 跑 `data-integrity.sql`，逐项判断并通过/失败。
 *
 * 职责：
 *   把 `scripts/monitor/data-integrity.sql` 的 20 条只读检查跑一遍，输出每项条数，
 *   并按 `known-issues.json` 的台账判断「超过允许值」的项。
 *
 * 调用方：
 *   - 人工：`npm run check:integrity`
 *   - CI：`backend` job（那里有 MySQL service，库由 Flyway 现建 + seed 导入）
 *   - 每轮验收前 / 发布前
 *
 * 数据来源：`faze` 库（只读）。**本脚本不执行任何写操作**，SQL 文件里也没有写操作。
 *
 * 为什么需要「已知非零项台账」：
 *   `data-integrity.sql` 的判读规则是「n 必须为 0」。但当前有两项因根因未消除而恒为 1
 *   （A12 实名两列矛盾、A20 法条快照过期）。如果没有台账，只有两种坏结局：
 *   ① CI 长期红 → 人开始忽略红灯；② 把整条检查关掉 → 真问题也看不见。
 *   台账的作用是把「红灯」和「已知红灯」分开，并强制每一条写清**关闭条件**。
 *
 * 注意事项：
 *   - 用 `≤` 而不是 `=` 比对允许值：CI 的库是空库建出来的，那两项实测 0 条也必须通过。
 *   - **SQL 里出现写操作就应当拒绝执行**（本脚本只读，不额外做检查，靠 code review 守）。
 *   - 连接参数从环境变量读（`FAZE_DB_HOST/PORT/USER/PASSWORD/NAME`），与 `tables.mjs` 同口径。
 *
 * 退出码：0 全部通过 / 1 有检查项超过允许值 / 2 连不上库或 SQL 执行失败
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const SQL_FILE = join(REPO, 'scripts', 'monitor', 'data-integrity.sql');
const KNOWN_FILE = join(REPO, 'scripts', 'monitor', 'known-issues.json');

const DB = {
  host: process.env.FAZE_DB_HOST ?? '127.0.0.1',
  port: process.env.FAZE_DB_PORT ?? '3306',
  user: process.env.FAZE_DB_USER ?? 'faze_app',
  password: process.env.FAZE_DB_PASSWORD ?? '123456',
  name: process.env.FAZE_DB_NAME ?? 'faze',
};

if (!existsSync(SQL_FILE)) {
  console.error(`[FAIL] 找不到巡检 SQL：${SQL_FILE}`);
  process.exit(2);
}

const known = existsSync(KNOWN_FILE) ? JSON.parse(readFileSync(KNOWN_FILE, 'utf8')) : { maxAllowed: {} };

/** 跑整个 SQL 文件，返回原始 stdout（`-B` 制表符分隔，无表头）。 */
function runSqlFile() {
  const args = [
    `--host=${DB.host}`,
    `--port=${DB.port}`,
    `--user=${DB.user}`,
    '--default-character-set=utf8mb4',
    '-N',
    '-B',
    '--raw',
    DB.name,
  ];
  try {
    return execFileSync('mysql', args, {
      input: readFileSync(SQL_FILE),
      encoding: 'utf8',
      env: { ...process.env, MYSQL_PWD: DB.password },
      maxBuffer: 32 * 1024 * 1024,
    });
  } catch (err) {
    console.error('[FAIL] 巡检 SQL 执行失败：' + (err.message ?? ''));
    if (err.stderr) console.error(String(err.stderr).slice(0, 2000));
    process.exit(2);
  }
}

const raw = runSqlFile().replace(/\r\n/g, '\n');

// ── 解析输出 ──────────────────────────────────────────────────────────
// 两类行：
//   · section：单列，形如 `=== 订单状态与凭据 ===`
//   · 检查项：两列，`A01 描述 \t 3`
const sections = [];
const results = [];
let currentSection = '(未分组)';

for (const line of raw.split('\n')) {
  if (line.trim() === '') continue;
  const cells = line.split('\t');
  if (cells.length === 1) {
    currentSection = cells[0].replace(/^=+\s*|\s*=+$/g, '');
    sections.push(currentSection);
    continue;
  }
  const [chk, nText] = cells;
  const m = /^([A-Z]\d{2})\b/.exec(chk.trim());
  if (!m) continue;
  results.push({ id: m[1], label: chk.trim(), n: Number(nText), section: currentSection });
}

if (results.length === 0) {
  console.error('[FAIL] 没解析到任何检查项。SQL 的 SELECT 别名格式可能变了（应为 `SELECT \'Axx 描述\' AS chk, COUNT(*) AS n ...`）。');
  process.exit(2);
}

// ── 判读 ──────────────────────────────────────────────────────────────
const maxAllowed = known.maxAllowed ?? {};
const violations = [];
const exempted = [];

for (const r of results) {
  const allow = maxAllowed[r.id];
  const allowed = allow === undefined ? 0 : allow.n;
  if (r.n > allowed) {
    violations.push({ ...r, allowed });
  } else if (r.n > 0) {
    exempted.push({ ...r, allowed, reason: allow.reason, closeWhen: allow.closeWhen });
  }
}

// ── 报告 ──────────────────────────────────────────────────────────────
console.log('=== 数据完整性巡检（DIRT 检查）===\n');
console.log(`检查项        ${results.length} 条 / ${sections.length} 组`);
console.log(`库            ${DB.user}@${DB.host}:${DB.port}/${DB.name}`);
console.log(`已知台账      ${Object.keys(maxAllowed).length} 项\n`);

let lastSection = '';
for (const r of results) {
  if (r.section !== lastSection) {
    console.log(`── ${r.section}`);
    lastSection = r.section;
  }
  const allow = maxAllowed[r.id]?.n ?? 0;
  const mark = r.n > allow ? 'FAIL' : r.n > 0 ? '允许' : ' OK ';
  console.log(`  [${mark}] ${r.id}  n=${r.n}${allow > 0 ? `（允许 ≤${allow}）` : ''}  ${r.label.replace(/^[A-Z]\d{2}\s*/, '')}`);
}

if (exempted.length > 0) {
  console.log(`\n已知非零项（${exempted.length} 项，均有台账与关闭条件）：`);
  for (const e of exempted) {
    console.log(`  · ${e.id} n=${e.n}（允许 ≤${e.allowed}）`);
    console.log(`      为什么不现在清：${e.reason}`);
    console.log(`      关闭条件：${e.closeWhen}`);
  }
}

if (violations.length === 0) {
  console.log('\n[OK] 没有超过允许值的检查项。');
  process.exit(0);
}

console.error(`\n[FAIL] ${violations.length} 项超过允许值：`);
for (const v of violations) {
  console.error(`  ${v.id}  n=${v.n}（允许 ≤${v.allowed}）  ${v.label}`);
}
console.error('\n处置：DIRT = 代码正确、库里数据违规。修法永远不是改文档——');
console.error('      · 测试造数 → 删行（先备份）');
console.error('      · 代码缺陷持续产生 → 先修代码，再清存量');
console.error('      · 历史快照过期 → 重算快照');
process.exit(1);
