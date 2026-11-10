/**
 * 事实同步门禁 —— 重跑全部生成器后，`docs/facts/` 里的文件必须一字不变。
 *
 * 职责：
 *   这是「事实与代码/数据库不同步就拦住」的**唯一执行点**。做法：
 *     ① 给 `docs/facts/` 生成前的内容快照（路径 → SHA256）；
 *     ② 跑 `run-all.mjs` 重新生成；
 *     ③ 再生成一次快照，逐文件比对；
 *     ④ 有任何新增 / 删除 / 内容变化 → 退出 1。
 *
 * 调用方：
 *   - CI：`mobile` job 跑 `--skip-db`（无 MySQL），`backend` job 跑全量（有 MySQL）
 *   - 本地：`npm run check:facts`
 *
 * 数据来源：不自己读业务文件；只调用 run-all.mjs 并比较生成物。
 *
 * 为什么用「前后快照」而不是 `git status`：
 *   第一版用的是 `git status --porcelain docs/facts/`，在 CI 里没问题（checkout 是干净的），
 *   但本地开发时**未提交的改动会被误报成漂移**——刚生成完还没 `git add` 就红了一片。
 *   那样的门禁会训练人「跑一次看了一眼红就提交」，反而丧失意义。
 *   改为内容快照后，本地与 CI 行为一致：**只关心「生成器输出是否与磁盘上现有文件一致」**。
 *   至于「磁盘上的文件与 git 里的一致」由提交动作保证，不归本脚本管。
 *
 * 注意事项：
 *   - **`docs/facts/*.json` 里禁止出现「生成当天日期」这类易变字段**。曾经三个生成器
 *     都写 `generatedAt: 当天`，本门禁在次日必然失败——内容没变、日期变了，把门禁变成
 *     日历。现已统一改为 `sourceDigest`（源内容稳定哈希）。
 *   - 只判 `docs/facts/` 目录，不碰其他目录：开发中其他文件有未提交改动很正常。
 *
 * 退出码：0 一致 / 1 漂移 / 2 生成器本身失败
 */

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const HERE = import.meta.dirname;
const REPO = join(HERE, '..', '..');
const FACTS_DIR = join(REPO, 'docs', 'facts');
const SKIP_DB = process.argv.includes('--skip-db');

/** 对目录下所有文件做内容快照：文件名 → SHA256。不递归（facts 目录是平铺的）。 */
function snapshot() {
  const map = new Map();
  if (!existsSync(FACTS_DIR)) return map;
  for (const name of readdirSync(FACTS_DIR).sort()) {
    if (!name.endsWith('.json') && !name.endsWith('.md')) continue;
    const buf = readFileSync(join(FACTS_DIR, name));
    map.set(name, createHash('sha256').update(buf).digest('hex'));
  }
  return map;
}

const before = snapshot();

// ── 重新生成 ──────────────────────────────────────────────────────────
try {
  execFileSync(process.execPath, [join(HERE, 'run-all.mjs'), ...(SKIP_DB ? ['--skip-db'] : [])], {
    stdio: 'inherit',
  });
} catch {
  console.error('\n[FAIL] 事实生成器本身失败，无法判定是否漂移。先修生成器。');
  process.exit(2);
}

const after = snapshot();

// ── 比对 ──────────────────────────────────────────────────────────────
const added = [...after.keys()].filter((k) => !before.has(k));
const removed = [...before.keys()].filter((k) => !after.has(k));
const changed = [...after.keys()].filter((k) => before.has(k) && before.get(k) !== after.get(k));

const dirty = added.length + removed.length + changed.length;

if (dirty === 0) {
  console.log('\n[OK] docs/facts/ 与源码/数据库一致（重跑后一字未变）');
  process.exit(0);
}

console.error('\n[FAIL] docs/facts/ 与当前代码或数据库不一致 —— 事实已过期。');
console.error('       下面这些文件在重跑后发生了变化：\n');
for (const f of added) console.error(`  新增  docs/facts/${f}`);
for (const f of removed) console.error(`  消失  docs/facts/${f}`);
for (const f of changed) console.error(`  变动  docs/facts/${f}`);

console.error('\n处理办法：');
console.error('  ① 这些变化是预期的（改了接口/表/文案/页面）→ 把重跑后的文件一起提交，它们就是新的真相快照；');
console.error('  ② 变化来路不明 → 查是谁改了源码或迁移却漏了重跑事实；');
console.error('  ③ 只有某个源指纹字段在变 → 说明生成器里写了易变内容（如当天日期），那是生成器的 bug。');
process.exit(1);
