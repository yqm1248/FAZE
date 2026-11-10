/**
 * 设计稿 ↔ 页面映射校验 —— `design/PAGE-MAP.json` 的完整性门禁。
 *
 * 职责：
 *   回答两个以前只能靠人点屏才能回答的问题：
 *     ① 「设计稿有、代码没有」有哪些？（`design-only`）
 *     ② 「代码有、设计稿没有」有哪些？（`impl-only`）
 *   并保证映射表本身不难腐坏：不缺项、不指向不存在的文件、`diverged` 必须挂真实 DRIFT 行。
 *
 * 调用方：
 *   - `npm run check:pages`
 *   - CI（`mobile` job，纯文件读取）
 *
 * 数据来源：`design/PAGE-MAP.json` + `design/ui/*.html` + `apps/mobile-app/features/**\/*Page.tsx`
 *   + `docs/facts/DRIFT.md`。不连服务端、不连数据库。
 *
 * 校验的五件事（缺一条这张表就白建）：
 *   1. `design/ui/` 下每个 html 至少被一行 `design` 指到；
 *   2. 每个页面实现文件至少被一行 `page` 指到（开发探测页除外）；
 *   3. `status` 只能是 implemented / diverged / design-only / impl-only 四值之一；
 *   4. `status=diverged` 必须带 `drift`，且该 ID 在 `docs/facts/DRIFT.md` 里**真实存在**；
 *   5. `status=design-only` 必须带 `stage`（计划段号或 `未排期`）。
 *
 * 注意事项：
 *   - `implemented` **只表示「有对应实现」**，不表示像素级一致。视觉一致性由验收负责，
 *     不是本脚本能验的。想验就得截图比对，那是另一件事（G7 的不可见断言也不管这个）。
 *   - 判断「这张稿对应哪一页」是**人工判断**，本脚本只校验完整性。不要让脚本去猜文件名，
 *     猜错的映射比没有映射更糟——它会让人以为已经对过了。
 *
 * 退出码：0 通过 / 1 有不完整项 / 2 映射表本身损坏（JSON 坏、状态值非法）
 */

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const MAP_FILE = join(REPO, 'design', 'PAGE-MAP.json');
const DESIGN_DIR = join(REPO, 'design', 'ui');
const FEATURES_DIR = join(REPO, 'apps', 'mobile-app', 'features');
const DRIFT_FILE = join(REPO, 'docs', 'facts', 'DRIFT.md');

const VALID_STATUS = new Set(['implemented', 'diverged', 'design-only', 'impl-only']);

/** 读并解析映射表；坏 JSON 直接退出 2（这是表本身坏了，不是校验失败）。 */
let map;
try {
  map = JSON.parse(readFileSync(MAP_FILE, 'utf8'));
} catch (err) {
  console.error(`[FAIL] design/PAGE-MAP.json 解析失败：${err.message}`);
  process.exit(2);
}
if (!Array.isArray(map.entries)) {
  console.error('[FAIL] design/PAGE-MAP.json 缺少 entries 数组');
  process.exit(2);
}
const entries = map.entries;

/** DRIFT.md 里真实存在的 ID 集合（只认表格行首的 `| DRIFT-0xx |`）。 */
const driftIds = new Set();
if (existsSync(DRIFT_FILE)) {
  for (const line of readFileSync(DRIFT_FILE, 'utf8').split('\n')) {
    const m = /^\|\s*(DRIFT-\d{3})\s*\|/.exec(line);
    if (m) driftIds.add(m[1]);
  }
}

/** 递归收集页面实现文件（相对仓库路径，POSIX 分隔）。 */
function collectPages() {
  const out = [];
  function walk(dir) {
    for (const entry of readdirSync(dir).sort()) {
      const full = join(dir, entry);
      if (!existsSync(full)) continue;
      const st = readdirSync(dir, { withFileTypes: true }).find((d) => d.name === entry);
      if (st.isDirectory()) walk(full);
      else if (entry.endsWith('Page.tsx')) {
        const rel = full.slice(REPO.length + 1).split('\\').join('/');
        // 开发探测页不是产品页，不参与映射
        if (!rel.includes('/features/dev/')) out.push(rel);
      }
    }
  }
  walk(FEATURES_DIR);
  return out;
}

const designFiles = readdirSync(DESIGN_DIR)
  .filter((f) => f.endsWith('.html'))
  .map((f) => `ui/${f}`)
  .sort();
const pageFiles = collectPages();

// ── 逐行校验 ──────────────────────────────────────────────────────────
const errors = [];
const referencedDesign = new Set();
const referencedPage = new Set();

entries.forEach((e, idx) => {
  const where = `entries[${idx}]`;
  if (!VALID_STATUS.has(e.status)) {
    errors.push(`${where}：status 值 \`${e.status}\` 非法（只允许 ${[...VALID_STATUS].join(' / ')}）`);
    return;
  }

  if (e.design !== null && e.design !== undefined) {
    const rel = `design/${e.design}`;
    referencedDesign.add(e.design);
    if (!existsSync(join(REPO, rel))) errors.push(`${where}：设计稿不存在 ${rel}`);
  } else if (e.status !== 'impl-only') {
    errors.push(`${where}：status=${e.status} 但没有 design 字段`);
  }

  if (e.page !== null && e.page !== undefined) {
    referencedPage.add(e.page);
    if (!existsSync(join(REPO, e.page))) errors.push(`${where}：实现文件不存在 ${e.page}`);
  } else if (e.status !== 'design-only') {
    errors.push(`${where}：status=${e.status} 但没有 page 字段`);
  }

  if (e.status === 'diverged') {
    if (!e.drift) errors.push(`${where}：diverged 必须带 drift 字段（指向 docs/facts/DRIFT.md 的 ID）`);
    else if (!driftIds.has(e.drift)) errors.push(`${where}：drift \`${e.drift}\` 在 DRIFT.md 里不存在`);
  }

  if (e.status === 'design-only') {
    if (!e.stage) errors.push(`${where}：design-only 必须带 stage（计划段号或「未排期」）`);
  }

  if (e.status === 'impl-only') {
    if (!e.note) errors.push(`${where}：impl-only 必须在 note 里写明为什么没有设计稿`);
    if (e.design !== null) errors.push(`${where}：impl-only 的 design 必须是 null`);
  }
});

// ── 完整性：两个方向都不许漏 ──────────────────────────────────────────
const designNotMapped = designFiles.filter((f) => !referencedDesign.has(f));
const pageNotMapped = pageFiles.filter((f) => !referencedPage.has(f));

// ── 报告 ──────────────────────────────────────────────────────────────
const count = (s) => entries.filter((e) => e.status === s).length;
console.log('=== 设计稿 ↔ 页面映射校验 ===\n');
console.log(`映射条目        ${entries.length}`);
console.log(`设计稿 HTML     ${designFiles.length}（已映射 ${referencedDesign.size}）`);
console.log(`页面实现        ${pageFiles.length}（已映射 ${referencedPage.size}）`);
console.log(
  `状态分布        implemented ${count('implemented')} / diverged ${count('diverged')} / design-only ${count('design-only')} / impl-only ${count('impl-only')}`,
);

let failed = false;

if (designNotMapped.length > 0) {
  failed = true;
  console.error(`\n[FAIL] ${designNotMapped.length} 份设计稿没有映射（设计稿有、但表里没登记它对应哪一页）：`);
  for (const f of designNotMapped) console.error(`  design/${f}`);
}

if (pageNotMapped.length > 0) {
  failed = true;
  console.error(`\n[FAIL] ${pageNotMapped.length} 个页面实现没有映射（代码有、但表里没登记它对应哪一稿）：`);
  for (const f of pageNotMapped) console.error(`  ${f}`);
  console.error('  处理：要么在 PAGE-MAP.json 里补一行（status=impl-only 并写明为什么没有稿），');
  console.error('       要么说明这是漏登记的实现页。**不许因为「没稿」就跳过登记**——那正是本表要暴露的东西。');
}

if (errors.length > 0) {
  failed = true;
  console.error(`\n[FAIL] ${errors.length} 处条目本身有问题：`);
  for (const e of errors) console.error(`  ${e}`);
}

if (!failed) {
  const doList = entries.filter((e) => e.status === 'design-only');
  const ioList = entries.filter((e) => e.status === 'impl-only');
  console.log('\n[OK] 映射表完整：设计稿不缺项、页面无漏登记、状态值与 DRIFT 引用全部有效。');
  if (doList.length) {
    console.log(`\n  设计稿有、代码没有（${doList.length} 项）：`);
    for (const e of doList) console.log(`    design/${e.design}  →  ${e.stage}`);
  }
  if (ioList.length) {
    console.log(`\n  代码有、设计稿没有（${ioList.length} 项）：`);
    for (const e of ioList) console.log(`    ${e.page}`);
  }
  process.exit(0);
}
process.exit(1);
