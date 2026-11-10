/**
 * 事实生成总入口 —— 依次跑全部 facts 生成器。
 *
 * 职责：
 *   把 `scripts/facts/` 下的各个生成器按固定顺序跑一遍，产出 `docs/facts/*.json`。
 *   每个生成器独立可跑，本文件只做编排与汇总，**不实现任何抽取逻辑**。
 *
 * 调用方：
 *   - `npm run facts`（全量，需要本机 MySQL）
 *   - `npm run facts:nodb` / `node scripts/facts/run-all.mjs --skip-db`（跳过表结构）
 *   - `scripts/facts/check-facts.mjs`（先跑本文件，再判 git diff）
 *
 * 数据来源：各生成器自己负责；本文件不读任何业务文件。
 *
 * 注意事项：
 *   - **顺序有意义**：先源码类（api / error-codes / i18n / pages），最后数据库类（tables）。
 *     数据库类可能因本机没起 MySQL 而失败，放最后不会挡住前三类。
 *   - `--skip-db` 只跳过数据库类，其余照跑。CI 的 mobile job 没有 MySQL service，
 *     用这个模式；backend job 有 MySQL，跑全量。
 *   - 单个生成器失败不中断其余（收集后统一报），因为「少一份事实」比「一份都没有」好，
 *     但最终退出码仍为 1，不允许静默通过。
 *
 * 退出码：0 全部成功 / 1 至少一个失败
 */

import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

const HERE = import.meta.dirname;
const SKIP_DB = process.argv.includes('--skip-db');

/** 生成器清单。`needsDb` 决定 --skip-db 时是否跳过。 */
const GENERATORS = [
  { file: 'api.mjs', label: '接口事实', needsDb: false },
  { file: 'error-codes.mjs', label: '错误码事实', needsDb: false },
  { file: 'extract-i18n.mjs', label: 'i18n 事实', needsDb: false },
  { file: 'extract-pages.mjs', label: '页面事实', needsDb: false },
  { file: 'tables.mjs', label: '表结构事实', needsDb: true },
];

const results = [];

for (const g of GENERATORS) {
  const path = join(HERE, g.file);
  if (!existsSync(path)) {
    results.push({ ...g, ok: false, reason: '文件不存在' });
    continue;
  }
  if (g.needsDb && SKIP_DB) {
    results.push({ ...g, ok: true, skipped: true });
    continue;
  }
  process.stdout.write(`\n──── ${g.label}（${g.file}）────\n`);
  try {
    execFileSync(process.execPath, [path], { stdio: 'inherit' });
    results.push({ ...g, ok: true });
  } catch (err) {
    results.push({ ...g, ok: false, reason: `退出码 ${err.status ?? '?'}` });
  }
}

console.log('\n════ 事实生成汇总 ════');
for (const r of results) {
  const mark = r.ok ? (r.skipped ? '跳过' : '成功') : '失败';
  const extra = r.skipped ? '（--skip-db：本机未连数据库）' : (r.reason ?? '');
  console.log(`  ${mark}  ${r.label.padEnd(10)} ${extra}`);
}

const failed = results.filter((r) => !r.ok);
if (failed.length > 0) {
  console.error(`\n[FAIL] ${failed.length} 个生成器失败：${failed.map((f) => f.file).join(', ')}`);
  process.exit(1);
}
console.log('\n全部事实已生成到 docs/facts/');
