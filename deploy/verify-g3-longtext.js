/*
 * G-3 浏览器实测：PC A-08 系统参数页能不能编辑 600+ 字的法务正文（多行）。
 *
 * 背景：修复前表格里是单行 el-input，600~900 字的正文既看不全也打不进换行。
 * 修复后：表格放 60 字摘要 + 「编辑正文（N 字）」，点开是固定高度 textarea 弹窗。
 *
 * 本脚本走真实浏览器（CDP），验证：摘要渲染 → 弹窗多行 → 换行真的保留 →
 * 「确定」进待保存 → 「保存修改」落库 → 再改回原值。
 *
 * 用法：node deploy\verify-g3-longtext.js
 *
 * 前置：PC 前端 :80 + RuoYi 后端 :8080 + faze-api :8081 已启动。
 *
 * Chrome 必须这样起（三处缺一不可）：
 *   chrome.exe --remote-debugging-port=9222 --user-data-dir=<某目录>
 *              --disable-background-timer-throttling
 *              --disable-backgrounding-occluded-windows
 *              --disable-renderer-backgrounding
 *
 *   为什么必须关节流：Chrome 会把**后台标签页**的 requestAnimationFrame 节流，
 *   而 RuoYi 的 layout 用 <transition> 包 router-view，过渡靠 transitionend 收尾，
 *   一被节流就永远停在 fade-transform-leave-active —— **路由匹配对了、面包屑也变了，
 *   内容区还是上一页**。排查这个现象很容易误判成「组件没加载」或「路由没注册」。
 *   脚本内部还有两道兜底：Page.bringToFront + 注入禁用 fade-transform 的样式。
 *
 * 数据安全：脚本最后会用管理端 API 把 user_agreement_content 改回原值
 * （原值在第 4 步从页面里读出来），所以 operation_log 里「改动」与「恢复」是成对的。
 */
const CDP = 'http://127.0.0.1:9222';
const APP = 'http://localhost';
const TARGET_KEY = 'user_agreement_content';
const TEST_TEXT = [
  'G-3 多行编辑验证·第一行',
  '第二行：这一行的存在说明换行被保留了',
  '第三行：600 字法务正文的替身，编辑框必须装得下',
  '',
  '空行之后还有一行。',
  '下面补足长度，让这段测试正文也超过长文本阈值（80 字），否则保存后该行会变回短文本控件，',
  '测的就不再是同一个场景了。一段真实的法务条款本来就是这个量级：',
  '本平台仅提供法律知识与法律服务对接能力，运营主体信息以平台公示为准；',
  '平台不是律师事务所，不直接提供律师执业服务；具体法律服务由入驻律师依据执业规范提供。',
  '用户须以本人手机号注册，并对账号下的全部操作负责。'
].join('\n');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
let ws;
let msgId = 0;
const pending = new Map();

function check(name, ok, detail) {
  results.push({ name, ok, detail: detail || '' });
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${name}${detail ? ' :: ' + detail : ''}`);
}

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const mid = ++msgId;
    pending.set(mid, { resolve, reject });
    ws.send(JSON.stringify({ id: mid, method, params }));
  });
}

async function evalJs(expression) {
  const r = await send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true
  });
  if (r.exceptionDetails) {
    throw new Error('eval error: ' + JSON.stringify(r.exceptionDetails.exception || r.exceptionDetails));
  }
  return r.result.value;
}

async function waitFor(expression, timeoutMs, label) {
  const t0 = Date.now();
  let last = '';
  while (Date.now() - t0 < timeoutMs) {
    try {
      const v = await evalJs(expression);
      if (v) return v;
      last = String(v);
    } catch (e) {
      last = e.message;
    }
    await sleep(300);
  }
  throw new Error(`等待超时(${timeoutMs}ms): ${label}；最后值=${last}`);
}

async function clickByText(selector, text) {
  const expr = `(() => {
    const list = Array.from(document.querySelectorAll(${JSON.stringify(selector)}));
    const el = list.find((e) => e.textContent && e.textContent.includes(${JSON.stringify(text)}) && e.offsetParent !== null);
    if (!el) return false;
    el.click();
    return true;
  })()`;
  return evalJs(expr);
}

async function main() {
  // ── 1. 拿 admin token 并注入 Cookie，绕过登录页（不依赖页面登录的时序）──
  const loginRes = await fetch('http://127.0.0.1:8080/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  }).then((r) => r.json());
  if (loginRes.code !== 200) throw new Error('RuoYi 登录失败：' + JSON.stringify(loginRes));
  const token = loginRes.token;
  console.log(`[info] admin token 已获取（${token.length} 字符）`);

  const list = await (await fetch(CDP + '/json/list')).json();
  const page = list.find((t) => t.type === 'page');
  if (!page) throw new Error('没有可用的 page target');
  ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => {
    ws.onopen = res;
    ws.onerror = rej;
  });
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result);
    }
  };

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');
  // 关键：Chrome 把后台标签页的 requestAnimationFrame 节流掉，RuoYi 的 <transition>
  // 靠 transitionend 收尾，一被节流就永远停在 fade-transform-leave-active，
  // 旧组件不卸载、新组件不插入（实测：不调 bringToFront 时路由切了但内容区不动）。
  await send('Page.bringToFront');
  await send('Network.setCookie', {
    name: 'Admin-Token',
    value: token,
    domain: 'localhost',
    path: '/'
  });

  // ── 2. 打开系统参数页 ────────────────────────────────────────────────
  // 注意：不能靠给 location.hash 赋值来切路由——如果 hash 已经是目标值，
  // 浏览器不会触发 hashchange，页面就停在原地（实测踩过）。
  // 也不能直接 navigate 到 /#/biz/sysConfig：那时动态路由还没从 /getRouters 加载完，
  // 匹配不到会重定向到首页。所以先落在首页，等菜单渲染出来，再用 router.push。
  await send('Page.navigate', { url: `${APP}/index` });
  await sleep(3000);
  // 兜底：即使 bringToFront 没把窗口拉到前台（Chrome 被最小化时 tab 仍被节流），
  // 也不能让路由切换卡在 transition 的 leave 态。直接把 RuoYi 那组过渡动画关掉——
  // 只影响 fade-transform 这一组，不影响 el-dialog 等其它动画的可见性。
  await evalJs(`(() => {
    if (document.getElementById('cdp-no-transition')) return 'already';
    const s = document.createElement('style');
    s.id = 'cdp-no-transition';
    s.textContent = '.fade-transform-leave-active,.fade-transform-enter-active{transition:none !important;animation:none !important;}';
    document.head.appendChild(s);
    return 'injected';
  })()`);
  await waitFor(
    `document.querySelectorAll('.el-menu-item').length > 5`,
    45000,
    '侧边菜单渲染（说明动态路由已就绪）'
  );
  await evalJs(`(() => {
    const vue = document.querySelector('#app').__vue_app__;
    const router = vue.config.globalProperties.$router;
    router.push('/biz/sysConfig');
    return true;
  })()`);
  await sleep(1500);

  await waitFor(
    `document.querySelectorAll('.el-table__row').length > 0`,
    45000,
    '系统参数表格渲染'
  );
  const rowCount = await evalJs(`document.querySelectorAll('.el-table__row').length`);
  check('页面打开且表格有数据', rowCount > 0, `${rowCount} 行`);

  // ── 3. 长文本行必须显示摘要 + 「编辑正文（N 字）」，而不是单行输入框 ──
  const rowInfo = await evalJs(`(() => {
    const rows = Array.from(document.querySelectorAll('.el-table__row'));
    const row = rows.find((r) => r.textContent.includes(${JSON.stringify(TARGET_KEY)}));
    if (!row) return { found: false };
    const btn = Array.from(row.querySelectorAll('button')).find((b) => b.textContent.includes('编辑正文'));
    const innerInput = row.querySelector('input.el-input__inner');
    return {
      found: true,
      hasEditBtn: !!btn,
      btnText: btn ? btn.textContent.trim() : '',
      hasInlineInput: !!innerInput,
      preview: (row.querySelector('.long-text__preview') || {}).textContent || ''
    };
  })()`);
  check(`找到 ${TARGET_KEY} 行`, rowInfo.found === true);
  check('长文本行有「编辑正文（N 字）」按钮', rowInfo.hasEditBtn === true, rowInfo.btnText);
  check(
    '长文本行不再内联单行输入框',
    rowInfo.hasInlineInput === false,
    rowInfo.hasInlineInput ? '仍存在 input.el-input__inner' : '无内联 input'
  );
  check('摘要已渲染', (rowInfo.preview || '').length > 0, rowInfo.preview.slice(0, 40));

  // ── 4. 打开弹窗，记录原值 ────────────────────────────────────────────
  const opened = await evalJs(`(() => {
    const rows = Array.from(document.querySelectorAll('.el-table__row'));
    const row = rows.find((r) => r.textContent.includes(${JSON.stringify(TARGET_KEY)}));
    const btn = Array.from(row.querySelectorAll('button')).find((b) => b.textContent.includes('编辑正文'));
    btn.click();
    return true;
  })()`);
  check('点击「编辑正文」', opened === true);
  await waitFor(`document.querySelector('.el-dialog textarea') !== null`, 15000, '弹窗 textarea 出现');

  const dialogInfo = await evalJs(`(() => {
    const ta = document.querySelector('.el-dialog textarea');
    return { rows: ta.getAttribute('rows'), len: ta.value.length, firstLine: ta.value.split(String.fromCharCode(10))[0].slice(0, 20) };
  })()`);
  const originalText = await evalJs(`document.querySelector('.el-dialog textarea').value`);
  check('弹窗是多行 textarea', dialogInfo.rows !== null, `rows=${dialogInfo.rows}`);
  check('原值被载入编辑器', dialogInfo.len > 500, `${dialogInfo.len} 字`);

  // ── 5. 输入带换行的测试文本 ─────────────────────────────────────────
  await evalJs(`(() => {
    const ta = document.querySelector('.el-dialog textarea');
    ta.focus();
    ta.setSelectionRange(0, ta.value.length);
    return true;
  })()`);
  await send('Input.insertText', { text: TEST_TEXT });
  await sleep(400);

  const afterInput = await evalJs(`(() => {
    const v = document.querySelector('.el-dialog textarea').value;
    return {
      len: v.length,
      newlines: v.split(String.fromCharCode(10)).length - 1,
      same: v === ${JSON.stringify(TEST_TEXT)}
    };
  })()`);
  check('输入后文本与预期完全一致', afterInput.same === true, `len=${afterInput.len}`);
  const expectedNewlines = TEST_TEXT.split('\n').length - 1;
  check(
    `换行被保留（${expectedNewlines} 个 \\n）`,
    afterInput.newlines === expectedNewlines,
    `实际 ${afterInput.newlines} 个换行`
  );

  // ── 6. 确定 → 回到列表并出现「1 项待保存」 ──────────────────────────
  const confirmed = await clickByText('.el-dialog button', '确定');
  check('点击弹窗「确定」', confirmed === true);
  // 不判断「弹窗是否已从 DOM 消失」：el-dialog 带 destroy-on-close + 过渡动画，
  // 关闭瞬间元素还在（实测 offsetParent 判据不成立，会误报超时）。
  // 直接等真正有意义的状态——工具栏的待保存计数。
  const dirtyText = await waitFor(
    `(() => { const m = document.body.textContent.match(/(\\d+) 项待保存/); return m ? m[1] : null; })()`,
    15000,
    '出现待保存计数'
  );
  check('工具栏显示待保存 1 项', String(dirtyText) === '1', `实际 ${dirtyText}`);

  // ── 7. 保存 → 二次确认 → 成功 ───────────────────────────────────────
  await clickByText('.table-toolbar__right button', '保存修改');
  await waitFor(`document.querySelector('.el-message-box') !== null`, 10000, '保存确认框出现');
  await clickByText('.el-message-box button', '确认保存');
  await waitFor(
    `document.querySelector('.el-message--success') !== null || document.querySelectorAll('.el-message__content').length > 0`,
    20000,
    '保存成功提示'
  );
  // 保存后会重新 loadList，列表是异步重建的——不能固定 sleep 后就看，要轮询等摘要更新
  const savedPreview = await waitFor(
    `(() => {
      const rows = Array.from(document.querySelectorAll('.el-table__row'));
      const row = rows.find((r) => r.textContent.includes(${JSON.stringify(TARGET_KEY)}));
      const p = row ? row.querySelector('.long-text__preview') : null;
      return p && p.textContent.includes('G-3 多行编辑验证') ? p.textContent : null;
    })()`,
    20000,
    '保存后列表摘要更新为新正文'
  );
  check('保存后列表摘要变成新正文的开头', true, savedPreview.slice(0, 40));

  // ── 8. 改回原值 ─────────────────────────────────────────────────────
  // 走 API 而不是再点一遍 UI：UI 的编辑/保存路径在第 4~7 步已经验过，
  // 这一步唯一目的是「一定把真实数据还原」，少一次人工点击就少一个失败面。
  console.log('[info] 开始恢复原值（走管理端 API）…');
  const putRes = await fetch('http://127.0.0.1:8081/api/v1/admin/sys-configs', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ items: [{ configKey: TARGET_KEY, configValue: originalText }] })
  }).then((r) => r.json());
  check(
    '已用 API 恢复原值',
    putRes.code === 200 || putRes.code === 0,
    JSON.stringify(putRes).slice(0, 120)
  );

  // ── 汇总 ───────────────────────────────────────────────────────────
  const failed = results.filter((r) => !r.ok);
  console.log(`\n===== G-3 结果：${results.length - failed.length}/${results.length} PASS =====`);
  if (failed.length) {
    failed.forEach((f) => console.log(`  FAIL: ${f.name} :: ${f.detail}`));
    process.exitCode = 1;
  }
  ws.close();
}

main().catch((e) => {
  console.error('[FATAL]', e.message);
  process.exitCode = 2;
});
