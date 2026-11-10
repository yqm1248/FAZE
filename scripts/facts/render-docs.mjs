/**
 * 受控表格渲染器 —— 把 `docs/facts/*.json` 的事实渲染进文档的 `<!-- FACTS:xxx -->` 区块。
 *
 * 职责：
 *   文档里凡是「一眼就会被问『这数还准吗』」的表格，都不再手写，改由本脚本从事实
 *   json 渲染。区块用 HTML 注释包起来，脚本只替换区块内部，**区块外一个字都不动**。
 *
 * 区块写法（注意闭合标记里的 key 必须与开始标记一致）：
 *
 *     <!-- FACTS:error-codes -->
 *     …本脚本生成的内容…
 *     <!-- /FACTS:error-codes -->
 *
 * 调用方：
 *   - `npm run render:docs`（写回文档）
 *   - `npm run check:facts`（`--check` 模式：只判定一致与否，不写）
 *   - CI（同上，不一致即 fail）
 *
 * 数据来源：`docs/facts/*.json`。**本脚本不解析业务源码**，事实从 json 来，
 *   而 json 由 `run-all.mjs` 生成。三层各管一段，谁都不越界。
 *
 * 为什么必须这样：
 *   手写表格的问题是「它会静默过期」——写的时候对，三个月后没人知道它还对不对，
 *   而读它的人无从判断。这次实测已经证明：错误码 78/91/94 三个数在四份文档里并存。
 *   渲染之后，文档里的数字与代码之间**不存在人工环节**。
 *
 * 注意事项：
 *   - **渲染结果必须与运行时间无关**。禁止加时间戳、禁止加 `sourceDigest`
 *     （digest 一进文档，源码每改一次文档就跟一次，噪音大于价值；要看 digest 直接看 json）。
 *   - 顺序必须稳定：表格严格按 json 里的既有顺序或固定排序键输出，不能依赖 Map 迭代序。
 *   - 表格单元格里的 `|` 要转义为 `\|`，否则表格解析错位。
 *   - 找到 `<!-- FACTS:key -->` 但没有对应闭合标记 → 报错退出 2，**不允许猜边界**。
 *
 * 退出码：0 一致 / 1 `--check` 下发现不一致 / 2 区块标记损坏或未知 key
 */

import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const FACTS_DIR = join(REPO, 'docs', 'facts');
const CHECK_ONLY = process.argv.includes('--check');

/** 区块头：固定文案，说明这节是生成的，避免下一个人手改。 */
const BLOCK_HEADER =
  '> 本区块由 `scripts/facts/render-docs.mjs` 从 `docs/facts/*.json` 生成，**禁止手改**；要改先改代码再跑 `npm run facts`。';

/** 读一份事实 json；缺失即报错（宁可失败，不要渲染半张表）。 */
function loadFact(name) {
  const p = join(FACTS_DIR, name);
  if (!existsSync(p)) {
    console.error(`[FAIL] 缺少事实文件 docs/facts/${name}，先跑 npm run facts`);
    process.exit(2);
  }
  return JSON.parse(readFileSync(p, 'utf8'));
}

/** 表格单元格转义：竖线会破坏 markdown 表格；换行压成空格。 */
function cell(v) {
  return String(v ?? '')
    .replace(/\r?\n/g, ' ')
    .replace(/\|/g, '\\|')
    .trim();
}

// ── 各区块渲染器 ──────────────────────────────────────────────────────

/** 错误码全量表：94 行，按码升序。 */
function renderErrorCodes() {
  const f = loadFact('error-codes.json');
  const segs = Object.entries(f.byPrefix)
    .sort((a, b) => Number(a[0]) - Number(b[0]))
    .map(([k, v]) => `${k}:${v}`)
    .join(' / ');
  const out = [
    `共 **${f.totals.codes}** 个码（重复 ${f.totals.duplicates} 组、\`msgKey\` 为空 ${f.totals.emptyMsgKey} 个）。号段分布：${segs}。`,
    '',
    '| 码 | HTTP | msgKey | 中文 |',
    '|---|---|---|---|',
  ];
  for (const c of f.codes) {
    out.push(`| ${c.code} | ${c.httpStatus} | \`${cell(c.msgKey)}\` | ${cell(c.msg)} |`);
  }
  return out.join('\n');
}

/** 表清单：53 张，含列数 / 主键 / 唯一键 / 表注释。 */
function renderSchemaTables() {
  const f = loadFact('tables.json');
  const out = [
    `已建 **${f.totals.tables}** 张业务表 / ${f.totals.columns} 列 / ${f.totals.indexes} 个索引；Flyway 已应用 ${f.totals.flywayApplied} 个迁移，最新 **V${f.totals.flywayLatest}**。`,
    '',
    '| 表 | 列 | 主键 | 唯一键 | 表注释 |',
    '|---|---|---|---|---|',
  ];
  for (const t of f.tables) {
    const uk = t.uniqueKeys.length ? t.uniqueKeys.join('、') : '—';
    out.push(
      `| \`${t.name}\` | ${t.columnCount} | ${t.primaryKey.join('、') || '—'} | ${cell(uk)} | ${cell(t.comment) || '—'} |`,
    );
  }
  return out.join('\n');
}

/** 页面清单：71 个页面实现，含路由与 testID 锚点数。 */
function renderPages() {
  const f = loadFact('pages.json');
  const k = f.counts;
  const out = [
    `产品页 **${k.productPages}**（路由常量 ${k.pageRoutes} − 开发探测 ${k.devRoutes}）；路由壳 ${k.shells}；页面实现 ${k.pageImpls}；设计稿主屏 ${k.designMainScreens}（HTML ${k.designHtml} − 补充屏 ${k.designSupplementScreens}）；PC 后台 ${k.adminScreens}。`,
    '',
    '四个数都对但**构成不同**，引用时必须连口径一起引。逐页明细（含 `testID` 锚点、`LocalizedText` 使用、写死路由）见 `docs/facts/pages.json`。',
    '',
    '| 路由 | 路由常量 key | 页面文件 | testID | 开发页 |',
    '|---|---|---|---|---|',
  ];
  for (const p of f.pages) {
    out.push(
      `| ${p.route ?? '—'} | ${p.routeKey ?? '—'} | \`${p.file.replace('apps/mobile-app/', '')}\` | ${p.testIdCount} | ${p.isDevProbe ? '是' : ''} |`,
    );
  }
  return out.join('\n');
}

/** i18n 统计：四语言覆盖 + 每 namespace 的 key 数矩阵。 */
function renderI18nStats() {
  const f = loadFact('i18n-keys.json');
  const t = f.totals;
  const out = [
    `源语言 \`${f.sourceLang}\` 共 **${t.sourceKeys}** 个 key（可翻译叶子 ${t.leafCount} 条、含中间对象的总节点 ${t.nodeCount} 个——**三个口径不是一个数**，引用时必须说明取哪个）。`,
    '',
    `${t.namespaces} 个 namespace、${t.jsonFiles} 个 json 文件。`,
    '',
    '| 语言 | key 数 | 覆盖率 |',
    '|---|---|---|',
  ];
  for (const lang of [f.sourceLang, ...f.targetLangs]) {
    const c = f.coverage[lang];
    out.push(`| \`${lang}\` | ${c.keys} | ${(c.ratio * 100).toFixed(2)}% |`);
  }
  out.push('', '每 namespace 的 key 数：', '', '| namespace | ' + [f.sourceLang, ...f.targetLangs].join(' | ') + ' |');
  out.push('|---' + '|---'.repeat(1 + f.targetLangs.length) + '|');
  for (const [ns, row] of Object.entries(f.perNamespace)) {
    out.push(`| \`${ns}\` | ${[f.sourceLang, ...f.targetLangs].map((l) => row[l]).join(' | ')} |`);
  }
  const pending = f.pendingFile;
  out.push(
    '',
    `缺译登记：\`${pending.path}\` 登记 ${pending.registeredTotal} 条，与本脚本实测${pending.consistent ? '**吻合**' : '**不一致**'}。`,
  );
  return out.join('\n');
}

/** 接口分布：按访问面 × 域。不列 260 行明细（太长），明细看 api.json。 */
function renderApiEndpoints() {
  const f = loadFact('api.json');
  const surfaces = ['admin', 'app', 'pub', 'dev'];
  const domains = Object.keys(f.byDomain).sort();
  const matrix = {};
  for (const e of f.endpoints) {
    matrix[e.domain] ??= {};
    matrix[e.domain][e.surface] = (matrix[e.domain][e.surface] ?? 0) + 1;
  }
  const out = [
    `共 **${f.totals.endpoints}** 个端点、${f.totals.controllers} 个 Controller。按访问面：` +
      surfaces.map((s) => `\`${s}\` ${f.bySurface[s] ?? 0}`).join(' / ') +
      '。按方法：' +
      Object.entries(f.byMethod)
        .sort()
        .map(([m, n]) => `${m} ${n}`)
        .join(' / ') +
      '。',
    '',
    '| 域 | ' + surfaces.join(' | ') + ' | 合计 |',
    '|---' + '|---'.repeat(surfaces.length + 1) + '|',
  ];
  for (const d of domains) {
    const row = surfaces.map((s) => matrix[d]?.[s] ?? 0);
    out.push(`| \`${d}\` | ${row.join(' | ')} | ${f.byDomain[d]} |`);
  }
  out.push(
    '| **合计** | ' + surfaces.map((s) => f.bySurface[s] ?? 0).join(' | ') + ` | **${f.totals.endpoints}** |`,
    '',
    '逐条明细（方法 / 路径 / 控制器 / 域）见 `docs/facts/api.json`。',
  );
  return out.join('\n');
}

/** key → 渲染器。新增受控区块必须在此登记，否则文档里出现该标记会直接报错。 */
const RENDERERS = {
  'error-codes': renderErrorCodes,
  'schema-tables': renderSchemaTables,
  pages: renderPages,
  'i18n-stats': renderI18nStats,
  'api-endpoints': renderApiEndpoints,
};

// ── 扫描与替换 ────────────────────────────────────────────────────────

/** 递归收集需要处理的 markdown 文件。 */
function collectDocs() {
  const out = [];
  function walk(dir) {
    for (const entry of readdirSync(dir).sort()) {
      const full = join(dir, entry);
      if (entry === 'node_modules' || entry.startsWith('.')) continue;
      if (statSync(full).isDirectory()) walk(full);
      else if (entry.endsWith('.md')) out.push(full);
    }
  }
  walk(join(REPO, 'docs'));
  return out;
}

/**
 * 在一份文档里找出全部区块，返回 [{ key, openLine, closeLine, body }]。
 *
 * **标记必须独占一行**才被承认。这条限制是两个原因叠出来的：
 *   ① 正文里「讲标记格式」的句子（例如某份文档解释「用 `<!-- FACTS:key -->` 包起来」）
 *      不应该被当成真区块 —— 2026-11-11 第一次跑就这样被 BASELINE 自己的说明骗了；
 *   ② 行首定位比全文 indexOf 更好推理，也不会因为半行标记把正文切坏。
 *
 * 标记不成对直接报错——**不允许猜边界**，猜错了会删掉正文。
 */
function findBlocks(text, docPath) {
  const lines = text.split('\n');
  const openRe = /^<!-- FACTS:([\w-]+) -->[ \t]*$/;
  const blocks = [];
  for (let i = 0; i < lines.length; i++) {
    const m = openRe.exec(lines[i]);
    if (!m) continue;
    const key = m[1];
    const closeRe = new RegExp(`^<!-- /FACTS:${key} -->[ \\t]*$`);
    let j = i + 1;
    while (j < lines.length && !closeRe.test(lines[j])) j++;
    if (j >= lines.length) {
      console.error(`[FAIL] ${docPath}:${i + 1} 找到 <!-- FACTS:${key} --> 但没有独占一行的闭合标记 <!-- /FACTS:${key} -->`);
      process.exit(2);
    }
    blocks.push({ key, openLine: i, closeLine: j, body: lines.slice(i + 1, j).join('\n') });
    i = j;
  }
  return blocks;
}

let changedFiles = 0;
let totalBlocks = 0;
const problems = [];

for (const docPath of collectDocs()) {
  const relPath = relative(REPO, docPath).split(sep).join('/');
  const original = readFileSync(docPath, 'utf8');
  const blocks = findBlocks(original, relPath);
  if (blocks.length === 0) continue;
  totalBlocks += blocks.length;

  // 从后往前替换，避免前面的替换改变后面 block 的行号
  const outLines = original.split('\n');
  for (const b of blocks.slice().reverse()) {
    const renderer = RENDERERS[b.key];
    if (!renderer) {
      console.error(`[FAIL] ${relPath}：未知区块 key \`${b.key}\`。已登记的 key：${Object.keys(RENDERERS).join(', ')}`);
      process.exit(2);
    }
    const desiredLines = [BLOCK_HEADER, '', ...renderer().split('\n')];
    const desired = desiredLines.join('\n');
    if (b.body !== desired) {
      problems.push({ doc: relPath, key: b.key });
      outLines.splice(b.openLine + 1, b.closeLine - b.openLine - 1, ...desiredLines);
    }
  }
  const updated = outLines.join('\n');

  if (updated !== original) {
    changedFiles++;
    if (CHECK_ONLY) {
      console.log(`  [不一致] ${relPath}`);
    } else {
      writeFileSync(docPath, updated, 'utf8');
      console.log(`  [已更新] ${relPath}`);
    }
  }
}

// ── 汇总 ──────────────────────────────────────────────────────────────
console.log('');
if (totalBlocks === 0) {
  console.log('[OK] 文档里没有任何 FACTS 受控区块（尚未启用，或标记被删）。');
  process.exit(0);
}

if (CHECK_ONLY) {
  if (problems.length === 0) {
    console.log(`[OK] ${totalBlocks} 个受控区块全部与事实一致。`);
    process.exit(0);
  }
  console.error(`[FAIL] ${problems.length} 个受控区块与事实不一致：`);
  for (const p of problems) console.error(`  ${p.doc}  →  FACTS:${p.key}`);
  console.error('\n处理：跑 `npm run render:docs` 重新渲染并提交（区块内容禁止手改）。');
  process.exit(1);
}

console.log(
  changedFiles === 0
    ? `[OK] ${totalBlocks} 个受控区块已是最新，无需改动。`
    : `[OK] 已更新 ${changedFiles} 份文档里的受控区块（共 ${totalBlocks} 个）。`,
);
