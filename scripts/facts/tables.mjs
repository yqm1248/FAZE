/**
 * 表结构事实生成器 —— 从**已应用的迁移 + 实际数据库**抽出表结构，产出 docs/facts/tables.json。
 *
 * 职责：
 *   读 information_schema（表/列/索引）与 flyway_schema_history（已应用版本），
 *   写成机器可读的 JSON，供文档与验收比对「文档里写的表结构」和「库里真实的表结构」。
 *
 * 调用方：
 *   - 人工：`node scripts/facts/tables.mjs`
 *   - CI：backend job（已有 MySQL service）里生成后 `git diff --exit-code docs/facts/tables.json`
 *
 * 数据来源：MySQL `information_schema` + `flyway_schema_history`。**只读。**
 *
 * 为什么真相源是数据库而不是文档：
 *   《数据表规范》附录 A 写「55 张表」，实测只建了 53（`invoice` / `share_poster` 未建）。
 *   文档记录的是计划，`information_schema` 记录的是事实。两者不一样时，
 *   按《说辞一致与真相源规范》§一，事实是 P0。
 *
 * 注意事项：
 *   - **Flyway 的 version 是字符串**，`MAX(version)` 会得到 '9' 而不是 '711'。
 *     本脚本按 `installed_rank` 排序取最后一条，不按 version 字面量排序。
 *   - 连接参数从环境变量读：`FAZE_DB_URL` 不解析（那是 JDBC 串），用 `FAZE_DB_HOST/PORT/USER/PASSWORD`，
 *     缺省回落到本机开发库。密码走 `MYSQL_PWD` 环境变量传给子进程，不出现在命令行里。
 *
 * 退出码：0 成功 / 1 mysql 不可用或查询失败
 */

import { execFileSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const OUT_DIR = join(REPO, 'docs', 'facts');
const OUT_FILE = join(OUT_DIR, 'tables.json');

const DB = {
  host: process.env.FAZE_DB_HOST ?? '127.0.0.1',
  port: process.env.FAZE_DB_PORT ?? '3306',
  user: process.env.FAZE_DB_USER ?? 'faze_app',
  password: process.env.FAZE_DB_PASSWORD ?? '123456',
  name: process.env.FAZE_DB_NAME ?? 'faze',
};

/** 跑一条 SQL，返回按行切分的二维数组（不带表头）。 */
function query(sql) {
  const args = [
    `--host=${DB.host}`,
    `--port=${DB.port}`,
    `--user=${DB.user}`,
    '--default-character-set=utf8mb4',
    '-N',
    '-B',
    '--raw',
    DB.name,
    '-e',
    sql,
  ];
  const out = execFileSync('mysql', args, {
    encoding: 'utf8',
    env: { ...process.env, MYSQL_PWD: DB.password },
    maxBuffer: 32 * 1024 * 1024,
  });
  return out
    .split('\n')
    .filter((l) => l.length > 0)
    .map((l) => l.split('\t'));
}

let tableRows;
let colRows;
let idxRows;
let fwRows;
try {
  tableRows = query(
    `SELECT table_name, IFNULL(table_comment,'')
       FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
      ORDER BY table_name;`,
  );
  colRows = query(
    `SELECT table_name, column_name, column_type, is_nullable, IFNULL(column_comment,'')
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
      ORDER BY table_name, ordinal_position;`,
  );
  idxRows = query(
    `SELECT table_name, index_name, non_unique,
            GROUP_CONCAT(column_name ORDER BY seq_in_index)
       FROM information_schema.statistics
      WHERE table_schema = DATABASE()
      GROUP BY table_name, index_name, non_unique
      ORDER BY table_name, index_name;`,
  );
  fwRows = query(
    `SELECT version, description, success, installed_rank
       FROM flyway_schema_history
      ORDER BY installed_rank;`,
  );
} catch (e) {
  console.error('无法连接 MySQL 或查询失败：' + e.message.split('\n')[0]);
  console.error('本脚本需要真实数据库（表结构的真相源）。请先启动 MySQL 并确认 FAZE_DB_* 环境变量。');
  process.exit(1);
}

// 按表聚合列
const colByTable = new Map();
for (const [t, c, type, nullable, comment] of colRows) {
  if (!colByTable.has(t)) colByTable.set(t, []);
  colByTable.get(t).push({ name: c, type, nullable: nullable === 'YES', comment });
}

// 按表聚合索引
const idxByTable = new Map();
for (const [t, name, nonUnique, cols] of idxRows) {
  if (!idxByTable.has(t)) idxByTable.set(t, []);
  idxByTable.get(t).push({
    name,
    unique: nonUnique === '0',
    columns: (cols ?? '').split(','),
  });
}

const tables = tableRows.map(([name, comment]) => {
  const cols = colByTable.get(name) ?? [];
  const idx = idxByTable.get(name) ?? [];
  const pk = idx.find((i) => i.name === 'PRIMARY');
  return {
    name,
    comment,
    columnCount: cols.length,
    primaryKey: pk ? pk.columns : [],
    uniqueKeys: idx.filter((i) => i.unique && i.name !== 'PRIMARY').map((i) => i.name),
    indexCount: idx.length,
    columns: cols.map((c) => `${c.name}:${c.type}`),
  };
});

// Flyway：按 installed_rank 取最后一条，不用 MAX(version)（version 是字符串，'9' > '711'）
const applied = fwRows.filter((r) => r[2] === '1').map((r) => ({ version: r[0], description: r[1] }));
const latest = fwRows.length > 0 ? fwRows[fwRows.length - 1][0] : null;

const payload = {
  _comment:
    '本文件由 scripts/facts/tables.mjs 从 information_schema + flyway_schema_history 生成，禁止手改。口径见 docs/standards/说辞一致与真相源规范.md §四。',
  generatedAt: new Date().toISOString().slice(0, 10),
  database: DB.name,
  totals: {
    tables: tables.length,
    columns: tables.reduce((s, t) => s + t.columnCount, 0),
    indexes: idxRows.length,
    flywayApplied: applied.length,
    flywayLatest: latest,
  },
  flyway: { applied },
  tables,
};

mkdirSync(OUT_DIR, { recursive: true });
writeFileSync(OUT_FILE, JSON.stringify(payload, null, 2) + '\n', 'utf8');

console.log('=== 表结构事实（生成自数据库）===');
console.log('业务表数            ', payload.totals.tables);
console.log('列总数              ', payload.totals.columns);
console.log('索引数              ', payload.totals.indexes);
console.log('Flyway 已应用       ', payload.totals.flywayApplied, '最新版本', latest);
console.log('\n列数最多的 10 张表：');
for (const t of [...tables].sort((a, b) => b.columnCount - a.columnCount).slice(0, 10)) {
  console.log(`  ${t.name.padEnd(28)} ${String(t.columnCount).padStart(3)} 列  ${t.comment}`);
}
const noComment = tables.filter((t) => !t.comment);
if (noComment.length > 0) {
  console.log(`\n[WARN] ${noComment.length} 张表没有表注释（《数据表规范》要求必须有）：`);
  for (const t of noComment) console.log('  ' + t.name);
}
const noPk = tables.filter((t) => t.primaryKey.length === 0);
if (noPk.length > 0) {
  console.log(`\n[WARN] ${noPk.length} 张表没有主键：`);
  for (const t of noPk) console.log('  ' + t.name);
}
console.log('\n已写 docs/facts/tables.json');
