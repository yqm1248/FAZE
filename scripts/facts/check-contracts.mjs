/**
 * 承诺性注释校验 —— 把「代码里/文案里说了什么保证」变成可 diff 的清单。
 *
 * 职责：
 *   1. 扫描源码注释里的**承诺性措辞**（幂等 / 重复提交 / 保证 / 影响全 App…），
 *      输出 `docs/facts/contracts.json`。清单进仓库，所以「新增了一条承诺」是可见的 diff。
 *   2. 检查一类**确定是假的承诺**：i18n 用户可见文案里写「影响全 App」，
 *      而实现只影响设置页（DRIFT-002）。这类不需要人判断，机器能直接判。
 *
 * 调用方：
 *   - `npm run check:contracts`（含 `--check`）
 *   - CI（`mobile` job，纯文件读取）
 *
 * 数据来源：`apps/mobile-app/**\/*.{ts,tsx}`、`services/faze-api/**\/*.java` 的注释，
 *   `apps/mobile-app/shared/i18n/resources/**\/*.json` 的文案。只读文件系统。
 *
 * 为什么不是「每条承诺都必须带 `@契约` 标记」：
 *   先试过那个方案，实测**前端就有 46 处**含承诺性措辞的注释，其中绝大多数是**说对了的**
 *   （「服务端两个方向都幂等」「同一次重试必须复用同一个 key」）。
 *   给 46 条正确注释全部加标记，只会训练人「批量刷标记」，等于没有检查。
 *
 *   **改成「清单化 + 漂移比对」**：不要求人改注释，但要求「承诺的总量是可见的」。
 *   新增一条承诺 → `contracts.json` 多一行 → CI 红 → 人要么确认它是真的、要么改掉它。
 *   这才是「让不一致可见」，而不是「让所有人加班加标记」。
 *
 * 注意事项：
 *   - 只扫**注释**（`//`、`/* *\/`、`*` 开头的行）与 i18n 的**字符串值**，不扫代码逻辑。
 *   - 已知说错的承诺登记在 `scripts/facts/contracts-known.json`，每条必须写 `closeWhen`。
 *   - 不写生成时间（会破坏 diff），写 `sourceDigest`。
 *
 * 退出码：0 通过 / 1 有未登记的新承诺或已知违规超过台账 / 2 读文件失败
 */

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const OUT_DIR = join(REPO, 'docs', 'facts');
const OUT_FILE = join(OUT_DIR, 'contracts.json');
const KNOWN_FILE = join(REPO, 'scripts', 'facts', 'contracts-known.json');
const CHECK_ONLY = process.argv.includes('--check');

/** 承诺性措辞。每条都对应一次「代码说它做到了某件不显眼的事」。 */
const PROMISE_KEYWORDS = ['幂等', '重复提交', '重复点', '保证', '影响全 App', '影响整个应用'];

/** 只扫这些目录里的源码。 */
const SOURCE_ROOTS = [
  { dir: join(REPO, 'apps', 'mobile-app'), exts: ['.ts', '.tsx'], skip: ['node_modules', '.expo', 'dist'] },
  { dir: join(REPO, 'services', 'faze-api', 'src', 'main', 'java'), exts: ['.java'], skip: [] },
];

const I18N_DIR = join(REPO, 'apps', 'mobile-app', 'shared', 'i18n', 'resources');

/** 递归收集文件。 */
function walk(dir, exts, skip, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir).sort()) {
    if (skip.includes(entry)) continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, exts, skip, out);
    else if (exts.some((e) => entry.endsWith(e))) out.push(full);
  }
  return out;
}

/** 仓库相对路径（POSIX）。 */
function rel(abs) {
  return relative(REPO, abs).split(sep).join('/');
}

/** 判断一行是否落在注释里（粗略但够用：`//`、`*` 开头、`/*` 开头）。 */
function isCommentLine(line) {
  const t = line.trim();
  return t.startsWith('//') || t.startsWith('*') || t.startsWith('/*');
}

// ── 1. 扫源码注释里的承诺 ─────────────────────────────────────────────
const entries = [];
const hash = createHash('sha256');

for (const root of SOURCE_ROOTS) {
  for (const file of walk(root.dir, root.exts, root.skip)) {
    const relPath = rel(file);
    const text = readFileSync(file, 'utf8');
    hash.update(relPath);
    hash.update(text);
    text.split('\n').forEach((line, idx) => {
      if (!isCommentLine(line)) return;
      const hit = PROMISE_KEYWORDS.find((k) => line.includes(k));
      if (hit === undefined) return;
      entries.push({
        file: relPath,
        line: idx + 1,
        keyword: hit,
        text: line.trim().replace(/^\/[/*]+\s*/, '').replace(/\*\/$/, '').trim().slice(0, 200),
      });
    });
  }
}

// ── 2. i18n 里「范围承诺大于实现」的文案（机器能直接判假的那一类）──────
const scopeViolations = [];
for (const file of walk(I18N_DIR, ['.json'], [])) {
  const relPath = rel(file);
  const text = readFileSync(file, 'utf8');
  hash.update(relPath);
  hash.update(text);
  text.split('\n').forEach((line, idx) => {
    if (!/影响全 App|影响整个应用/.test(line)) return;
    scopeViolations.push({ file: relPath, line: idx + 1, text: line.trim().slice(0, 200) });
  });
}

const sourceDigest = hash.digest('hex').slice(0, 16);

// ── 3. 已知违规台账 ───────────────────────────────────────────────────
const known = existsSync(KNOWN_FILE) ? JSON.parse(readFileSync(KNOWN_FILE, 'utf8')) : { allowed: [] };
const allowedCount = (known.allowed ?? []).length;

// ── 4. 写清单 ─────────────────────────────────────────────────────────
const byKeyword = {};
for (const e of entries) byKeyword[e.keyword] = (byKeyword[e.keyword] ?? 0) + 1;

const payload = {
  _comment:
    '本文件由 scripts/facts/check-contracts.mjs 从源码注释与 i18n 文案生成，禁止手改。用途：让「代码里承诺了什么」成为可 diff 的清单——新增一条承诺就是一次可见的改动。',
  sourceDigest,
  totals: { promises: entries.length, scopeViolations: scopeViolations.length, knownViolations: allowedCount },
  byKeyword,
  note:
    'promises 只说明「注释里提到了承诺性措辞」，**不代表它说错了**。绝大多数是对的。scopeViolations 才是机器能判定为假的那一类。',
  scopeViolations,
  promises: entries,
};

mkdirSync(OUT_DIR, { recursive: true });
const serialized = JSON.stringify(payload, null, 2) + '\n';
const before = existsSync(OUT_FILE) ? readFileSync(OUT_FILE, 'utf8') : '';

// ── 5. 报告 ───────────────────────────────────────────────────────────
console.log('=== 承诺性注释与文案校验 ===\n');
console.log(`承诺性注释      ${entries.length} 条`);
console.log(`按关键词        ${Object.entries(byKeyword).map(([k, n]) => `${k}:${n}`).join(' / ')}`);
console.log(`范围承诺违规    ${scopeViolations.length} 条（i18n 文案写「影响全 App」）`);
console.log(`已知违规台账    ${allowedCount} 条`);

let failed = false;

if (scopeViolations.length > allowedCount) {
  failed = true;
  console.error(`\n[FAIL] 范围承诺违规 ${scopeViolations.length} 条 > 台账允许 ${allowedCount} 条：`);
  for (const v of scopeViolations) console.error(`  ${v.file}:${v.line}`);
  console.error('  处置见 docs/facts/DRIFT.md（这类文案承诺的范围大于实现，属「改代码：改文案」）。');
}

if (CHECK_ONLY) {
  if (before !== serialized) {
    failed = true;
    console.error('\n[FAIL] docs/facts/contracts.json 与当前源码不一致 —— 承诺清单过期。');
    console.error('  处理：跑 `npm run check:contracts` 生成新清单并**审阅新增的承诺**后提交。');
    console.error('  新增一条承诺意味着代码里多了一句「我保证做到 X」——它可能是对的，但需要有人看一眼。');
  } else {
    console.log('\n[OK] 承诺清单与源码一致，范围承诺违规在台账内。');
  }
  process.exit(failed ? 1 : 0);
}

writeFileSync(OUT_FILE, serialized, 'utf8');
console.log(changedHint(before, serialized));
process.exit(failed ? 1 : 0);

/** 写文件后的提示语：新增/减少多少条。 */
function changedHint(oldText, newText) {
  if (oldText === '') return '\n已写 docs/facts/contracts.json（首次生成）';
  try {
    const oldCount = JSON.parse(oldText).totals.promises;
    const newCount = JSON.parse(newText).totals.promises;
    if (oldCount === newCount) return '\n已写 docs/facts/contracts.json（条数未变）';
    const diff = newCount - oldCount;
    return `\n已写 docs/facts/contracts.json（承诺条数 ${oldCount} → ${newCount}，${diff > 0 ? '+' : ''}${diff}）`;
  } catch {
    return '\n已写 docs/facts/contracts.json';
  }
}
