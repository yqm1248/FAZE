/**
 * 段 4 的自动探针（供 deploy/verify-seg4.ps1 判定 8 条锚点用）。
 *
 * 职责：把「能用接口完成的部分」一次性做完，产出事实（哈希、状态、URL 有效性、分页/筛选结果），
 *       写成一份 JSON 给 PowerShell 判定；PDF 内容层面的判据交给 deploy/tools/PdfInspect.java。
 * 调用方：`deploy/verify-seg4.ps1`（node 本脚本 → 解析 JSON → 判 PASS/FAIL）。
 * 数据来源：faze-api `http://127.0.0.1:8081`，测试账号 13800000001/123456（database/seed/test.sql）。
 * 注意：
 *   1. **用 node 而不是 PowerShell 的 Invoke-RestMethod**：PS 5.1 对 `application/json`（无 charset）
 *      按 ISO-8859-1 解码，中文会变乱码（2026-11-07 踩过，把模板中文写坏过一次）。
 *   2. 本脚本**不碰数据库**：SQL 相关的锚点（4-A4/4-A8）由 PS 侧用 mysql 客户端做，
 *      避免 node 通过管道调用外部进程（受沙箱限制，EPERM）。
 *   3. 输出：stdout 最后一行是 `__JSON__{...}`，其余行是人类可读日志。
 *   4. 会新建一份文书并盖章（**这是验收数据**，上线前随测试数据清理）。
 *
 * 用法：node deploy/tools/seg4-probe.mjs <输出目录> [--skip-expiry]
 */

import { mkdirSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { join } from 'node:path';

const API = process.env.FAZE_API_BASE ?? 'http://127.0.0.1:8081';
const OUT = process.argv[2] ?? 'docs/acceptance/seg4/_auto';
const SKIP_EXPIRY = process.argv.includes('--skip-expiry');
const PHONE = '13800000001';
const PASSWORD = '123456';

mkdirSync(OUT, { recursive: true });

/** 打印人类可读日志并累计到 facts.log。 */
const logLines = [];
function log(msg) {
  logLines.push(msg);
  console.log(msg);
}

/** 发请求；返回 {code,data,msg,status}。 */
async function call(path, { method = 'GET', body, token } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(API + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text === '' ? null : JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: res.status, ...(json ?? {}) };
}

/** SHA-256 十六进制（node 内置）。 */
async function sha256(buf) {
  const { createHash } = await import('node:crypto');
  return createHash('sha256').update(buf).digest('hex');
}

/** 下载 URL 到内存。 */
async function download(url) {
  const res = await fetch(url);
  return { status: res.status, buf: Buffer.from(await res.arrayBuffer()) };
}

/**
 * 生成一张透明底 PNG（黑色两笔曲线）。
 *
 * @param width 宽（像素）
 * @param height 高（像素）
 * @returns PNG 字节
 */
function makeSignaturePng(width, height) {
  const px = Buffer.alloc(width * height * 4, 0);
  const stroke = (x, y) => {
    if (x < 0 || y < 0 || x >= width || y >= height) return;
    const i = (y * width + x) * 4;
    px[i] = 17;
    px[i + 1] = 17;
    px[i + 2] = 17;
    px[i + 3] = 255;
  };
  for (let t = 0; t <= 1; t += 0.0005) {
    const x = Math.round(20 + t * (width - 40));
    const y = Math.round(height / 2 + Math.sin(t * Math.PI * 2) * height * 0.28);
    for (let d = -1; d <= 1; d++) stroke(x, y + d);
  }
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    px.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const typeBuf = Buffer.from(type, 'ascii');
    const crcBuf = Buffer.alloc(4);
    crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])) >>> 0);
    return Buffer.concat([len, typeBuf, data, crcBuf]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** CRC32（PNG 分块用）。 */
function crc32(buf) {
  let c = ~0;
  for (const byte of buf) {
    c ^= byte;
    for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1;
  }
  return ~c;
}

const facts = { generatedAt: new Date().toISOString(), api: API };

/**
 * 上传一张签名图并入库；库满（406005）时先删掉一个未被引用的旧签名再试。
 *
 * @param png PNG 字节
 * @param fileName 原始文件名
 * @returns file_record 签名主键（失败 null）
 */
async function addSignature(png, fileName) {
  const tok = await call('/api/v1/app/upload/token', {
    method: 'POST',
    token,
    body: { scene: 'signature', fileName, size: png.length, contentType: 'image/png' },
  });
  await fetch(tok.data.uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'image/png' }, body: png });
  let add = await call('/api/v1/app/signatures', { method: 'POST', token, body: { imageOssKey: tok.data.key } });
  // 库满时按 D1 规则反复剔掉「未被引用」的旧签名（被引用的返回 406006，跳过），最多腾 5 次
  for (let attempt = 0; add.code === 406005 && attempt < 5; attempt++) {
    const lib = await call('/api/v1/app/signatures', { token });
    let purged = false;
    for (const item of lib.data ?? []) {
      const del = await call(`/api/v1/app/signatures/${item.id}`, { method: 'DELETE', token });
      if (del.code === 200) {
        facts.purgedSignatureId = item.id;
        log(`签名库已满，删掉未引用的旧签名 signatureId=${item.id} 腾出名额`);
        purged = true;
        break;
      }
    }
    if (!purged) break;
    add = await call('/api/v1/app/signatures', { method: 'POST', token, body: { imageOssKey: tok.data.key } });
  }
  facts.signatureAddCode = add.code;
  return add.data ?? null;
}

// ── 1. 登录 ────────────────────────────────────────────────────────────────
const login = await call('/api/v1/pub/auth/login/password', {
  method: 'POST',
  body: { phone: PHONE, password: PASSWORD, deviceId: 'seg4-probe' },
});
if (login.code !== 200) {
  console.error('登录失败', login);
  process.exit(2);
}
const token = login.data.accessToken;
facts.userId = login.data.userId;
log(`登录成功 userId=${facts.userId}`);

// ── 2. 模板 ────────────────────────────────────────────────────────────────
const tpls = await call('/api/v1/app/doc-templates', { token });
facts.templates = (tpls.data ?? []).map((t) => ({ id: t.id, code: t.code, name: t.name }));
log(`模板 ${facts.templates.length} 个：${facts.templates.map((t) => `${t.code}#${t.id}`).join(', ')}`);
const lawsuit = facts.templates.find((t) => t.code === 'LAWSUIT-001');
if (!lawsuit) {
  console.error('没有 active 的 LAWSUIT-001');
  process.exit(2);
}
facts.templateId = lawsuit.id;

// ── 3. 建草稿 → 生成 ───────────────────────────────────────────────────────
const form = {
  court_name: '伊宁市人民法院',
  plaintiffs: [
    { name: '马合木提·艾山', gender: '男', birth: '1985-03-12', ethnicity: '维吾尔族', address: '伊宁市解放路 1 号', phone: '13900000001' },
    { name: '古丽娜尔·伊明', gender: '女', birth: '1988-07-20', ethnicity: '维吾尔族', address: '伊宁市解放路 1 号', phone: '13900000002' },
    { name: '艾尔肯·买买提', gender: '男', birth: '1990-01-05', ethnicity: '维吾尔族', address: '伊宁市斯大林街 8 号', phone: '13900000003' },
  ],
  defendants: [
    { name: '伊宁市恒通商贸有限公司', address: '伊宁市斯大林街 2 号', phone: '13900000004' },
    { name: '王建国', address: '伊宁市新华东路 15 号', phone: '13900000005' },
  ],
  claims: [
    { text: '请求判令被告偿还借款本金人民币 100000 元' },
    { text: '请求判令被告支付逾期利息人民币 5000 元' },
  ],
  facts: '被告于 2026 年 1 月向原告借款人民币 100000 元，约定三个月内归还，至今未还。原告多次催讨无果，故诉至贵院。',
  filing_date: '2026-11-07',
};
const saved = await call('/api/v1/app/documents', {
  method: 'POST',
  token,
  body: { templateId: lawsuit.id, title: '验收探针文书', formData: JSON.stringify(form) },
});
if (saved.code !== 200) {
  console.error('保存草稿失败', saved);
  process.exit(2);
}
const docId = saved.data;
facts.docId = docId;
log(`草稿 documentId=${docId}`);

await call(`/api/v1/app/documents/${docId}/generate`, { method: 'POST', token });

/** 轮询到 generated / failed。 */
async function waitDone(id, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 2000));
    const d = await call(`/api/v1/app/documents/${id}`, { token });
    last = d.data;
    if (last && (last.status === 'generated' || last.status === 'failed')) return last;
  }
  return last;
}

const detail1 = await waitDone(docId);
facts.status = detail1.status;
facts.templateVersion = detail1.templateVersion;
facts.generating = detail1.generating;
log(`生成结果 status=${detail1.status} templateVersion=${detail1.templateVersion} generating=${detail1.generating}`);

// ── 4. 预览 ────────────────────────────────────────────────────────────────
const previewT0 = Date.now();
const preview = await call(`/api/v1/app/documents/${docId}/preview`, { token });
const previewMs1 = Date.now() - previewT0;
const previewT1 = Date.now();
await call(`/api/v1/app/documents/${docId}/preview`, { token });
const previewMs2 = Date.now() - previewT1;
facts.preview = {
  pages: (preview.data ?? []).length,
  pageW: preview.data?.[0]?.widthPt ?? 0,
  pageH: preview.data?.[0]?.heightPt ?? 0,
  msFirst: previewMs1,
  msCached: previewMs2,
};
if (preview.data?.[0]?.imageUrl) {
  const png = await download(preview.data[0].imageUrl);
  facts.preview.firstImageBytes = png.buf.length;
  writeFileSync(join(OUT, 'preview-page1.png'), png.buf);
}
log(`预览 ${facts.preview.pages} 页 ${facts.preview.pageW}x${facts.preview.pageH}pt，首次 ${previewMs1}ms / 缓存 ${previewMs2}ms`);

// ── 5. 导出（未签）────────────────────────────────────────────────────────
const export1 = await call(`/api/v1/app/documents/${docId}/export?format=pdf`, { token });
facts.export = {
  code: export1.code,
  expiresIn: export1.data?.expiresIn,
  fileName: export1.data?.fileName,
  url: export1.data?.url,
};
const unsigned = await download(export1.data.url);
writeFileSync(join(OUT, 'doc-unsigned.pdf'), unsigned.buf);
facts.unsignedPdfBytes = unsigned.buf.length;
facts.unsignedHash = await sha256(unsigned.buf);
log(`未签 PDF ${facts.unsignedPdfBytes} 字节 sha256=${facts.unsignedHash.slice(0, 16)}…`);

const badFormat = await call(`/api/v1/app/documents/${docId}/export?format=docx`, { token });
facts.export.badFormatCode = badFormat.code;

// ── 6. 签名库 + 叠章 ───────────────────────────────────────────────────────
const sigPng = makeSignaturePng(240, 120);
writeFileSync(join(OUT, 'signature-sample.png'), sigPng);
facts.signatureId = await addSignature(sigPng, 'seg4-probe.png');
if (!facts.signatureId) {
  // 库里 5 个都被文书引用（删不掉）时，无法新增；直接复用库里已有的一个（等同用户选已有签名）
  const lib = await call('/api/v1/app/signatures', { token });
  facts.signatureId = lib.data?.[0]?.id ?? null;
  facts.signatureReused = facts.signatureId;
  log(`新增签名失败（code=${facts.signatureAddCode}），复用已有 signatureId=${facts.signatureId}`);
}
log(`本次叠章用 signatureId=${facts.signatureId}`);

const sign = await call(`/api/v1/app/documents/${docId}/sign`, {
  method: 'POST',
  token,
  body: {
    signatureId: facts.signatureId,
    pageNo: 1,
    posX: 380,
    posY: 700,
    width: 120,
    height: 60,
    basePageW: facts.preview.pageW,
    basePageH: facts.preview.pageH,
  },
});
facts.sign = { code: sign.code, documentHash: sign.data?.documentHash };
if (sign.code === 200) {
  const signed = await download(sign.data.signedPdfUrl);
  writeFileSync(join(OUT, 'doc-signed.pdf'), signed.buf);
  facts.signedHash = await sha256(signed.buf);
  log(`带签 PDF ${signed.buf.length} 字节 sha256=${facts.signedHash.slice(0, 16)}…`);
} else {
  log(`叠章失败：${JSON.stringify(sign)}`);
}

const detail2 = await call(`/api/v1/app/documents/${docId}`, { token });
facts.statusAfterSign = detail2.data?.status;

// 叠章后导出应指向带签名那份
const export2 = await call(`/api/v1/app/documents/${docId}/export?format=pdf`, { token });
const exported2 = await download(export2.data.url);
writeFileSync(join(OUT, 'doc-export-after-sign.pdf'), exported2.buf);
facts.exportAfterSignHash = await sha256(exported2.buf);

// ── 7. 列表：翻页 + 类型筛选（4-A7 第 7/8 项）──────────────────────────────
const page1 = await call('/api/v1/app/documents?pageNum=1&pageSize=5', { token });
const page2 = await call('/api/v1/app/documents?pageNum=2&pageSize=5', { token });
const ids1 = (page1.data?.list ?? []).map((d) => String(d.id));
const ids2 = (page2.data?.list ?? []).map((d) => String(d.id));
facts.list = {
  total: page1.data?.total,
  page1: ids1,
  page2: ids2,
  disjoint: ids1.filter((id) => ids2.includes(id)).length === 0,
  hasMore: page1.data?.hasMore,
};
const filtered = await call('/api/v1/app/documents?docType=' + encodeURIComponent('诉讼文书') + '&pageSize=50', { token });
facts.filter = {
  count: (filtered.data?.list ?? []).length,
  docTypes: [...new Set((filtered.data?.list ?? []).map((d) => d.docType))],
  allMatch: (filtered.data?.list ?? []).every((d) => d.docType === '诉讼文书'),
};
log(`列表 total=${facts.list.total} 第1页=${ids1.length} 第2页=${ids2.length} 无交集=${facts.list.disjoint}`);
log(`类型筛选「诉讼文书」命中 ${facts.filter.count} 条，docType=${JSON.stringify(facts.filter.docTypes)}`);

// ── 8. 导出 URL 61 秒过期（4-A7 第 3 项）──────────────────────────────────
if (!SKIP_EXPIRY) {
  log('等待 62 秒验证导出 URL 过期…');
  await new Promise((r) => setTimeout(r, 62_000));
  const expired = await fetch(facts.export.url);
  facts.export.expiredStatus = expired.status;
  log(`61 秒后同一 URL 状态 = ${expired.status}`);
}

// ── 9. 4-A5 位置量测：另建一份文书，用**实心矩形**签名图盖章 ─────────────────
// 为什么要另建一份、为什么用实心图：4-A5 量的是「落点与预期坐标的偏差」。
// 手写笔迹图四周是透明的，差异包围盒只覆盖笔画，量出来会比图片矩形小一圈（会被误判成偏移）。
// 实心图盖章后，差异包围盒 == 图片矩形，才能和请求的 (posX,posY,width,height) 直接比。
log('4-A5：另建一份文书用实心签名图盖章，用于精确量测落点…');
const solidPng = makeSolidPng(240, 120);
writeFileSync(join(OUT, 'signature-solid.png'), solidPng);
const saved2 = await call('/api/v1/app/documents', {
  method: 'POST',
  token,
  body: { templateId: lawsuit.id, title: '验收探针文书（位置量测）', formData: JSON.stringify(form) },
});
const a5DocId = saved2.data;
await call(`/api/v1/app/documents/${a5DocId}/generate`, { method: 'POST', token });
const a5Detail = await waitDone(a5DocId);
facts.a5 = { docId: a5DocId, status: a5Detail.status };
if (a5Detail.status === 'generated') {
  const exBefore = await call(`/api/v1/app/documents/${a5DocId}/export?format=pdf`, { token });
  const beforeBuf = await download(exBefore.data.url);
  writeFileSync(join(OUT, 'a5-before.pdf'), beforeBuf.buf);
  const a5Preview = await call(`/api/v1/app/documents/${a5DocId}/preview`, { token });
  // 实心图没能入库（库满且都被引用）时，退回复用本次叠章用的那个签名——量测改用「解析式笔迹包围盒」比
  const sigSolidId = (await addSignature(solidPng, 'seg4-solid.png')) ?? facts.signatureId;
  const sig2 = { data: sigSolidId };
  const a5Sign = await call(`/api/v1/app/documents/${a5DocId}/sign`, {
    method: 'POST',
    token,
    body: {
      signatureId: sig2.data,
      pageNo: 1,
      posX: 380,
      posY: 700,
      width: 120,
      height: 60,
      basePageW: a5Preview.data[0].widthPt,
      basePageH: a5Preview.data[0].heightPt,
    },
  });
  facts.a5.signCode = a5Sign.code;
  facts.a5.expected = { x: 380, y: 700, w: 120, h: 60 };
  // 量测取「笔迹（不透明像素）」的包围盒，所以预期值也按 png 的笔迹范围解析算出来：
  // makeSignaturePng 的笔画 x∈[20,220]、y∈[25,95]（240×120 的画布，含 ±1px 描边），
  // 缩放到 120×60pt 的放置框后即可换算成预期包围盒。
  const art = { x0: 20, x1: 220, y0: 25, y1: 95, w: 240, h: 120 };
  facts.a5.expectedInk = {
    x: 380 + (art.x0 * 120) / art.w,
    y: 700 + (art.y0 * 60) / art.h,
    w: ((art.x1 - art.x0) * 120) / art.w,
    h: ((art.y1 - art.y0) * 60) / art.h,
  };
  if (a5Sign.code === 200) {
    const signedBuf = await download(a5Sign.data.signedPdfUrl);
    writeFileSync(join(OUT, 'a5-after.pdf'), signedBuf.buf);
    facts.a5.signedBytes = signedBuf.buf.length;
  }
  log(`4-A5 量测文书 doc=${a5DocId} 盖章 code=${a5Sign.code}，预期落点 (380,700,120,60)`);
}

// ── 10. 4-A4 模板热更新：H1==H2、H3!=H1、旧行 inactive / 新行 active ─────────
// 注意：这一步会**真的升一版模板**（运营改版的真实链路），会在库里留新版本行。
// 验收环境可重复跑（每次都升一版），上线前随测试数据清理。
log('4-A4：模板热更新验证（会升一版模板）…');
const ruoyiLogin = await fetch('http://127.0.0.1:8080/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'admin123', code: '', uuid: '' }),
}).then((r) => r.json());
const adminToken = ruoyiLogin.token;
async function adminCall(path, opts = {}) {
  const headers = { Authorization: `Bearer ${adminToken}` };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(API + path, {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  });
  return res.json();
}
const tplDetail = await adminCall(`/api/v1/admin/doc-templates/${lawsuit.id}`);
facts.a4 = { templateIdBefore: lawsuit.id, versionBefore: tplDetail.data.version };
const a4DocSave = await call('/api/v1/app/documents', {
  method: 'POST',
  token,
  body: { templateId: lawsuit.id, title: '验收探针文书（4-A4）', formData: JSON.stringify(form) },
});
const a4DocId = a4DocSave.data;
facts.a4.docId = a4DocId;
await call(`/api/v1/app/documents/${a4DocId}/generate`, { method: 'POST', token });
const a4Detail1 = await waitDone(a4DocId);
facts.a4.statusBefore = a4Detail1.status;
const a4Export1 = await call(`/api/v1/app/documents/${a4DocId}/export?format=pdf`, { token });
const a4Hash1 = await sha256((await download(a4Export1.data.url)).buf);
facts.a4.h1 = a4Hash1;

const oldHtml = tplDetail.data.templateHtml;
const sizeMatch = /h1\s*\{[^}]*?font-size:\s*(\d+)pt/.exec(oldHtml);
const oldSize = sizeMatch ? Number(sizeMatch[1]) : 22;
const newHtml = oldHtml.replace(`font-size: ${oldSize}pt`, `font-size: ${oldSize - 2}pt`);
const savedTpl = await adminCall(`/api/v1/admin/doc-templates/${lawsuit.id}`, {
  method: 'PUT',
  body: {
    code: tplDetail.data.code,
    name: tplDetail.data.name,
    category: tplDetail.data.category,
    templateHtml: newHtml,
    fieldSchema: tplDetail.data.fieldSchema,
    sortOrder: tplDetail.data.sortOrder,
    status: 'active',
  },
});
facts.a4.saveCode = savedTpl.code;
facts.a4.newTemplateId = savedTpl.data;
const newDetail = await adminCall(`/api/v1/admin/doc-templates/${savedTpl.data}`);
facts.a4.versionAfter = newDetail.data.version;
facts.a4.newStatus = newDetail.data.status;
const oldDetail = await adminCall(`/api/v1/admin/doc-templates/${lawsuit.id}`);
facts.a4.oldStatus = oldDetail.data.status;

const a4Export2 = await call(`/api/v1/app/documents/${a4DocId}/export?format=pdf`, { token });
const a4Hash2 = await sha256((await download(a4Export2.data.url)).buf);
facts.a4.h2 = a4Hash2;

await call(`/api/v1/app/documents/${a4DocId}/generate`, { method: 'POST', token });
const a4Detail2 = await waitDone(a4DocId);
facts.a4.statusAfter = a4Detail2.status;
facts.a4.templateVersionAfter = a4Detail2.templateVersion;
const a4Export3 = await call(`/api/v1/app/documents/${a4DocId}/export?format=pdf`, { token });
const a4Hash3 = await sha256((await download(a4Export3.data.url)).buf);
facts.a4.h3 = a4Hash3;
log(`4-A4 H1=${a4Hash1.slice(0, 12)}… H2=${a4Hash2.slice(0, 12)}… H3=${a4Hash3.slice(0, 12)}… 版本 ${facts.a4.versionBefore}→${facts.a4.versionAfter}`);
writeFileSync(join(OUT, 'probe.log'), logLines.join('\n'), 'utf8');
console.log('__JSON__' + JSON.stringify(facts));

/**
 * 生成一张**全实心**黑色矩形 PNG（4-A5 位置量测的「标尺」）。
 *
 * @param width 宽（像素）
 * @param height 高（像素）
 * @returns PNG 字节
 */
function makeSolidPng(width, height) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    const rowStart = y * (width * 4 + 1);
    raw[rowStart] = 0;
    for (let x = 0; x < width; x++) {
      const i = rowStart + 1 + x * 4;
      raw[i] = 0;
      raw[i + 1] = 0;
      raw[i + 2] = 0;
      raw[i + 3] = 255;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const typeBuf = Buffer.from(type, 'ascii');
    const crcBuf = Buffer.alloc(4);
    crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])) >>> 0);
    return Buffer.concat([len, typeBuf, data, crcBuf]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
