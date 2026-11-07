/**
 * 4-A7 的浏览器证据采集（段 4，`deploy/tools/` 里的验收工具，非业务代码）。
 *
 * 职责：用 CDP 驱动真 Chrome（390×844，headless），在真实前端（Expo Web 8090）与真实后端
 *       （faze-api 8081）上，把 `4-A7` 里「只能浏览器验」的四项跑一遍并写证据：
 *         ② 重新下载：点「导出 PDF」→ 文件真的落盘且文件头是 %PDF
 *         ④ 分享降级：`navigator.share` 不存在时按钮置灰 + 文字说明
 *         ⑥ 打印：`expo-print` 走 `window.print()`（用桩记录是否被调用）
 *         ⑤ 存相册：登记为段 8 挂账（按钮置灰 + 说明）
 *       ①/⑦/⑧（列表首条、翻页无交集、按类型筛选）由 `seg4-probe.mjs` 在接口层验。
 * 调用方：`deploy/verify-seg4.ps1` 读它产出的 `docs/acceptance/seg4/4-A7-browser.json`。
 * 数据来源：`docs/acceptance/seg4/_auto/facts.json`（拿 docId）。
 * 注意：
 *   1. 复用本机 `%LOCALAPPDATA%\faze-cdp-profile` 里的登录态（Expo Web 的 session 存在 localStorage）。
 *      没有登录态时页面会落到登录页，脚本会以 error 记下并退出 1（此时先手工登录一次再跑）。
 *   2. `navigator.share` 与 `window.print` 都用 `Page.addScriptToEvaluateOnNewDocument` 注入桩：
 *      分享桩是「删掉 navigator.share」模拟不支持的浏览器；打印桩只记录调用，不真开打印对话框。
 *   3. 只写证据文件，不改任何业务数据。
 *
 * 用法：node deploy/tools/seg4-browser-a7.mjs [docId]
 */

import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const DEBUG_PORT = 9222;
const WEB = 'http://localhost:8090';
const OUT_DIR = resolve('docs/acceptance/seg4/_auto/browser-downloads');
const SHOT_DIR = resolve('docs/acceptance/seg4');
const RESULT_PATH = join(SHOT_DIR, '4-A7-browser.json');
const FACTS = resolve('docs/acceptance/seg4/_auto/facts.json');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 取探针产出的 docId。 */
function resolveDocId() {
  if (process.argv[2]) return process.argv[2];
  if (!existsSync(FACTS)) throw new Error(`找不到 ${FACTS}，先跑 deploy/tools/seg4-probe.mjs`);
  // facts.json 是 PowerShell Set-Content -Encoding UTF8 写的，带 BOM，先剥掉再解析
  return JSON.parse(readFileSync(FACTS, 'utf8').replace(/^\uFEFF/, '')).docId;
}

/** 找本机 Chrome。 */
function findChrome() {
  const candidates = [
    join(process.env.LOCALAPPDATA ?? '', 'Google/Chrome/Application/chrome.exe'),
    join(process.env.PROGRAMFILES ?? '', 'Google/Chrome/Application/chrome.exe'),
    join(process.env['PROGRAMFILES(X86)'] ?? '', 'Google/Chrome/Application/chrome.exe'),
  ];
  const hit = candidates.find((p) => p && existsSync(p));
  if (!hit) throw new Error('找不到 Chrome');
  return hit;
}

/** 等 CDP 端口起来。 */
async function cdpVersion(timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/version`);
      if (r.ok) return await r.json();
    } catch {
      /* 还没起来 */
    }
    await sleep(250);
  }
  return null;
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

/** 在页面里求值。 */
async function evaluate(cdp, expression) {
  const res = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (res.exceptionDetails) throw new Error('页面脚本异常: ' + JSON.stringify(res.exceptionDetails));
  return res.result?.value ?? null;
}

/** 截图存盘。 */
async function shot(cdp, name) {
  const res = await cdp.send('Page.captureScreenshot', { format: 'png' });
  const path = join(SHOT_DIR, name);
  writeFileSync(path, Buffer.from(res.data, 'base64'));
  return name;
}


/**
 * 在页面里用测试账号登录（Expo Web 的登录页）。
 *
 * <p>为什么需要：CDP 复用的浏览器 profile 里的 session 会过期（几小时后必过期），
 * 过期时页面显示「登录凭证无效」，后续断言会全部假失败。每次跑先确保登录态。</p>
 *
 * @param cdp CDP 客户端
 * @returns 登录结果（含是否成功）
 */
async function loginWithTestAccount(cdp) {
  await cdp.send('Page.navigate', { url: `${WEB}/login` });
  await sleep(7000);
  const res = await evaluate(
    cdp,
    `(async () => {
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const inputs = Array.from(document.querySelectorAll('input'));
      if (inputs.length < 2) {
        return { skipped: true, body: document.body.innerText.slice(0, 120) };
      }
      const setVal = (el, v) => {
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(el, v);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      };
      setVal(inputs[0], '13800000001');
      setVal(inputs[1], '123456');
      await wait(400);
      const btn = Array.from(document.querySelectorAll('[role="button"]')).find((n) => /登录|登 录/.test(n.textContent || ''));
      if (!btn) return { error: '没有登录按钮', body: document.body.innerText.slice(0, 150) };
      btn.click();
      await wait(7000);
      return { url: location.href, body: document.body.innerText.slice(0, 150) };
    })()`,
  );
  return res;
}

const main = async () => {
  const docId = resolveDocId();
  rmSync(OUT_DIR, { recursive: true, force: true });
  mkdirSync(OUT_DIR, { recursive: true });
  mkdirSync(SHOT_DIR, { recursive: true });
  const report = { docId, startedAt: new Date().toISOString(), shots: [], errors: [] };

  let version = await cdpVersion(1200);
  if (version === null) {
    const profile = join(process.env.LOCALAPPDATA ?? '', 'faze-cdp-profile');
    mkdirSync(profile, { recursive: true });
    spawn(
      findChrome(),
      [
        '--headless=new',
        `--remote-debugging-port=${DEBUG_PORT}`,
        `--user-data-dir=${profile}`,
        '--no-first-run',
        '--no-default-browser-check',
        '--disable-gpu',
        '--hide-scrollbars',
        '--disable-extensions',
        'about:blank',
      ],
      { detached: true, stdio: 'ignore' },
    ).unref();
    version = await cdpVersion(30000);
    if (!version) throw new Error('Chrome 没起来（9222）');
  }

  const target = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/new?about:blank`, { method: 'PUT' })).json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });
  const cdp = new Cdp(ws);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await cdp.send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: OUT_DIR });

  // ── 0. 先确保登录态（profile 里的 session 会过期）────────────────────────
  report.login = await loginWithTestAccount(cdp);
  if (!report.login || !(report.login.url || report.login.skipped)) {
    report.errors.push('登录态获取失败：' + JSON.stringify(report.login));
  }

  // ── ① 我的文书列表 ────────────────────────────────────────────────────
  await cdp.send('Page.navigate', { url: `${WEB}/doc/mine` });
  await sleep(8000);
  const mine = await evaluate(
    cdp,
    `(() => {
      const items = Array.from(document.querySelectorAll('[data-testid^="mine-item-"]'));
      return {
        count: items.length,
        firstText: items[0] ? (items[0].textContent || '').slice(0, 60) : null,
        hasLoadMore: !!document.querySelector('[data-testid="mine-load-more"]'),
        body: document.body.innerText.slice(0, 200),
      };
    })()`,
  );
  report.mine = mine;
  report.mineListVisible = mine.count > 0;
  if (!report.mineListVisible) report.errors.push('我的文书列表没有条目：' + JSON.stringify(mine.body));
  report.shots.push(await shot(cdp, 'a7-browser-mine.png'));

  // ── ② 下载 PDF（导出面板）──────────────────────────────────────────────
  await cdp.send('Page.navigate', { url: `${WEB}/doc/preview?id=${docId}` });
  await sleep(9000);
  const downloadClick = await evaluate(
    cdp,
    `(async () => {
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const byId = (id) => document.querySelector('[data-testid="' + id + '"]');
      const open = byId('docPreview.action.export');
      if (!open) return { error: '没有导出按钮', body: document.body.innerText.slice(0, 200) };
      open.click();
      await wait(700);
      const row = byId('docPreview.export.download');
      if (!row) return { error: '导出面板里没有下载行' };
      row.click();
      await wait(5000);
      return { clicked: true, toast: document.body.innerText.slice(-120).replace(/\\n/g, ' | ') };
    })()`,
  );
  report.download = downloadClick;
  await sleep(2500);
  const files = readdirSync(OUT_DIR).filter((n) => !n.endsWith('.crdownload'));
  report.downloadedFiles = files.map((name) => {
    const buf = readFileSync(join(OUT_DIR, name));
    return { name, bytes: buf.length, head: buf.subarray(0, 5).toString('latin1') };
  });
  report.downloadSavedPdf = report.downloadedFiles.some((f) => f.head === '%PDF-');
  if (!report.downloadSavedPdf) report.errors.push('没有下载到 PDF：' + JSON.stringify(report.downloadedFiles));
  report.shots.push(await shot(cdp, 'a7-browser-download.png'));

  // ── ④⑥⑤ 分享降级 / 打印 / 存相册挂账（注入桩后重进页面）──────────────
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `
      // 模拟「不支持系统分享」的浏览器
      try { Object.defineProperty(navigator, 'share', { value: undefined, configurable: true }); } catch (e) {}
      // 打印桩：只记录被调用，不真弹对话框
      window.__fazePrintCalled = 0;
      window.print = function () { window.__fazePrintCalled += 1; };
    `,
  });
  await cdp.send('Page.navigate', { url: `${WEB}/doc/preview?id=${docId}` });
  await sleep(9000);
  const panel = await evaluate(
    cdp,
    `(async () => {
      const wait = (ms) => new Promise((r) => setTimeout(r, ms));
      const byId = (id) => document.querySelector('[data-testid="' + id + '"]');
      const isDisabled = (el) => {
        if (!el) return null;
        if (el.getAttribute('aria-disabled') === 'true') return true;
        const cs = getComputedStyle(el);
        return cs.pointerEvents === 'none' || cs.opacity !== '' && Number(cs.opacity) < 0.6;
      };
      const open = byId('docPreview.action.export');
      if (!open) return { error: '没有导出按钮' };
      open.click();
      await wait(700);
      const share = byId('docPreview.export.share');
      const print = byId('docPreview.export.print');
      const gallery = byId('docPreview.export.gallery');
      const text = (el) => (el ? el.textContent || '' : '');
      const before = { share: text(share), print: text(print), gallery: text(gallery) };
      const shareDisabled = isDisabled(share);
      if (print) print.click();
      await wait(1200);
      return {
        before,
        shareDisabled,
        galleryDisabled: isDisabled(gallery),
        printCalled: window.__fazePrintCalled || 0,
        sheetText: (byId('docPreview.export.sheet') || {}).textContent || '',
      };
    })()`,
  );
  report.panel = panel;
  report.shareDegraded = Boolean(panel && panel.shareDisabled === true && /不支持|不可用|无法/.test(panel.before.share));
  report.printTriggered = Boolean(panel && panel.printCalled > 0);
  report.mediaLibraryDeferred = Boolean(
    panel && panel.galleryDisabled === true && /段 ?8|暂未|不支持|不可用/.test(panel.before.gallery + (panel.sheetText || '')),
  );
  if (!report.shareDegraded) report.errors.push('分享降级没验到：' + JSON.stringify(panel && panel.before));
  if (!report.printTriggered) report.errors.push('打印没有触发 window.print()');
  if (!report.mediaLibraryDeferred) report.errors.push('存相册没有置灰/说明（段 8 挂账）');
  report.shots.push(await shot(cdp, 'a7-browser-export-panel.png'));

  report.finishedAt = new Date().toISOString();
  writeFileSync(RESULT_PATH, JSON.stringify(report, null, 2), 'utf8');
  console.log(JSON.stringify({ ok: report.errors.length === 0, result: RESULT_PATH, errors: report.errors }, null, 2));
  ws.close();
  process.exit(report.errors.length === 0 ? 0 : 1);
};

main().catch((error) => {
  console.error('[a7-browser] 失败:', error.message);
  process.exit(1);
});
