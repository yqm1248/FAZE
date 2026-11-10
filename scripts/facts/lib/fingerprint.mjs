/**
 * 基线指纹计算 —— 一处实现，基线表与校验脚本共用。
 *
 * 职责：
 *   按 `docs/BASELINE.md` §二 的口径计算一份文档的「行数 / 字节 / SHA256 前 16 位」：
 *   **一律按 LF 口径**（把 CRLF 归一成 LF 之后再算），行数 = **LF 换行符个数**。
 *
 *   行数为什么是「LF 个数」而不是「LF 个数 + 1」：BASELINE §二 的说明原文写的是
 *   「LF 换行符个数 + 1」，但 §二 实际登记的**全部是 LF 个数**（2026-11-11 用本模块
 *   逐份实测 24 份差异，其中 18 份字节与指纹完全一致、只有行数差 1，说明口径说明写错了、
 *   数字是对的）。所以本模块按 LF 个数算，并把 BASELINE 的说明一并改正——
 *   **说明与数字不一致时，以实测数字为准，改说明**。
 *
 * 调用方：
 *   - `scripts/facts/check-baseline.mjs`（校验 BASELINE §二 登记的指纹是否过期）
 *   - 需要出新基线版本时，人工调用本模块打印实测值
 *
 * 数据来源：只读文件系统。
 *
 * 为什么必须归一 LF：
 *   踩过两次。第一次是 v1.3：本机 `core.autocrlf = true` 会把检出转成 CRLF，按工作区
 *   算出来的指纹别人 clone 下来对不上，等于给了个复现不了的基线。第二次是 v1.6：13 份
 *   文档被写回 CRLF（`.gitattributes` 只约束 git 检出，管不到写入工具）。所以**不许按
 *   工作区原始字节算**，必须先把 CRLF 归一成 LF 再算。
 *
 * 注意事项：
 *   - 「行数 = LF 换行符个数」是 BASELINE 既有登记值实际采用的口径（等同 `wc -l`）。
 *     不要改成「LF 个数 + 1」：那会让全表 47 份资产的行数整体偏移 1，看起来像全部过期。
 *   - 指纹取 SHA256 前 16 位小写十六进制，与 BASELINE §二 一致。
 */

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

/**
 * 计算一份文件的 LF 口径指纹。
 *
 * @param {string} absPath 绝对路径
 * @returns {{ lines: number, bytes: number, sha16: string }}
 */
export function fingerprintFile(absPath) {
  const raw = readFileSync(absPath);
  // 先按 latin1 解码再还原成字节，避免 UTF-8 多字节序列被字符串操作破坏
  const normalized = raw.toString('latin1').replace(/\r\n/g, '\n');
  const buf = Buffer.from(normalized, 'latin1');
  const lines = (normalized.match(/\n/g) ?? []).length;
  const sha16 = createHash('sha256').update(buf).digest('hex').slice(0, 16);
  return { lines, bytes: buf.length, sha16 };
}

/**
 * 仅计算指纹（不关心行数/字节）时的便捷函数。
 *
 * @param {string} absPath 绝对路径
 * @returns {string} SHA256 前 16 位
 */
export function sha16Of(absPath) {
  return fingerprintFile(absPath).sha16;
}
