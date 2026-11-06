#!/usr/bin/env node
/**
 * 职责：用 Chrome DevTools Protocol（CDP）打开真实 Chrome 并按指定尺寸截图，
 *       供第 3 段 F/H 组「真机尺寸验收」使用（负责人 2026-11 明确要求：
 *       「用 CDP 打开谷歌浏览器自己去截屏，不要只截手机尺寸」）。
 *
 * 调用方：验收时由 Lead 手动执行，例：
 *   node scripts/cdp-shot.mjs --url http://localhost/ --out tmp/seg3/shot.png --width 1440 --height 900
 *   node scripts/cdp-shot.mjs --url http://localhost/ --out tmp/seg3/a27.png --width 1440 --height 900 \
 *        --eval "document.querySelector('#app') && 1" --wait 3000
 *   node scripts/cdp-shot.mjs --url http://localhost/ --out tmp/seg3/mobile.png --width 390 --height 844 --mobile
 *
 * 数据来源：本机 Chrome（默认用户级安装路径，可用 CHROME_PATH 覆盖）+ CDP 的 HTTP JSON 接口与 WebSocket。
 * 注意：
 *   1. **不依赖 puppeteer/playwright**：Node 22+ 自带全局 WebSocket，直连 CDP 即可，
 *      避免为一次性验收引入大依赖（AGENTS §12）。
 *   2. 用独立 `--user-data-dir`（临时目录），**不碰用户自己的 Chrome 配置**；
 *      脚本退出不删该目录，方便复用时保留登录态（下次会用同一个 profile 复用）。
 *   3. 默认 `--headless=new`（真实 Chrome 渲染管线，不是 PhantomJS 那种假渲染）。
 *      要看得见的窗口时加 `--headed`。
 *   4. `--eval` 在导航完成后执行，用于注入登录态或触发页面内操作；其返回值会被打印，
 *      方便断言（例如页面是否出现某个 testID）。
 *   5. 退出码：0 = 截图成功；1 = 失败（Chrome 没起来 / 导航超时 / 截图失败）。
 *
 * 为什么需要它：`3-A7`/F 组的验收要的是「真实浏览器按真实尺寸渲染」的像素证据，
 * 设计稿 HTML 不能代替；而本机没有 adb 设备，手机尺寸只能靠 CDP 模拟视口。
 */

import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const DEBUG_PORT = 9222;

/** 解析 `--key value` / `--flag` 形式的参数。 */
function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (!a.startsWith('--')) { out._.push(a); continue; }
    const key = a.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) { out[key] = true; } else { out[key] = next; i += 1; }
  }
  return out;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const args = parseArgs(process.argv.slice(2));
if (!args.url || !args.out) {
  console.error('用法：node scripts/cdp-shot.mjs --url <URL> --out <PNG路径> [--width 1440] [--height 900] [--wait 2000] [--mobile] [--headed] [--full] [--eval "<js>"]');
  process.exit(1);
}

const width = Number(args.width ?? 1440);
const height = Number(args.height ?? 900);
const waitMs = Number(args.wait ?? 1500);
const mobile = Boolean(args.mobile);
const full = Boolean(args.full);

/** 找到 Chrome 可执行文件：环境变量优先，其次用户级/系统级安装路径。 */
function findChrome() {
  if (process.env.CHROME_PATH && existsSync(process.env.CHROME_PATH)) return process.env.CHROME_PATH;
  const cands = [
    join(process.env.LOCALAPPDATA ?? '', 'Google/Chrome/Application/chrome.exe'),
    join(process.env.PROGRAMFILES ?? '', 'Google/Chrome/Application/chrome.exe'),
    join(process.env['PROGRAMFILES(X86)'] ?? '', 'Google/Chrome/Application/chrome.exe'),
    join(process.env.PROGRAMFILES ?? '', 'Microsoft/Edge/Application/msedge.exe'),
    join(process.env['PROGRAMFILES(X86)'] ?? '', 'Microsoft/Edge/Application/msedge.exe'),
  ];
  const hit = cands.find((p) => p && existsSync(p));
  if (!hit) throw new Error('找不到 Chrome/Edge，请用 CHROME_PATH 指定');
  return hit;
}

/** 等 CDP 的 HTTP 端点就绪，返回 browser 的 webSocketDebuggerUrl。 */
async function waitForCdp(timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/version`);
      if (r.ok) return await r.json();
    } catch { /* 还没起来 */ }
    await sleep(250);
  }
  throw new Error(`CDP 端口 ${DEBUG_PORT} 在 ${timeoutMs}ms 内未就绪`);
}

/** 极简 CDP 客户端：按 id 分发响应，按事件名分发事件。 */
class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    this.listeners = new Map();
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : '');
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(`${msg.error.message} (${JSON.stringify(msg.error)})`));
        else resolve(msg.result);
        return;
      }
      if (msg.method) {
        const set = this.listeners.get(msg.method);
        if (set) for (const fn of [...set]) fn(msg.params);
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

  once(method, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`等事件 ${method} 超时`)), timeoutMs);
      const fn = (p) => {
        clearTimeout(timer);
        this.listeners.get(method)?.delete(fn);
        resolve(p);
      };
      if (!this.listeners.has(method)) this.listeners.set(method, new Set());
      this.listeners.get(method).add(fn);
    });
  }
}

async function main() {
  const chrome = findChrome();
  await waitForCdp(1200).catch(async () => {
    // profile 固定下来（不是每次新建）：连续截多张时能复用登录 cookie，
    // 否则每张都要重新登一次。默认放在 LOCALAPPDATA 下的固定目录。
    const profile = args.profile
      ? join(process.cwd(), String(args.profile))
      : join(process.env.LOCALAPPDATA ?? tmpdir(), 'faze-cdp-profile');
    mkdirSync(profile, { recursive: true });
    const spawnArgs = [
      `--remote-debugging-port=${DEBUG_PORT}`,
      `--user-data-dir=${profile}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-gpu',
      '--hide-scrollbars',
      '--disable-extensions',
      'about:blank',
    ];
    if (!args.headed) spawnArgs.unshift('--headless=new');
    console.error(`[cdp] 启动 Chrome：${chrome}`);
    console.error(`[cdp] profile：${profile}`);
    spawn(chrome, spawnArgs, { detached: true, stdio: 'ignore' }).unref();
    await waitForCdp(30000);
    return;
  });

  // 新建 target（新版 Chrome 的 /json/new 要求 PUT）
  const newTarget = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/new?about:blank`, { method: 'PUT' });
  const target = await newTarget.json();
  console.error(`[cdp] target: ${target.id}`);

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', () => reject(new Error('CDP WebSocket 连接失败')), { once: true });
  });
  const cdp = new Cdp(ws);

  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width,
    height,
    deviceScaleFactor: 1,
    mobile,
  });
  if (mobile) {
    // 手机视口下必须有触摸能力，否则媒体查询/滚动行为与真机差得远
    await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  }

  const loaded = cdp.once('Page.loadEventFired').catch(() => null);
  console.error(`[cdp] 导航到 ${args.url}（${width}x${height}${mobile ? ' mobile' : ''}）`);
  await cdp.send('Page.navigate', { url: args.url });
  await loaded;
  await sleep(waitMs);

  // JS 用文件传入（--eval-file），避免 Windows 命令行把引号吃掉。
  // 实测：`--eval "document.querySelector('[data-testid]')"` 经 PowerShell 传参后
  // 引号被剥离，浏览器侧报 `ReferenceError: data is not defined`。
  let evalExpr = args.eval ? String(args.eval) : null;
  if (args['eval-file']) {
    evalExpr = readFileSync(join(process.cwd(), String(args['eval-file'])), 'utf8');
  }
  if (evalExpr) {
    const res = await cdp.send('Runtime.evaluate', { expression: evalExpr, returnByValue: true, awaitPromise: true });
    console.error(`[cdp] eval => ${JSON.stringify(res.result?.value ?? res.result?.description ?? null)}`);
    // eval 里常做 location 跳转/触发异步渲染，需要再等一会儿才截得到目标页
    if (args['post-wait']) await sleep(Number(args['post-wait']));
  }

  const info = await cdp.send('Runtime.evaluate', {
    expression: 'JSON.stringify({title: document.title, url: location.href, h: document.documentElement.scrollHeight})',
    returnByValue: true,
  });
  console.error(`[cdp] 页面状态 => ${info.result.value}`);

  const shotParams = { format: 'png' };
  if (full) {
    const metrics = await cdp.send('Page.getLayoutMetrics');
    const content = metrics.cssContentSize ?? metrics.contentSize;
    shotParams.captureBeyondViewport = true;
    shotParams.clip = { x: 0, y: 0, width: Math.ceil(content.width), height: Math.ceil(content.height), scale: 1 };
  }
  const shot = await cdp.send('Page.captureScreenshot', shotParams);
  writeFileSync(args.out, Buffer.from(shot.data, 'base64'));
  console.error(`[cdp] 截图已写入 ${args.out}`);

  // 关闭本 target（保留浏览器进程，便于连续截多张）
  await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/close/${target.id}`).catch(() => {});
  ws.close();
  process.exit(0);
}

main().catch((e) => {
  console.error(`[cdp] 失败：${e.message}`);
  process.exit(1);
});
