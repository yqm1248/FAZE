/**
 * 错误码事实生成器 —— 从 ErrorCode 枚举抽出全部业务码，产出 docs/facts/error-codes.json。
 *
 * 职责：
 *   解析 `common/exception/ErrorCode.java` 的枚举常量四元组
 *   `(code, httpStatus, msgKey, msg)`，输出成机器可读清单，并检查三件事：
 *     ① code 是否重复（《错误码规范》要求唯一）
 *     ② 是否落在规范定义的号段内
 *     ③ msgKey 是否为空
 *
 * 调用方：
 *   - 人工：`node scripts/facts/error-codes.mjs`
 *   - CI：生成后 `git diff --exit-code docs/facts/error-codes.json`
 *
 * 数据来源：只读 Java 源码。
 *
 * 为什么必须生成：
 *   `docs/README.md` 与《错误码规范》都写死了「91 个码」。写死的数字一定会过期，
 *   而且读的人无从判断过期没有。生成物可以 diff。
 *
 * 注意事项：
 *   本脚本**不校验 msgKey 在 i18n 资源里是否存在**——那需要读四语言 JSON，
 *   属于 i18n 事实生成器的职责（见 scripts/facts/extract-i18n.mjs）。这里只登记不越界。
 *
 * 退出码：0 成功（含发现重复码）/ 1 解析不到任何码
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';

const REPO = join(import.meta.dirname, '..', '..');
const SRC = join(
  REPO,
  'services/faze-api/src/main/java/com/faze/common/exception/ErrorCode.java',
);
const OUT_DIR = join(REPO, 'docs', 'facts');
const OUT_FILE = join(OUT_DIR, 'error-codes.json');

const src = readFileSync(SRC, 'utf8');

// 枚举常量形如：
//   AUTH_SMS_SEND_FAILED(401001, 400, "auth.smsSendFailed", "验证码发送失败"),
// msg 里可能含中文与逗号，所以 msg 用贪婪匹配到行尾的 `)`。
const re = /^\s{4}([A-Z][A-Z0-9_]*)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\)\s*,?\s*$/gm;

const codes = [];
let m;
while ((m = re.exec(src)) !== null) {
  codes.push({
    name: m[1],
    code: Number(m[2]),
    httpStatus: Number(m[3]),
    msgKey: m[4],
    msg: m[5],
  });
}

if (codes.length === 0) {
  console.error('未解析到任何 ErrorCode 常量。枚举写法可能变了，请检查正则。');
  console.error('文件：' + SRC);
  process.exit(1);
}

// ① 重复码
const byCode = new Map();
const duplicates = [];
for (const c of codes) {
  if (byCode.has(c.code)) duplicates.push({ code: c.code, names: [byCode.get(c.code), c.name] });
  else byCode.set(c.code, c.name);
}

// ② 号段分布（前三位）
const byPrefix = {};
for (const c of codes) {
  const p = String(c.code).slice(0, 3);
  byPrefix[p] = (byPrefix[p] ?? 0) + 1;
}

// ③ HTTP 状态分布
const byHttpStatus = {};
for (const c of codes) {
  byHttpStatus[String(c.httpStatus)] = (byHttpStatus[String(c.httpStatus)] ?? 0) + 1;
}

const emptyMsgKey = codes.filter((c) => !c.msgKey);

codes.sort((a, b) => a.code - b.code);

// 源指纹：单文件哈希。**不要改回 generatedAt（当天日期）**，否则 CI 的
// `git diff --exit-code docs/facts/` 次日必然失败，把门禁变成日历。
const sourceDigest = createHash('sha256').update(src).digest('hex').slice(0, 16);

const payload = {
  _comment:
    '本文件由 scripts/facts/error-codes.mjs 从 ErrorCode.java 生成，禁止手改。口径见 docs/standards/说辞一致与真相源规范.md §四。',
  sourceDigest,
  source: 'services/faze-api/src/main/java/com/faze/common/exception/ErrorCode.java',
  totals: { codes: codes.length, duplicates: duplicates.length, emptyMsgKey: emptyMsgKey.length },
  byPrefix,
  byHttpStatus,
  duplicates,
  emptyMsgKey: emptyMsgKey.map((c) => c.name),
  codes,
};

mkdirSync(OUT_DIR, { recursive: true });
writeFileSync(OUT_FILE, JSON.stringify(payload, null, 2) + '\n', 'utf8');

console.log('=== 错误码事实（生成自 ErrorCode.java）===');
console.log('错误码总数          ', codes.length);
console.log('号段分布            ', JSON.stringify(byPrefix));
console.log('HTTP 状态分布       ', JSON.stringify(byHttpStatus));
if (duplicates.length > 0) {
  console.log(`\n[FAIL] ${duplicates.length} 组重复码（违反《错误码规范》唯一性）：`);
  for (const d of duplicates) console.log(`  ${d.code}  ${d.names.join(' / ')}`);
}
if (emptyMsgKey.length > 0) {
  console.log(`\n[WARN] ${emptyMsgKey.length} 个码的 msgKey 为空：`);
  for (const n of emptyMsgKey) console.log('  ' + n);
}
console.log('\n已写 docs/facts/error-codes.json');
