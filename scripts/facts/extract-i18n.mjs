/**
 * i18n 事实生成脚本 —— 四语言 key 覆盖矩阵、缺译清单、占位符一致性。
 *
 * 职责：
 *   从 `apps/mobile-app/shared/i18n/resources/` 抽取四语言（zh-Hans / bo / ug-Arab-CN /
 *   kk-Arab-CN）× 14 个 namespace 的 key 全集，输出到 `docs/facts/i18n-keys.json`。
 *   该 json 是「i18n 到底有多少 key、四语言各覆盖多少」这件事的**唯一机器口径**，
 *   文档里写覆盖率必须引用它，不许人工填。
 *
 * 调用方：
 *   - `node scripts/facts/extract-i18n.mjs`（人工 / `npm run facts`）
 *   - CI 的 facts 同步检查（重跑后 `git diff --exit-code docs/facts/` 必须为空）
 *   - `docs/architecture/法泽FAZE_i18n规范_v1.0.md` 的统计节引用本脚本产出
 *
 * 数据来源：只读文件系统里的 56 个 json（4 语言 × 14 namespace），不连服务端。
 *
 * 注意事项：
 *   1. **key 的比较口径与 `scripts/check-i18n.ps1` 一致**：以 namespace 文件为单位各自
 *      扁平化，key **带 namespace 前缀**（如 `profile.settings.title`）。
 *      历史上两处脚本都是不带前缀的口径，导致 `profile.settings.title`（设置）与
 *      `workbench.settings.title`（服务设置）被并成一条，前者缺译永远不会被告警。
 *      2026-11-11 连同 `check-i18n.ps1` 一并改为带前缀，改时 `.i18n-pending.json` 为空，
 *      零迁移成本。**两个脚本必须同时改，只改一边就会出现两套数字。**
 *   2. **本脚本不重写 `.i18n-pending.json`**（那是 `check-i18n.ps1` 的产物）。本脚本只
 *      **读**它，并与自己算出的缺译集合做交叉校验：两边不一致说明有一方漂移，
 *      `pendingFile.consistent` 会变成 false。双实现交叉校验比单实现更能发现漂移。
 *   3. **不写生成时间**。写 `generatedAt: 当天日期` 会让 `git diff --exit-code docs/facts/`
 *      在次日必然失败（内容没变、日期变了），那是把 CI 变成日历。改用 `sourceDigest`：
 *      对抽取源内容做稳定哈希，源码不变则字节不变，源码一变立刻可见。
 *   4. 退出码恒为 0（事实生成器不是门禁）；门禁形态见 `check-facts.mjs`。
 */

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const RESOURCES = join(REPO, 'apps', 'mobile-app', 'shared', 'i18n', 'resources');
const PENDING_FILE = join(RESOURCES, '.i18n-pending.json');
const OUT_DIR = join(REPO, 'docs', 'facts');
const OUT_FILE = join(OUT_DIR, 'i18n-keys.json');

const SOURCE_LANG = 'zh-Hans';
const TARGET_LANGS = ['bo', 'ug-Arab-CN', 'kk-Arab-CN'];
const PLACEHOLDER_RE = /\{\{\s*(\w+)\s*\}\}/g;

/** 递归列出目录下所有指定扩展名文件，返回 POSIX 相对路径（跨平台稳定）。 */
function walk(dir, ext) {
  const out = [];
  for (const entry of readdirSync(dir).sort()) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...walk(full, ext));
    else if (entry.endsWith(ext)) out.push(full);
  }
  return out;
}

/**
 * 扁平化一个 namespace 对象。
 * 语义与 `check-i18n.ps1` 的 `Flatten-Json` 对齐：嵌套对象用点号展开，叶子一律当字符串。
 */
function flatten(obj, prefix = '', out = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, out);
    else out[key] = typeof v === 'string' ? v : String(v);
  }
  return out;
}

/** 读一个语言目录下的全部 namespace，返回 { files, flat, perFile }。key 带 namespace 前缀。 */
function readLang(lang) {
  const dir = join(RESOURCES, lang);
  if (!existsSync(dir)) return { files: [], flat: {}, perFile: {} };
  const files = readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .sort();
  const flat = {};
  const perFile = {};
  for (const f of files) {
    const ns = f.replace(/\.json$/, '');
    const parsed = JSON.parse(readFileSync(join(dir, f), 'utf8'));
    const part = flatten(parsed, ns);
    perFile[f] = Object.keys(part).length;
    for (const [k, v] of Object.entries(part)) flat[k] = v;
  }
  return { files, flat, perFile };
}

/**
 * 统计「同一个短路径名在多个 namespace 下被复用」的情况（如 `settings.title`）。
 * 加上 namespace 前缀后这些 key 已彼此独立、不再互相覆盖，本清单只陈述**命名复用**这一事实：
 * 复用多不代表有缺陷，但复用多的名字在翻译时容易串味，交给 i18n 规范判断。
 */
function findDuplicateKeyNames(langDir, files) {
  const seen = new Map();
  for (const f of files) {
    const ns = f.replace(/\.json$/, '');
    const parsed = JSON.parse(readFileSync(join(langDir, f), 'utf8'));
    for (const k of Object.keys(flatten(parsed))) {
      if (!seen.has(k)) seen.set(k, []);
      seen.get(k).push(ns);
    }
  }
  const dups = {};
  for (const [k, where] of seen) {
    if (where.length > 1) dups[k] = where;
  }
  return dups;
}

/** 取字符串里 {{占位符}} 的名字集合，排序后拼接，用于比较是否一致。 */
function placeholders(text) {
  const names = [...text.matchAll(PLACEHOLDER_RE)].map((m) => m[1]);
  return [...new Set(names)].sort().join(',');
}

/** 统计一个 namespace 树里「节点数（含中间对象）」与「叶子数（真正可翻译的条目）」。 */
function countNodes(obj) {
  let nodes = 0;
  let leaves = 0;
  for (const v of Object.values(obj)) {
    nodes++;
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
      const sub = countNodes(v);
      nodes += sub.nodes;
      leaves += sub.leaves;
    } else {
      leaves++;
    }
  }
  return { nodes, leaves };
}

// ── 读取 ──────────────────────────────────────────────────────────────
const source = readLang(SOURCE_LANG);
if (source.files.length === 0) {
  console.error(`[facts:i18n] 源语言目录里没有任何 json：${RESOURCES}`);
  process.exit(2);
}

const langs = { [SOURCE_LANG]: source };
for (const l of TARGET_LANGS) langs[l] = readLang(l);

// ── 结构（文件名集合）一致性 ──────────────────────────────────────────
const structureMismatch = [];
for (const l of TARGET_LANGS) {
  const onlyInSource = source.files.filter((f) => !langs[l].files.includes(f));
  const onlyInTarget = langs[l].files.filter((f) => !source.files.includes(f));
  if (onlyInSource.length || onlyInTarget.length) {
    structureMismatch.push({ lang: l, onlyInSource, onlyInTarget });
  }
}

// ── 缺译与占位符 ──────────────────────────────────────────────────────
const sourceKeys = Object.keys(source.flat).sort();
const missing = {};
const placeholderMismatch = [];
const coverage = {};

for (const l of [SOURCE_LANG, ...TARGET_LANGS]) {
  const flat = langs[l].flat;
  const present = sourceKeys.filter((k) => Object.prototype.hasOwnProperty.call(flat, k));
  coverage[l] = {
    keys: present.length,
    sourceKeys: sourceKeys.length,
    ratio: Number((present.length / sourceKeys.length).toFixed(4)),
  };
  if (l === SOURCE_LANG) continue;

  const miss = sourceKeys.filter((k) => !Object.prototype.hasOwnProperty.call(flat, k));
  if (miss.length) missing[l] = miss;

  for (const k of present) {
    const a = placeholders(source.flat[k]);
    const b = placeholders(flat[k]);
    if (a !== b) placeholderMismatch.push({ lang: l, key: k, source: a, target: b });
  }
}

// ── 与 .i18n-pending.json 交叉校验 ────────────────────────────────────
let pendingFile = { path: 'apps/mobile-app/shared/i18n/resources/.i18n-pending.json', exists: false };
if (existsSync(PENDING_FILE)) {
  const pendingJson = JSON.parse(readFileSync(PENDING_FILE, 'utf8'));
  const reg = pendingJson.pending ?? {};
  const registeredTotal = Object.values(reg).reduce((n, arr) => n + arr.length, 0);
  const computedTotal = Object.values(missing).reduce((n, arr) => n + arr.length, 0);
  const perLangConsistent = {};
  for (const l of TARGET_LANGS) {
    const a = (reg[l] ?? []).slice().sort().join('\n');
    const b = (missing[l] ?? []).slice().sort().join('\n');
    perLangConsistent[l] = a === b;
  }
  pendingFile = {
    path: 'apps/mobile-app/shared/i18n/resources/.i18n-pending.json',
    exists: true,
    registeredTotal,
    computedTotal,
    consistent: registeredTotal === computedTotal && Object.values(perLangConsistent).every(Boolean),
    perLangConsistent,
  };
}

// ── 每 namespace 的 key 数矩阵 ────────────────────────────────────────
const namespaces = source.files;
const perNamespace = {};
for (const f of namespaces) {
  perNamespace[f.replace(/\.json$/, '')] = {};
  for (const l of [SOURCE_LANG, ...TARGET_LANGS]) {
    perNamespace[f.replace(/\.json$/, '')][l] = langs[l].perFile[f] ?? 0;
  }
}

const duplicateKeyNames = findDuplicateKeyNames(join(RESOURCES, SOURCE_LANG), source.files);

// 三个口径的数必须同时给出，否则「i18n 有多少 key」这个问题永远会被答成三个答案：
//   nodeCount  —— 含中间对象的总节点数。`GUARDRAIL-PLAN.md` §2.1 写的 1965 是这个口径。
//   leafCount  —— 真正可翻译的叶子条目总数。1880。
//   sourceKeys —— 带 namespace 前缀后去重的 key 数。与 leafCount 相等才是正常的；
//                 若小于 leafCount，说明仍有 key 互相覆盖（带前缀后不应再发生）。
const nodeTotals = source.files.reduce(
  (acc, f) => {
    const sub = countNodes(JSON.parse(readFileSync(join(RESOURCES, SOURCE_LANG, f), 'utf8')));
    return { nodes: acc.nodes + sub.nodes, leaves: acc.leaves + sub.leaves };
  },
  { nodes: 0, leaves: 0 },
);

// ── 源指纹：内容稳定哈希，替代生成时间 ────────────────────────────────
const digest = createHash('sha256');
for (const f of walk(RESOURCES, '.json')) {
  digest.update(relative(REPO, f).split(sep).join('/'));
  digest.update(readFileSync(f));
}
const sourceDigest = digest.digest('hex').slice(0, 16);

const payload = {
  _comment:
    '本文件由 scripts/facts/extract-i18n.mjs 从 shared/i18n/resources 生成，禁止手改。口径见 docs/standards/说辞一致与真相源规范.md §四。切换语言目录后必须重跑，CI 会拦 git diff。',
  sourceDigest,
  source: 'apps/mobile-app/shared/i18n/resources',
  sourceLang: SOURCE_LANG,
  targetLangs: TARGET_LANGS,
  totals: {
    namespaces: namespaces.length,
    jsonFiles: Object.values(langs).reduce((n, l) => n + l.files.length, 0),
    sourceKeys: sourceKeys.length,
    leafCount: nodeTotals.leaves,
    nodeCount: nodeTotals.nodes,
    countNote:
      'sourceKeys=带 namespace 前缀去重后的 key 数；leafCount=可翻译叶子条目数；nodeCount=含中间对象的总节点数。三者都可查，引用数字时必须连口径一起引。',
  },
  coverage,
  perNamespace,
  missing,
  placeholderMismatch,
  structureMismatch,
  pendingFile,
  duplicateKeyNames: {
    count: Object.keys(duplicateKeyNames).length,
    keys: duplicateKeyNames,
    note: '同名短路径在多个 namespace 下复用；加前缀后彼此独立、不会互相覆盖。此清单只陈述事实，不判断是否为缺陷。',
  },
};

mkdirSync(OUT_DIR, { recursive: true });
writeFileSync(OUT_FILE, JSON.stringify(payload, null, 2) + '\n', 'utf8');

// ── stdout 摘要 ──────────────────────────────────────────────────────
console.log('=== i18n 事实（生成自 shared/i18n/resources）===\n');
console.log('namespace 文件数      ', namespaces.length);
console.log('json 文件总数         ', payload.totals.jsonFiles);
console.log('源语言 key 数（带前缀去重）', sourceKeys.length);
console.log('叶子条目数            ', nodeTotals.leaves);
console.log('总节点数（含中间对象）', nodeTotals.nodes);
for (const l of [SOURCE_LANG, ...TARGET_LANGS]) {
  const c = coverage[l];
  console.log(`  ${l.padEnd(12)} ${String(c.keys).padStart(5)} / ${c.sourceKeys}  覆盖 ${(c.ratio * 100).toFixed(2)}%`);
}
console.log('短名跨 namespace 复用 ', Object.keys(duplicateKeyNames).length);

const problems = [];
if (structureMismatch.length) problems.push(`${structureMismatch.length} 个语言目录不同构`);
if (placeholderMismatch.length) problems.push(`${placeholderMismatch.length} 处占位符不一致`);
if (pendingFile.exists && !pendingFile.consistent) {
  problems.push(
    `缺译登记与实测不一致（登记 ${pendingFile.registeredTotal} / 实测 ${pendingFile.computedTotal}）`,
  );
}
if (problems.length) {
  console.log('\n[注意] ' + problems.join('；'));
  console.log('        处置见 docs/facts/DRIFT.md，本脚本不裁决。');
} else {
  console.log('\n[OK] 目录同构 / 占位符一致 / 缺译登记与实测吻合');
}

console.log(`\n源指纹 ${sourceDigest}`);
console.log('已写 docs/facts/i18n-keys.json');
