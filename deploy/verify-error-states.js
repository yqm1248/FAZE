/*
 * 1-A9 的错误态与断网重实验收（CDP）。
 *
 * 判据（《第1段工作流》1-A9）：「每页 4 态都有可读的视觉反馈；错误态有重试按钮且点了真能恢复」。
 * 正常态与空态已在 G5 截过，这里补后两态。
 *
 * **做法为什么是「拦 API 请求」而不是「断网」**：
 * 一开始用 `Network.emulateNetworkConditions offline=true` 再 Page.navigate，结果连页面导航本身
 * 都被拦掉，浏览器直接显示自己的离线页（恐龙游戏），APP 根本没加载——那样验的是 Chrome，不是 APP。
 * 改用 `Fetch.enable` 只拦 API 请求（urlPattern 只匹配 api/v1）：页面资源照常加载，只有接口请求失败，
 * 这才是客户端真正会遇到的「接口挂了」场景。
 *
 * 流程：拦截开 → 打开页面 → 截图错误态 → 断言有可读文案 + 有重试按钮
 *       → 关拦截 → 点重试 → 截图并断言回到正常内容。
 *
 * 只跑「有异步数据」的页：纯表单页没有数据请求，不存在错误态（页面注释里已说明这个口径）。
 *
 * 用法：node cdp-error-states.js [outDir]
 */
const fs = require('node:fs');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '..');
const OUT_DIR = process.argv[2] || path.join(REPO_ROOT, 'docs/acceptance/seg1/G5-states/error-states');
const BASE = 'http://127.0.0.1:8090';
const PHONE = '13800000001';
const PASSWORD = '123456';

/** 有异步数据的页 + 恢复后应当出现的关键字（用于判「真的恢复了」）。 */
const PAGES = [
  ['profile', '/profile', '账户余额'],
  ['balance', '/balance', '可用余额'],
  ['account-security', '/account-security', '手机号'],
  ['verify-realname', '/verify-realname', '实名'],
  ['pay-password', '/pay-password', '支付密码'],
  ['agreement', '/agreement', '协议'],
  ['lawyer-join-status', '/lawyer-join-status', '入驻'],
];

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const list = await (await fetch('http://127.0.0.1:9222/json/list')).json();
  let page = list.find((t) => t.type === 'page');
  if (!page) page = await (await fetch('http://127.0.0.1:9222/json/new?about:blank')).json();
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  let blockApi = false;
  let blockedCount = 0;
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const mid = ++id;
      pending.set(mid, { resolve, reject });
      ws.send(JSON.stringify({ id: mid, method, params }));
    });
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      if (m.error) p.reject(new Error(JSON.stringify(m.error)));
      else p.resolve(m.result);
      return;
    }
    // 拦截到 API 请求：按开关决定失败还是放行
    if (m.method === 'Fetch.requestPaused') {
      const rid = m.params.requestId;
      if (blockApi) {
        blockedCount++;
        send('Fetch.failRequest', { requestId: rid, errorReason: 'ConnectionRefused' }).catch(() => {});
      } else {
        send('Fetch.continueRequest', { requestId: rid }).catch(() => {});
      }
    }
  });
  await new Promise((r) => ws.addEventListener('open', r));
  await send('Page.enable');
  await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await send('Fetch.enable', { patterns: [{ urlPattern: '*/api/v1/*', requestStage: 'Request' }] });

  // 先在线登录一次拿 localStorage 里的 token
  blockApi = false;
  await send('Page.navigate', { url: BASE + '/login' });
  await wait(7000);
  await send('Runtime.evaluate', {
    expression: `(function(){
      const inputs = [...document.querySelectorAll('input')];
      const setVal = (el, v) => {
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(el, v);
        el.dispatchEvent(new Event('input', { bubbles: true }));
      };
      if (inputs.length < 2) return 'no inputs';
      setVal(inputs[0], '${PHONE}');
      setVal(inputs[1], '${PASSWORD}');
      const btn = [...document.querySelectorAll('button, [role=button]')].find(b => (b.innerText || '').trim() === '登录');
      if (btn) btn.click();
      return 'submitted';
    })()`,
    returnByValue: true,
  });
  await wait(5000);

  const rows = [];
  for (const [name, route, expectWord] of PAGES) {
    // ── 错误态：拦接口请求后进页面 ──
    blockedCount = 0;
    blockApi = true;
    await send('Page.navigate', { url: BASE + route });
    await wait(7000);
    const errInfo = await send('Runtime.evaluate', {
      expression: `(function(){
        const text = (document.body.innerText || '').replace(/\\s+/g, ' ');
        const retry = [...document.querySelectorAll('button, [role=button]')]
          .filter(b => /重试|再试|重新加载|刷新/.test(b.innerText || ''));
        return JSON.stringify({
          len: text.length,
          text: text.slice(0, 140),
          retryCount: retry.length,
          retryText: retry.length ? (retry[0].innerText || '').trim() : '',
        });
      })()`,
      returnByValue: true,
    });
    const errState = JSON.parse(errInfo.result.value);
    const shot1 = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(OUT_DIR, `${name}-error.png`), Buffer.from(shot1.data, 'base64'));

    // ── 恢复：放行接口 + 点重试 ──
    blockApi = false;
    const clicked = await send('Runtime.evaluate', {
      expression: `(function(){
        const retry = [...document.querySelectorAll('button, [role=button]')]
          .filter(b => /重试|再试|重新加载|刷新/.test(b.innerText || ''));
        if (!retry.length) return 'no-retry';
        retry[0].click();
        return 'clicked';
      })()`,
      returnByValue: true,
    });
    await wait(5000);
    const okInfo = await send('Runtime.evaluate', {
      expression: `(function(){
        const text = (document.body.innerText || '').replace(/\\s+/g, ' ');
        return JSON.stringify({ len: text.length, hasExpect: text.indexOf('${expectWord}') >= 0, text: text.slice(0, 110) });
      })()`,
      returnByValue: true,
    });
    const okState = JSON.parse(okInfo.result.value);
    const shot2 = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(OUT_DIR, `${name}-recovered.png`), Buffer.from(shot2.data, 'base64'));

    const hasReadable = errState.len > 10;
    const hasRetry = errState.retryCount > 0;
    const recovered = okState.hasExpect && okState.len > 10;
    const pass = hasReadable && hasRetry && recovered;
    rows.push({ name, route, blockedRequests: blockedCount, hasReadable, hasRetry, retryText: errState.retryText, recovered, clicked: clicked.result.value, errText: errState.text, okText: okState.text });
    console.log(
      `${pass ? 'PASS' : 'FAIL'}  ${name.padEnd(18)} 拦请求=${blockedCount} 错误文案=${hasReadable} 重试=${hasRetry}(${errState.retryText}) 点后恢复=${recovered}`,
    );
    if (!pass) console.log(`      错误态：${errState.text}\n      恢复后：${okState.text}`);
  }

  const fail = rows.filter((r) => !(r.hasReadable && r.hasRetry && r.recovered));
  console.log(`\n===== 1-A9 错误态/接口失败：${rows.length - fail.length}/${rows.length} 通过 =====`);
  fs.writeFileSync(path.join(OUT_DIR, '_report.json'), JSON.stringify({ runAt: new Date().toISOString(), rows }, null, 2));
  await send('Fetch.disable');
  ws.close();
  process.exit(fail.length === 0 ? 0 : 1);
}

function wait(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

main().catch((e) => {
  console.error(String(e));
  process.exit(2);
});
