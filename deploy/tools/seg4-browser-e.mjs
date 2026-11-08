/**
 * E 组六页的浏览器巡检（段 4，`deploy/tools/` 验收工具，非业务代码）。
 *
 * 职责：用 CDP 驱动真 Chrome（390×844）逐页打开文书中心六页，采集「页面是否真的渲染出内容」的结构化证据：
 *      模板卡片数 / 分类、表单字段数（schema 驱动）、预览页真实 PDF 图、我的文书列表条数与筛选、签章库条数与手写板。
 * 调用方：`docs/acceptance/seg4/E-group.md` 的证据来源；接在 `seg4-browser-a7.mjs` 之后跑（复用同一个 Chrome 与登录态）。
 * 数据来源：`docs/acceptance/seg4/_auto/facts.json`（docId / templateId）。
 * 注意：
 *   1. 只读巡检：不提交表单、不删数据、不盖章（盖章在 4-A5 的探针里做）。
 *   2. 依赖 8090 已启动且已登录（跑 `seg4-browser-a7.mjs` 会完成登录）。
 *
 * 用法：node deploy/tools/seg4-browser-e.mjs
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const DEBUG_PORT = 9222;
const WEB = 'http://localhost:8090';
const FACTS = resolve('docs/acceptance/seg4/_auto/facts.json');
const OUT_JSON = resolve('docs/acceptance/seg4/E-group-browser.json');
const SHOT_DIR = resolve('docs/acceptance/seg4');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 读探针事实（去掉 BOM）。 */
function readFacts() {
  if (!existsSync(FACTS)) throw new Error(`找不到 ${FACTS}`);
  return JSON.parse(readFileSync(FACTS, 'utf8').replace(/^\uFEFF/, ''));
}

/** 极简 CDP 客户端。 */
class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : '');
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error)));
        else resolve(msg.result);
      }
    });
  }

  send(method, params = {}) {
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
}

/** 页面求值。 */
async function evaluate(cdp, expression) {
  const res = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (res.exceptionDetails) throw new Error('页面脚本异常: ' + JSON.stringify(res.exceptionDetails));
  return res.result?.value ?? null;
}

/** 截图。 */
async function shot(cdp, name) {
  const res = await cdp.send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(join(SHOT_DIR, name), Buffer.from(res.data, 'base64'));
  return name;
}

/** 打开一个路由并等渲染。 */
async function open(cdp, path, waitMs = 7000) {
  await cdp.send('Page.navigate', { url: WEB + path });
  await sleep(waitMs);
}

/** 取 App 端 token（E 组巡检要拿「当前 active 模板 id」，facts.json 里的可能已被 4-A4 升版作废）。 */
async function apiToken() {
  const r = await fetch('http://127.0.0.1:8081/api/v1/pub/auth/login/password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: '13800000001', password: '123456', deviceId: 'seg4-e' }),
  }).then((x) => x.json());
  return r.data.accessToken;
}

/** 调接口。 */
async function api(path, token, method = 'GET') {
  const res = await fetch('http://127.0.0.1:8081' + path, { method, headers: { Authorization: 'Bearer ' + token } });
  return res.json();
}

/** 签章库满时删一个未被引用的，腾出手写板名额（被引用的会 406006，自动跳过）。 */
async function freeSealSlot(token) {
  const lib = await api('/api/v1/app/signatures', token);
  for (const item of lib.data ?? []) {
    const del = await api('/api/v1/app/signatures/' + item.id, token, 'DELETE');
    if (del.code === 200) return item.id;
  }
  return null;
}

const main = async () => {
  const facts = readFacts();
  const token = await apiToken();
  const tplList = await api('/api/v1/app/doc-templates', token);
  facts.lawsuitActive = (tplList.data ?? []).find((x) => x.code === 'LAWSUIT-001') ?? null;
  if (facts.lawsuitActive) facts.templateId = facts.lawsuitActive.id;
  report0: {}
  mkdirSync(SHOT_DIR, { recursive: true });
  const report = { docId: facts.docId, templateId: facts.templateId, pages: {}, shots: [] };

  const version = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/version`)).json();
  const target = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/new?about:blank`, { method: 'PUT' })).json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => {
    ws.addEventListener('open', res, { once: true });
    ws.addEventListener('error', rej, { once: true });
  });
  const cdp = new Cdp(ws);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  report.browser = version.Browser;

  // ── E1 模板列表 ────────────────────────────────────────────────────────
  await open(cdp, '/doc/templates');
  report.pages.templates = await evaluate(
    cdp,
    `(() => {
      const text = document.body.innerText;
      const cards = Array.from(document.querySelectorAll('[data-testid]')).filter((n) =>
        /^docTemplates?\\.(card|item)/.test(n.getAttribute('data-testid') || ''));
      return {
        body: text.slice(0, 300),
        cardCount: cards.length,
        categories: ['诉讼文书','合同文书','劳动文书','跨境电商'].filter((c) => text.includes(c)),
        empty: text.includes('暂无可用模板'),
      };
    })()`,
  );
  report.shots.push(await shot(cdp, 'e1-templates.png'));

  // ── E2 起诉状表单（schema 驱动）────────────────────────────────────────
  await open(cdp, `/doc/lawsuit?templateId=${facts.templateId}`, 9000);
  report.pages.lawsuit = await evaluate(
    cdp,
    `(() => {
      const text = document.body.innerText;
      const inputs = Array.from(document.querySelectorAll('input, textarea'));
      return {
        body: text.slice(0, 300),
        inputCount: inputs.length,
        labels: ['受理法院','原告','被告','诉讼请求','事实与理由'].filter((l) => text.includes(l)),
        hasGenerate: /立即生成/.test(text),
      };
    })()`,
  );
  report.shots.push(await shot(cdp, 'e2-lawsuit-form.png'));

  // ── E3 其它文书表单（同一条渲染管线，换成证据清单模板）─────────────────
  const evidenceTpl = (facts.templates ?? []).find((t) => t.code === 'EVIDENCE-001');
  if (evidenceTpl) {
    await open(cdp, `/doc/form?templateId=${evidenceTpl.id}`, 9000);
    report.pages.form = await evaluate(
      cdp,
      `(() => {
        const text = document.body.innerText;
        return {
          body: text.slice(0, 260),
          inputCount: document.querySelectorAll('input, textarea').length,
          hasGenerate: /立即生成/.test(text),
        };
      })()`,
    );
    report.shots.push(await shot(cdp, 'e3-other-form.png'));
  }

  // ── E4 预览（真实 PDF → PNG）──────────────────────────────────────────
  await open(cdp, `/doc/preview?id=${facts.docId}`, 9000);
  report.pages.preview = await evaluate(
    cdp,
    `(() => {
      const text = document.body.innerText;
      const pages = document.querySelector('[data-testid="docPreview.preview.list"]');
      const imgs = pages ? Array.from(pages.querySelectorAll('img')) : [];
      return {
        body: text.slice(0, 260),
        pageImages: imgs.length,
        imgLoaded: imgs.filter((i) => i.complete && i.naturalWidth > 0).map((i) => i.naturalWidth + 'x' + i.naturalHeight),
        hasExport: !!document.querySelector('[data-testid="docPreview.action.export"]'),
        hasSeal: !!document.querySelector('[data-testid="docPreview.action.seal"]'),
      };
    })()`,
  );
  report.shots.push(await shot(cdp, 'e4-preview.png'));

  // ── E5 我的文书（列表 / 筛选 / 翻页）──────────────────────────────────
  await open(cdp, '/doc/mine', 8000);
  report.pages.mine = await evaluate(
    cdp,
    `(async () => {
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const items = () => document.querySelectorAll('[data-testid^="mine-item-"]').length;
      const before = items();
      const empty = !!document.querySelector('[data-testid="mine-empty"]');
      const statusChips = Array.from(document.querySelectorAll('[data-testid^="mine-status-"]')).length;
      const typeChips = Array.from(document.querySelectorAll('[data-testid^="mine-type-"]')).length;
      const loadMore = document.querySelector('[data-testid="mine-load-more"]');
      if (loadMore) { loadMore.click(); await wait(4000); }
      return { before, afterLoadMore: items(), empty, statusChips, typeChips, hasLoadMore: !!loadMore };
    })()`,
  );
  report.shots.push(await shot(cdp, 'e5-mine.png'));

  // 签章库满（5 个）时手写板入口是置灰的，腾一个名额再验手写板能否打开
  report.freedSealId = await freeSealSlot(token);

  // ── E6 签章库 + 手写板 ────────────────────────────────────────────────
  await open(cdp, '/doc/seals', 8000);
  report.pages.seals = await evaluate(
    cdp,
    `(async () => {
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const text = document.body.innerText;
      const images = Array.from(document.querySelectorAll('img')).filter((i) => i.complete && i.naturalWidth > 0);
      const byText = (t) => Array.from(document.querySelectorAll('[role="button"]')).find((n) => (n.textContent || '').includes(t));
      const add = byText('手写');
      if (add) { add.click(); await wait(1500); }
      return {
        body: text.slice(0, 260),
        signatureImages: images.length,
        hasPad: !!document.querySelector('[data-testid*="seal"]'),
        padOpened: /清空|撤销|确认/.test(document.body.innerText),
      };
    })()`,
  );
  report.shots.push(await shot(cdp, 'e6-seals.png'));

  writeFileSync(OUT_JSON, JSON.stringify(report, null, 2), 'utf8');
  console.log(JSON.stringify({ ok: true, result: OUT_JSON, pages: Object.keys(report.pages) }, null, 2));
  ws.close();
  process.exit(0);
};

main().catch((error) => {
  console.error('[e-group-browser] 失败:', error.message);
  process.exit(1);
});
