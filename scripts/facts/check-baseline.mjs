/**
 * 基线登记校验 —— 检查 `docs/BASELINE.md` §二 登记的行数 / 字节 / 指纹是否过期。
 *
 * 职责：
 *   把 BASELINE §二 的资产表解析成机器可比的清单，逐份实测 LF 口径指纹并与登记值比对。
 *   它是「基线是不是可信」这件事的**唯一执行点**——BASELINE 自称是「哪些文档现行有效」的
 *   唯一入口，那它自己就必须可校验，否则只是又一份会腐坏的手写清单。
 *
 * 调用方：
 *   - `npm run check:baseline`
 *   - CI（接进 `mobile` job，纯文件读取，不需要数据库）
 *
 * 数据来源：只读 `docs/BASELINE.md` + 被登记的文件本身。
 *
 * 为什么写这个：
 *   BASELINE §五 原本给了一段**手工维护**的 PowerShell 校验命令，里面只列了 32 个文件，
 *   而 §二 已登记 51 份资产——**清单自己就漏了 19 份**。手写清单的问题是它会跟着文档
 *   一起腐坏，而且腐坏时没人知道。改为从 §二 自动解析，登记了什么就校验什么。
 *
 * 注意事项：
 *   - **只认 §二 的行**（形如 `| 12 | \`docs/x.md\` | 297 | 15598 | \`9d11a5…\` |…`）。
 *     §七 的历史记录里也有表格与指纹，但那不是现行登记，不参与校验。
 *   - 行数为「LF 个数 + 1」、字节按 CRLF→LF 归一后计——口径来自 `lib/fingerprint.mjs`。
 *   - 校验失败**不等于文档有问题**，等于「文档在基线之后被改过却没出新基线」。
 *     两种结局都是问题，但归因不同，输出里分开说明。
 *   - `--fix` 把登记值改写成实测值（只改数字，不动 📝 / 🆕 标记与「是什么」列）。
 *     **它不判断改动是否预期**：写文档的人自己知道刚才改了什么，脚本不知道。
 *     所以 --fix 之后必须人工确认「这些变化是我想要的」，再补标记、出基线版本。
 *
 * 退出码：0 全部一致 / 1 有不一致 / 2 BASELINE 解析失败
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { fingerprintFile } from './lib/fingerprint.mjs';

const REPO = join(import.meta.dirname, '..', '..');
const BASELINE = join(REPO, 'docs', 'BASELINE.md');
const FIX = process.argv.includes('--fix');

/**
 * 资产行：`| 编号 | \`路径\` | 行数 | 字节 | \`sha16\` |…`
 * 行首允许空白：实测 §二 里 `#3` 那一行带着一个多余的前导空格（历史编辑残留），
 * 若正则要求行首必须是 `|`，它会整行被跳过，表现为「编号 2 → 4 缺 3」——
 * **那是解析器的 bug，不是文档缺一份**。（2026-11-11 第一次跑就踩到了。）
 *
 * 分组说明：1=行首前缀 2=编号 3=到路径前的分隔 4=路径 5=路径后的分隔
 *          6=行数 7=分隔 8=字节 9=分隔 10=指纹 11=指纹之后的全部内容
 * 这样 `--fix` 可以原样保留 📝 / 🆕 标记与「是什么」那一列。
 */
const ASSET_ROW_RE =
  /^(\s*\|\s*)(\d+)(\s*\|\s*`)([^`]+)(`\s*\|\s*)(\d+)(\s*\|\s*)(\d+)(\s*\|\s*`)([0-9a-f]{16})(`.*)$/;

const md = readFileSync(BASELINE, 'utf8');
const lines = md.split('\n');

// 只取 §二（「## 二、」到下一个「## 」之前）的段落，避免把 §七 历史记录当成现行登记
const startIdx = lines.findIndex((l) => /^## 二、/.test(l));
if (startIdx < 0) {
  console.error('[FAIL] 找不到 §二 段落，BASELINE 结构可能变了。');
  process.exit(2);
}
let endIdx = lines.findIndex((l, i) => i > startIdx && /^## /.test(l));
if (endIdx < 0) endIdx = lines.length;

const assets = [];
const seenIds = new Map();
const duplicates = [];

for (let i = startIdx; i < endIdx; i++) {
  const m = ASSET_ROW_RE.exec(lines[i]);
  if (!m) continue;
  const [, pre, num, sep1, path, sep2, linesDeclared, sep3, bytesDeclared, sep4, shaDeclared, tail] = m;
  const id = Number(num);
  const entry = {
    id,
    path,
    lineNo: i + 1,
    lineIndex: i,
    parts: { pre, num, sep1, sep2, sep3, sep4, tail },
    declared: { lines: Number(linesDeclared), bytes: Number(bytesDeclared), sha16: shaDeclared },
  };
  if (seenIds.has(id)) duplicates.push({ id, first: seenIds.get(id), second: i + 1 });
  else seenIds.set(id, i + 1);
  assets.push(entry);
}

if (assets.length === 0) {
  console.error('[FAIL] §二 里没有解析到任何资产行，正则或格式可能变了。');
  process.exit(2);
}

// ── 逐份校验 ──────────────────────────────────────────────────────────
const missing = [];
const drifts = [];

for (const a of assets) {
  const abs = join(REPO, a.path);
  if (!existsSync(abs)) {
    missing.push(a);
    continue;
  }
  const actual = fingerprintFile(abs);
  if (
    actual.lines !== a.declared.lines ||
    actual.bytes !== a.declared.bytes ||
    actual.sha16 !== a.declared.sha16
  ) {
    drifts.push({ ...a, actual });
  }
}

// ── 编号连续性 ────────────────────────────────────────────────────────
const ids = assets.map((a) => a.id).sort((x, y) => x - y);
const gaps = [];
for (let i = 1; i < ids.length; i++) {
  if (ids[i] !== ids[i - 1] + 1) gaps.push(`${ids[i - 1]} → ${ids[i]}（缺 ${ids[i] - ids[i - 1] - 1} 个）`);
}

// ── 报告 ──────────────────────────────────────────────────────────────
const mdCount = assets.filter((a) => a.path.endsWith('.md')).length;
const jsonCount = assets.filter((a) => a.path.endsWith('.json')).length;

console.log('=== 基线登记校验（LF 口径）===\n');
console.log(`登记资产        ${assets.length} 份（Markdown ${mdCount} + JSON ${jsonCount}）`);
console.log(`编号范围        ${ids[0]} ~ ${ids[ids.length - 1]}`);
console.log(`实测一致        ${assets.length - missing.length - drifts.length} 份`);

let failed = false;

if (duplicates.length > 0) {
  failed = true;
  console.error(`\n[FAIL] 编号重复 ${duplicates.length} 处：`);
  for (const d of duplicates) console.error(`  #${d.id} 出现在第 ${d.first} 行与第 ${d.second} 行`);
}

if (gaps.length > 0) {
  failed = true;
  console.error(`\n[FAIL] 编号不连续：${gaps.join('；')}`);
}

if (missing.length > 0) {
  failed = true;
  console.error(`\n[FAIL] 登记了但文件不存在（${missing.length} 份）：`);
  for (const m of missing) console.error(`  #${m.id}  ${m.path}   （登记于 BASELINE 第 ${m.lineNo} 行）`);
}

if (drifts.length > 0) {
  failed = true;
  console.error(`\n[FAIL] 登记值与实测不符（${drifts.length} 份）：`);
  console.error('  文件'.padEnd(56) + '登记（行/字节/指纹）→ 实测');
  for (const d of drifts) {
    const dec = `${d.declared.lines}/${d.declared.bytes}/${d.declared.sha16}`;
    const act = `${d.actual.lines}/${d.actual.bytes}/${d.actual.sha16}`;
    console.error(`  #${String(d.id).padEnd(3)} ${d.path}`.padEnd(56) + `${dec} → ${act}`);
    const lineDiff = d.actual.lines - d.declared.lines;
    const byteDiff = d.actual.bytes - d.declared.bytes;
    if (d.actual.sha16 !== d.declared.sha16 && lineDiff === 0 && byteDiff === 0) {
      console.error('        指纹变了但行数/字节没变——内容改动恰好等长，最常见的是改了一个数字或错别字');
    } else if (d.actual.sha16 !== d.declared.sha16 && lineDiff === 0) {
      console.error(`        行数不变、字节 ${byteDiff > 0 ? '+' : ''}${byteDiff}`);
    }
  }
  if (!FIX) {
    console.error('\n  处理：① 改动是预期的 → 出新基线版本并更新登记（或加 --fix 让脚本改数字）；');
    console.error('        ② 改动是意外的 → git log 查是谁改的、为什么；');
    console.error('        ③ 内容确实没变却报不符 → 先怀疑 LF 口径或行数算法（见 lib/fingerprint.mjs 注释）。');
  }
}

// ── --fix：把登记值改写成实测值 ────────────────────────────────────────
if (FIX && drifts.length > 0) {
  for (const d of drifts) {
    const p = d.parts;
    lines[d.lineIndex] =
      p.pre +
      p.num +
      p.sep1 +
      d.path +
      p.sep2 +
      String(d.actual.lines) +
      p.sep3 +
      String(d.actual.bytes) +
      p.sep4 +
      d.actual.sha16 +
      p.tail;
  }
  writeFileSync(BASELINE, lines.join('\n'), 'utf8');
  console.log(`\n[FIX] 已按实测值改写 ${drifts.length} 行登记。`);
  console.log('      标记（📝 / 🆕）与「是什么」那一列保持原样，需要人工判断是否补标记。');
  console.log('      改完请重跑一次本脚本确认全绿。');
  process.exit(0);
}

if (!failed) {
  console.log('\n[OK] §二 全部登记与实测一致，编号连续无重复。');
  process.exit(0);
}
process.exit(1);
