/**
 * 每日/每轮巡检编排器（回归清单 R-04/R-05/R-06/R-17/R-19 的落地入口）。
 *
 * 职责：
 *   把「每天该跑一遍」的检查串成一条命令，逐项输出通过/失败与耗时，
 *   并把完整输出写到 `logs/daily-check-YYYY-MM-DD.log`（`logs/` 已 gitignore）。
 *
 * 为什么不直接用 CI：
 *   CI 只在 push / PR 时跑。而**数据类问题是在两次提交之间长出来的**：
 *   验收造数没清、手工改库、第三方回调重复投递、脏数据跨天累积。
 *   这类问题不会让任何一次构建变红，只有「每天跑一次」才发现。
 *
 * 用法：
 *   node scripts/monitor/daily-check.mjs              # 文件类 + 数据库类
 *   node scripts/monitor/daily-check.mjs --with-api   # 额外跑「不可见断言」（需要 8081 在线）
 *   node scripts/monitor/daily-check.mjs --db-only    # 只跑数据库类
 *
 * 退出码：
 *   0 全部通过 / 1 至少一项失败 / 2 编排本身出错
 *
 * 已知局限：
 *   - `--with-api` 那组需要**运行中的 API 进程**，脚本本身不起服务；
 *     服务没起时结果是「跳过」而不是「失败」——因为「没起服务」和「服务坏了」
 *     在日志里长得一样，把它判成失败只会训练人忽略红灯。
 *   - 本脚本只编排与汇总，不重新实现任何检查逻辑；每项的真伪由各自脚本负责。
 */

import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const withApi = process.argv.includes('--with-api');
const dbOnly = process.argv.includes('--db-only');

const checks = [];

if (!dbOnly) {
  // 注意这里**不带** `--skip-db`：每日巡检在开发者本机跑，库与代码本应一致；
  // 表结构事实对不上正是要查的东西。CI 里那道用 --skip-db 是因为 CI 库的
  // information_schema 表述可能与本地不同（见 ci.yml 注释），两处口径不同是有意的。
  checks.push({ name: '事实 json 与源码/数据库一致', script: 'scripts/facts/check-facts.mjs', args: [] });
  checks.push({ name: '文档受控区块', script: 'scripts/facts/render-docs.mjs', args: ['--check'] });
  checks.push({ name: '基线指纹', script: 'scripts/facts/check-baseline.mjs', args: [] });
  checks.push({ name: '设计稿与页面映射', script: 'scripts/facts/check-pages.mjs', args: [] });
  checks.push({ name: '承诺清单', script: 'scripts/facts/check-contracts.mjs', args: ['--check'] });
  checks.push({ name: '文案内部口吻（R-11）', script: 'scripts/check-copy-tone.mjs', args: [] });
  checks.push({ name: '字段写入路径（R-15）', script: 'scripts/facts/check-write-paths.mjs', args: [] });
}

// 数据库类（R-19 造数残留、R-04/R-05 一致性都在 data-integrity.sql 的 A01~A21 里）
checks.push({ name: '数据完整性巡检 21 条（含 R-19）', script: 'scripts/monitor/check-integrity.mjs', args: [] });

// 不可见断言类（R-17 等）——只在显式要求时跑，且需要 API 在线
if (withApi) {
  checks.push({ name: '不可见断言（R-17 等）', script: 'scripts/monitor/check-invisible.mjs', args: [], needsApi: true });
}

mkdirSync(join(REPO, 'logs'), { recursive: true });
// 按**本地日期**命名（不是 UTC）：日志是给人翻的，跨时区差 8 小时会出现「今天的日志
// 叫昨天」这种怪事。与「库内时间存 UTC」不冲突——日志文件名不是时间字段。
const now = new Date();
const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
const logFile = join(REPO, 'logs', `daily-check-${today}.log`);
const lines = [];
const record = (s) => {
  lines.push(s);
  console.log(s);
};

record(`\n════ 每日巡检 ${new Date().toISOString()} ════`);
record(`范围：${dbOnly ? '仅数据库' : withApi ? '文件 + 数据库 + 不可见断言' : '文件 + 数据库'}`);

const results = [];
for (const c of checks) {
  const started = Date.now();
  let status = '通过';
  let detail = '';
  try {
    detail = execFileSync(process.execPath, [join(REPO, c.script), ...c.args], {
      cwd: REPO,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      env: process.env,
    });
  } catch (e) {
    const out = (e.stdout ?? '') + (e.stderr ?? '');
    detail = out;
    const msg = String(e.message ?? '');
    // 「服务没起」不算失败：这个区分必须显式做，否则每天红一次人就不看了
    if (c.needsApi && /ECONNREFUSED|fetch failed|API 不可用|连不上/.test(out + msg)) {
      status = '跳过（API 未运行）';
    } else {
      status = '失败';
    }
  }
  const ms = Date.now() - started;
  results.push({ name: c.name, status, ms });
  record(`\n── ${c.name} → ${status}（${ms}ms）`);
  if (status !== '通过') {
    const tail = detail.trim().split(/\r?\n/).slice(-20).join('\n');
    record(tail);
  }
}

const failed = results.filter((r) => r.status === '失败');
const skipped = results.filter((r) => r.status.startsWith('跳过'));

record(`\n════ 汇总 ════`);
for (const r of results) record(`  ${r.status.padEnd(14, ' ')} ${r.name}（${r.ms}ms）`);
record(`\n通过 ${results.length - failed.length - skipped.length} / 失败 ${failed.length} / 跳过 ${skipped.length}`);

appendFileSync(logFile, lines.join('\n') + '\n');
console.log(`\n日志已追加：${logFile}`);

if (failed.length > 0) {
  console.error(`\n[FAIL] ${failed.length} 项失败：${failed.map((r) => r.name).join('、')}`);
  process.exit(1);
}
console.log('\n[OK] 每日巡检全部通过。');
