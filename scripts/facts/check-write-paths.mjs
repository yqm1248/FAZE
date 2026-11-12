/**
 * 「每个字段必须有写入路径」检查（回归清单 R-15）。
 *
 * 职责：
 *   对 `docs/facts/tables.json` 里的每个业务字段，判断它在**代码或种子数据里有没有写入证据**。
 *   找不到写入证据的字段，要么在基线台账里（有 reason + closeWhen），要么报错。
 *
 * 为什么这条值得查：
 *   表里有字段、接口能读出来、但**没有任何代码写它**——那个字段永远是 NULL，
 *   界面上永远空着，而类型检查、单元测试、CI 都不会报。本仓已经踩过两次：
 *   `court_name`（法院名称永远为空，DRIFT-011）与 `view_count`（浏览量永远 0）。
 *
 * 写入证据的六种来源（命中任一条即算「有写入路径」）：
 *   1. Java 实体 setter：`setXxx(`
 *   2. Java lambda 方法引用：`::getXxx`（MyBatis-Plus UpdateWrapper.set 的写法）
 *   3. Java/XML 字符串字面量：`"col"`（`.set("col", ...)` 这类）
 *   4. XML 参数占位：`#{camel}`
 *   5. XML 裸列名：手写 `UPDATE t SET col = ...`（不带引号，所以单独判）
 *   6. 种子/迁移 SQL：`database/seed/**` 与迁移里的 `INSERT INTO <table>` 语句
 *      （D 类基础数据由 Flyway 灌，不经 Java，这是合法路径）
 *
 * 排除的系统列：
 *   `id` / `created_at` / `updated_at` / `deleted_at` / `version` / 一切 `_at` 结尾。
 *   它们由 MyBatis-Plus 自动填充或 DB 默认值负责，不是业务写入。
 *
 * 基线：
 *   `scripts/facts/write-paths-baseline.json` 记录「当前确实没有写入证据、但已定性」的字段。
 *   新增的无写入字段 → 失败。每条基线必须写 reason 与 closeWhen。
 *
 * 已知局限（**别把这道检查当全量证明**）：
 *   - 只做**静态**扫描，扫不到反射、动态 SQL 拼接出的列名、DB 触发器写入的字段。
 *   - 判据是「文本里出现过」，注释里提到列名也会被算成有写入——所以**它是"查新增缺口"的
 *     工具，不是"证明所有字段都有写入"的证明**。
 *   - 反过来：一个字段有写入证据，不代表写入路径正确（可能写了但永远写错值）。
 *
 * 退出码：0 无新增缺口 / 1 有未登记的无写入字段 / 2 输入文件缺失
 */

import { readFileSync, readdirSync, statSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const TABLES_FILE = join(REPO, 'docs', 'facts', 'tables.json');
const BASELINE_FILE = join(REPO, 'scripts', 'facts', 'write-paths-baseline.json');
const UPDATE = process.argv.includes('--update');

if (!existsSync(TABLES_FILE)) {
  console.error(`[FAIL] 找不到 ${TABLES_FILE}，先跑 npm run facts`);
  process.exit(2);
}

/** 递归收集指定扩展名的文件。 */
function walk(dir, ext, out = []) {
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir)) {
    const p = join(dir, f);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, ext, out);
    else if (f.endsWith(ext)) out.push(p);
  }
  return out;
}

const JAVA_DIR = join(REPO, 'services', 'faze-api', 'src', 'main', 'java');
const RES_DIR = join(REPO, 'services', 'faze-api', 'src', 'main', 'resources');
const XML_DIR = join(RES_DIR, 'mapper');
const MIG_DIR = join(RES_DIR, 'db', 'migration');
const SEED_DIR = join(REPO, 'database');

const javaText = walk(JAVA_DIR, '.java').map((f) => readFileSync(f, 'utf8')).join('\n');
const xmlText = walk(XML_DIR, '.xml').map((f) => readFileSync(f, 'utf8')).join('\n');
const seedText = walk(SEED_DIR, '.sql').map((f) => readFileSync(f, 'utf8')).join('\n');

// 迁移里的 INSERT 语句才构成「写入路径」；CREATE TABLE 里的列名不算（那是建表不是写数据）
const insertText = walk(MIG_DIR, '.sql')
  .map((f) => readFileSync(f, 'utf8'))
  .join('\n')
  .split(';')
  .filter((stmt) => /INSERT\s+INTO/i.test(stmt))
  .join('\n');

const tables = JSON.parse(readFileSync(TABLES_FILE, 'utf8')).tables;
const baseline = existsSync(BASELINE_FILE)
  ? JSON.parse(readFileSync(BASELINE_FILE, 'utf8'))
  : { known: [] };
const knownKeys = new Set((baseline.known ?? []).map((x) => x.field));

const toCamel = (s) => s.replace(/_([a-z])/g, (_, c) => c.toUpperCase());

function isSystemColumn(col) {
  return (
    col === 'id' ||
    col === 'created_at' ||
    col === 'updated_at' ||
    col === 'deleted_at' ||
    col === 'version' ||
    col.endsWith('_at')
  );
}

/** 判断一个字段有没有写入证据。返回命中的证据类型（用于报告）。 */
function writeEvidence(table, col) {
  const camel = toCamel(col);
  const cap = camel.charAt(0).toUpperCase() + camel.slice(1);
  if (new RegExp(`set${cap}\\s*\\(`).test(javaText)) return 'java-setter';
  if (new RegExp(`::get${cap}\\b`).test(javaText)) return 'java-method-ref';
  if (javaText.includes(`"${col}"`) || xmlText.includes(`"${col}"`)) return 'string-literal';
  if (new RegExp(`#\\{\\s*${camel}\\s*[,\\}]`).test(xmlText)) return 'xml-param';
  if (new RegExp(`\\b${col}\\b`).test(xmlText)) return 'xml-column';
  if (new RegExp(`\\b${col}\\b`).test(seedText)) return 'seed-sql';
  // INSERT 语句里必须同时出现表名与列名，避免「别的表也叫这个列名」的误判
  const insertForTable = insertText
    .split(';')
    .filter((stmt) => new RegExp(`INSERT\\s+INTO\\s+\`?${table}\`?\\b`, 'i').test(stmt))
    .join('\n');
  if (insertForTable && new RegExp(`\\b${col}\\b`).test(insertForTable)) return 'migration-insert';
  return null;
}

const missing = [];
let bizFields = 0;
for (const t of tables) {
  for (const colDef of t.columns) {
    const col = colDef.split(':')[0];
    if (isSystemColumn(col)) continue;
    bizFields++;
    if (writeEvidence(t.name, col)) continue;
    missing.push({ field: `${t.name}.${col}`, table: t.name, column: col });
  }
}

console.log('=== 字段写入路径检查（R-15）===');
console.log(`业务字段 ${bizFields} 个（已排除 id / *_at / version 等系统列）`);
console.log(`无写入证据 ${missing.length} 个，基线登记 ${knownKeys.size} 个`);

const unregistered = missing.filter((m) => !knownKeys.has(m.field));
const stale = [...knownKeys].filter((k) => !missing.some((m) => m.field === k));

for (const m of missing) {
  const mark = knownKeys.has(m.field) ? '[基线]' : '[新增]';
  console.log(`  ${mark} ${m.field}`);
}
for (const s of stale) {
  console.log(`  [过期] ${s} —— 已有写入证据，请从基线删掉这一条`);
}

if (UPDATE) {
  const next = {
    _comment:
      '「有读无写」字段的**已知基线**（R-15）。这里列出的字段当前确实找不到任何写入证据，但已逐条定性：要么是设计上允许为空，要么缺口已登记在 DEFERRED.md / DRIFT.md。新增的无证据字段不在这个文件里 → `check:write-paths` 会失败。每条必须写 reason 与 closeWhen。',
    _rules: [
      'field 格式为 <表名>.<列名>；表名与列名都用真实名字，不要写驼峰。',
      '新增一条基线必须同时在 docs/acceptance/guard/G9-回归清单落地.md 记一笔。',
      '字段后来补了写入路径 → 本检查会报「过期」，此时删掉这一条。',
    ],
    known: missing.map((m) => ({
      field: m.field,
      reason: '待补：见本文件生成后的人工定性（设计允许为空 / 缺口已登记）',
      closeWhen: '待补',
    })),
  };
  writeFileSync(BASELINE_FILE, JSON.stringify(next, null, 2) + '\n');
  console.log(`\n[更新] 已写 ${BASELINE_FILE}（${missing.length} 条，reason/closeWhen 需人工补）`);
  process.exit(0);
}

if (unregistered.length > 0) {
  console.error(`\n[FAIL] ${unregistered.length} 个字段没有任何写入证据，且不在基线里。`);
  console.error('处理：① 补写入路径；② 或登记到 scripts/facts/write-paths-baseline.json 并写 reason + closeWhen。');
  process.exit(1);
}

if (stale.length > 0) {
  console.error(`\n[FAIL] 基线里 ${stale.length} 条已过期（字段现在有写入证据），删掉它们。`);
  process.exit(1);
}

console.log('\n[OK] 没有新增的「有读无写」字段，基线也没有过期条目。');
