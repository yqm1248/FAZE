/**
 * 接口事实生成器 —— 从 Controller 源码抽取端点清单，产出 docs/facts/api.json。
 *
 * 职责：
 *   扫描 `services/faze-api/**\/controller/**\/*Controller.java`，解析
 *   类级 `@RequestMapping` 前缀与方法级 `@Get/Post/Put/Patch/DeleteMapping`，
 *   拼出完整 HTTP 端点，按所属域与访问面分类，写成机器可读的 JSON。
 *
 * 调用方：
 *   - 人工：`node scripts/facts/api.mjs`
 *   - CI：生成后 `git diff --exit-code docs/facts/api.json`，
 *     不一致即说明「文档里的接口清单与代码不同步」
 *
 * 数据来源：只读 Java 源码，不连服务端、不连数据库。
 *
 * 为什么必须生成而不是手写：
 *   技术路线 §4.8 的接口清单是手写的，实测已经落后于代码。手写清单会在每次
 *   加接口时静默过期，而读它的人无从判断过期没有。生成物可以 diff。
 *
 * 注意事项：
 *   - **不解析 `@RequestMapping(method = RequestMethod.X)` 这种老写法**（本仓 0 处）。
 *     一旦出现会记为 unresolved 并在输出里报警，不静默跳过。
 *   - 路径常量拼接（`@RequestMapping(BASE + "/x")`）同样无法静态解析，记为 unresolved。
 *   - 本脚本只抽取「契约形状」，不判断权限是否正确——那要看注解与拦截器配置。
 *
 * 退出码：0 成功（含存在 unresolved 的情况，但要人工看）/ 1 未找到 Controller
 */

import { readdirSync, readFileSync, writeFileSync, statSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, relative, sep } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const CTRL_ROOT = join(REPO, 'services', 'faze-api', 'src', 'main', 'java', 'com', 'faze');
const OUT_DIR = join(REPO, 'docs', 'facts');
const OUT_FILE = join(OUT_DIR, 'api.json');

/** 递归找 *Controller.java。 */
function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) walk(full, out);
    else if (entry.endsWith('Controller.java')) out.push(full);
  }
  return out;
}

/** 从路径推断所属域（modules/<domain>/... 或 infra/<x>）。 */
function domainOf(relPath) {
  const m = relPath.match(/com\/faze\/modules\/([^/]+)\//);
  if (m) return m[1];
  const i = relPath.match(/com\/faze\/infra\/([^/]+)\//);
  if (i) return `infra/${i[1]}`;
  return 'other';
}

/** 从完整路径判断访问面。 */
function surfaceOf(path) {
  if (path.startsWith('/api/v1/pub')) return 'pub';
  if (path.startsWith('/api/v1/app')) return 'app';
  if (path.startsWith('/api/v1/admin')) return 'admin';
  if (path.startsWith('/api/v1/dev')) return 'dev';
  return 'other';
}

const files = walk(CTRL_ROOT).sort();
if (files.length === 0) {
  console.error('未找到任何 Controller，检查路径：' + CTRL_ROOT);
  process.exit(1);
}

const endpoints = [];
const unresolved = [];

for (const full of files) {
  const src = readFileSync(full, 'utf8');
  const rel = relative(REPO, full).split(sep).join('/');
  const cls = rel.split('/').pop().replace(/\.java$/, '');
  const domain = domainOf(rel);
  const isAdminDir = /\/admin\/controller\//.test(rel);

  // 类级前缀：支持单值、value= 形式，以及数组形式（一个 Controller 挂多个前缀）。
  // 数组形式的真实例子：ArticleController 同时挂 "/api/v1/pub/articles" 与 "/api/v1/app/articles"。
  const classAnnIdx = src.search(/@RequestMapping\b/);
  let classPrefixes = [];
  if (classAnnIdx < 0) {
    unresolved.push({ file: rel, reason: '类级 @RequestMapping 未解析到' });
  } else {
    // 取注解括号内的全部字符串字面量
    const after = src.slice(classAnnIdx);
    const parenStart = after.indexOf('(');
    const parenEnd = parenStart >= 0 ? after.indexOf(')', parenStart) : -1;
    const inner = parenStart >= 0 && parenEnd > parenStart ? after.slice(parenStart + 1, parenEnd) : '';
    const lits = [...inner.matchAll(/"([^"]*)"/g)].map((x) => x[1]);
    if (lits.length === 0) {
      unresolved.push({ file: rel, reason: '类级 @RequestMapping 括号内没有字符串字面量' });
    } else {
      classPrefixes = lits;
    }
  }

  // 方法级：先定位所有 @XxxMapping，再取其括号内的第一个字符串字面量作为子路径。
  // 不能用一个正则同时处理「无参」「带路径」「带额外属性」三种写法——会把
  // `@PostMapping(value = "/import", consumes = ...)` 的路径截掉，退化成类前缀（曾误报重复端点）。
  const annRe = /@(Get|Post|Put|Patch|Delete)Mapping\b/g;
  let m;
  let count = 0;
  while ((m = annRe.exec(src)) !== null) {
    const httpMethod = m[1].toUpperCase();
    const rest = src.slice(m.index + m[0].length);
    let sub = '';
    if (/^\s*\(/.test(rest)) {
      const q = rest.match(/^[^)]*?"([^"]*)"/);
      if (q) sub = q[1];
    }
    for (const prefix of classPrefixes) {
      const fullPath = (prefix + sub).replace(/\/+/g, '/');
      endpoints.push({
        domain,
        controller: cls,
        method: httpMethod,
        path: fullPath,
        surface: surfaceOf(fullPath),
        adminDir: isAdminDir,
      });
    }
    count += 1;
  }
  if (count === 0) unresolved.push({ file: rel, reason: '方法级 Mapping 未解析到' });
}

// 稳定排序，保证 diff 只反映真实变化
endpoints.sort((a, b) =>
  a.path === b.path ? a.method.localeCompare(b.method) : a.path.localeCompare(b.path),
);

// 重复端点检查。
// 注意：同一 method + path 出现两次**不一定**是冲突——Spring 允许按 `consumes`
// 或 `params` 分派（本仓真实例子：`POST /admin/law-items/import` 一份收 JSON、
// 一份收 multipart）。所以这里区分两种：
//   conflict       = 跨 Controller 的同一 method+path（真正的映射冲突）
//   sameController = 同一 Controller 内（几乎都是 consumes/params 分派，人工确认即可）
const seen = new Map();
const duplicates = [];
for (const e of endpoints) {
  const key = `${e.method} ${e.path}`;
  if (seen.has(key)) {
    duplicates.push({
      key,
      first: seen.get(key),
      second: e.controller,
      sameController: seen.get(key) === e.controller,
    });
  } else seen.set(key, e.controller);
}
const realConflicts = duplicates.filter((d) => !d.sameController);

const bySurface = {};
const byDomain = {};
const byMethod = {};
for (const e of endpoints) {
  bySurface[e.surface] = (bySurface[e.surface] ?? 0) + 1;
  byDomain[e.domain] = (byDomain[e.domain] ?? 0) + 1;
  byMethod[e.method] = (byMethod[e.method] ?? 0) + 1;
}

// 源指纹：对全部 Controller 源码做稳定哈希。
// **不要改回 generatedAt（当天日期）**：那会让 CI 的 `git diff --exit-code docs/facts/`
// 在次日必然失败（内容没变、日期变了），把门禁变成日历。源码不变则本字段不变。
const digest = createHash('sha256');
for (const f of files.slice().sort()) {
  digest.update(relative(REPO, f).split(sep).join('/'));
  digest.update(readFileSync(f));
}
const sourceDigest = digest.digest('hex').slice(0, 16);

const payload = {
  _comment:
    '本文件由 scripts/facts/api.mjs 从 Controller 源码生成，禁止手改。口径见 docs/standards/说辞一致与真相源规范.md §四。',
  sourceDigest,
  sourceRoot: 'services/faze-api/src/main/java/com/faze',
  totals: {
    controllers: files.length,
    endpoints: endpoints.length,
    unresolved: unresolved.length,
    duplicateKeys: duplicates.length,
    realConflicts: realConflicts.length,
  },
  bySurface,
  byDomain,
  byMethod,
  duplicates,
  unresolved,
  endpoints,
};

mkdirSync(OUT_DIR, { recursive: true });
writeFileSync(OUT_FILE, JSON.stringify(payload, null, 2) + '\n', 'utf8');

console.log('=== 接口事实（生成自源码）===');
console.log('Controller 文件      ', files.length);
console.log('端点总数            ', endpoints.length);
console.log('按访问面            ', JSON.stringify(bySurface));
console.log('按 HTTP 方法        ', JSON.stringify(byMethod));
console.log('\n按业务域：');
for (const [d, n] of Object.entries(byDomain).sort((a, b) => b[1] - a[1])) {
  console.log(`  ${d.padEnd(20)} ${n}`);
}
if (duplicates.length > 0) {
  console.log('\n同一 method+path 出现多次（' + duplicates.length + ' 组）：');
  for (const d of duplicates) {
    const tag = d.sameController ? '同 Controller，按 consumes/params 分派（人工确认）' : '**跨 Controller 映射冲突**';
    console.log(`  ${d.key}  —— ${tag}`);
  }
  if (realConflicts.length > 0) {
    console.log('  [FAIL] 存在 ' + realConflicts.length + ' 组真正的映射冲突，服务可能启动失败');
  }
}
if (unresolved.length > 0) {
  console.log('\n[WARN] 未解析 ' + unresolved.length + ' 处（需人工确认）：');
  for (const u of unresolved) console.log(`  ${u.file}  ${u.reason}`);
}
console.log('\n已写 ' + relative(REPO, OUT_FILE).split(sep).join('/'));
